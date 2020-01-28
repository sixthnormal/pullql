(ns pullql.core
  (:require
   [clojure.set :as set]
   #?(:clj [clojure.spec.alpha :as s]
      :cljs [cljs.spec.alpha :as s])
   [datascript.core :as d]
   [datascript.db :as db-internals :refer [datom-tx datom-added #?(:cljs Datom)]]
   [pullql.walk :as walk])
  #?(:clj (:import [datascript.db Datom])))

;; GRAMMAR

(s/def ::pattern (s/coll-of ::attr-spec :kind vector?))
(s/def ::attr-spec (s/or :attribute ::attr-name
                         :clause ::clause
                         :expand ::map-spec))
(s/def ::attr-name keyword?)
(s/def ::clause (s/or :data-pattern ::data-pattern))
(s/def ::data-pattern (s/tuple ::attr-name ::constant))
(s/def ::constant (constantly true)) ;; @TODO
(s/def ::map-spec (s/and (s/map-of ::attr-name ::pattern)
                         #(= (count %) 1)))

(s/def ::query ::pattern)

;; PARSER

(defn parse [query]
  (let [conformed (s/conform ::query query)]
    (if (s/invalid? conformed)
      (throw (ex-info "Couldn't parse query" (s/explain-data ::query query)))
      conformed)))

(def ^{:arglists '([query])} parse-memoized (memoize parse))

;; INTERPRETER

(defn is-attr?
  "Returns true iff the attribute is annotated with a property such as
  `:db/index` or `:db.cardinality/many` in the schema."
  [db attr property]
  (contains? (db-internals/-attrs-by db property) attr))

(defn is-derived?
  "Returns true iff the attribute is derived, rather than stored in the
  database."
  [db attr]
  (get-in db [:schema attr :db/derived]))

(defn reverse-ref?
  "Returns true iff the attribute is a reverse reference, such as
  `:parent/_child`."
  [attr]
  (= \_ (nth (name attr) 0)))

(defn reverse-ref
  "Returns the inverted reference. E.g. `:parent/child` becomes
  `:parent/_child` and vice versa."
  [attr]
  (if (reverse-ref? attr)
    (keyword (namespace attr) (subs (name attr) 1))
    (keyword (namespace attr) (str "_" (name attr)))))

(defn normalized-ref
  "Returns the version of a reference that is guaranteed to be contained
  in the schema. E.g. `:parent/_child` is normalized to
  `:parent/child`."
  [attr]
  (if (reverse-ref? attr)
    (reverse-ref attr)
    attr))

(defmulti ^:private impl (fn [ctx node] (first node)))

(defn- pull-attr
  ([db read-fn attr]
   (cond
     (is-derived? db attr) (read-fn attr db nil nil)
     (reverse-ref? attr)   (->> (d/datoms db :avet (reverse-ref attr))
                                (sequence (map (fn [^Datom d]
                                                 (db-internals/datom (.-v d) attr (.-e d) (datom-tx d) (datom-added d))))))
     :else                 (d/datoms db :aevt attr)))
  ([db read-fn attr eids]
   (cond
     (is-derived? db attr) (read-fn attr db eids nil)
     (reverse-ref? attr)   (->> (d/datoms db :avet (reverse-ref attr))
                                (sequence (comp
                                           (filter (fn [^Datom d] (contains? eids (.-v d))))
                                           (map (fn [^Datom d]
                                                  (db-internals/datom (.-v d) attr (.-e d) (datom-tx d) (datom-added d)))))))
     :else                 (->> (d/datoms db :aevt attr)
                                (sequence (filter (fn [^Datom d] (contains? eids (.-e d)))))))))

(defn- pull-pattern
  ([db read-fn pattern] (pull-pattern db read-fn pattern #{} true))
  ([db read-fn pattern eids] (pull-pattern db read-fn pattern eids false))
  ([db read-fn pattern eids root?]
   (let [ctx {:db         db
              :read-fn    read-fn
              :entities   {}
              :root?      root?
              :eids       eids
              :eid-filter identity}]
     (impl ctx [:pattern pattern]))))

(defmethod impl :pattern [ctx [_ specs]]
  (reduce impl ctx specs))

(defmethod impl :attribute [{:keys [db read-fn eids root?] :as ctx} [_ attr]]
  (let [datoms     (if root?
                     (pull-attr db read-fn attr)
                     (pull-attr db read-fn attr eids))
        with-datom (if (is-attr? db attr :db.cardinality/many)
                     (fn [entities ^Datom d] (update-in entities [(.-e d) attr] conj (.-v d)))
                     (fn [entities ^Datom d] (assoc-in entities [(.-e d) attr] (.-v d))))]
    (update ctx :entities #(reduce with-datom % datoms))))

(defmethod impl :expand [{:keys [db read-fn eids root?] :as ctx} [_ map-spec]]
  (let [[attr pattern]    (first map-spec)
        datoms            (if root?
                            (pull-attr db read-fn attr)
                            (pull-attr db read-fn attr eids))
        child-eids        (into #{} (map (fn [^Datom d] (.-v d))) datoms)
        child-ctx         (pull-pattern db read-fn pattern child-eids)
        child-filter      (:eid-filter child-ctx)
        matching-children (select-keys (:entities child-ctx) (child-filter (:eids child-ctx)))
        ;; Do we need to pull :db/id?
        pull-eid?         (some #{[:attribute :db/id]} pattern)
        ;; We wrap (find matching-children <eid>) in order to pull
        ;; :db/id on-the-fly.
        find-child        (if pull-eid?
                            (fn [eid]
                              (when-some [child (get matching-children eid)]
                                [eid (assoc child :db/id eid)]))
                            (partial find matching-children))
        with-datom        (if (is-attr? db attr :db.cardinality/many)
                            (fn [entities ^Datom d]
                              (if-some [[_ child] (find-child (.-v d))]
                                (update-in entities [(.-e d) attr] conj child)
                                entities))
                            (fn [entities ^Datom d]
                              (if-some [[_ child] (find-child (.-v d))]
                                (assoc-in entities [(.-e d) attr] child)
                                entities)))]
    (update ctx :entities #(reduce with-datom % datoms))))

(defmethod impl :clause [{:keys [db read-fn] :as ctx} [_ clause]]
  (let [[_ data-pattern] clause
        [attr v]         data-pattern
        indexed?         (is-attr? db attr :db/index)
        derived?         (is-derived? db attr)
        reverse?         (reverse-ref? attr)
        placeholder?     (= '_ v)
        matching-datoms  (cond
                           derived?     (read-fn attr db nil (when-not placeholder? #{v})) ; @TODO deal with eids here?
                           reverse?     (->> (d/datoms db :avet (reverse-ref attr))
                                             (sequence (map (fn [^Datom d]
                                                              (db-internals/datom (.-v d) attr (.-e d) (datom-tx d) (datom-added d))))))
                           placeholder? (d/datoms db :aevt attr)
                           indexed?     (d/datoms db :avet attr v)
                           :else        (->> (d/datoms db :aevt attr)
                                             (sequence (filter (fn [^Datom d] (= (.-v d) v))))))
        with-datom       (if (is-attr? db attr :db.type/ref)
                           (fn [entities ^Datom d] (update-in entities [(.-e d) attr] conj (.-v d)))
                           (fn [entities ^Datom d] (assoc-in entities [(.-e d) attr] (.-v d))))]
    (-> ctx
        (update :eid-filter comp (partial set/intersection (into #{} (map #(.-e %)) matching-datoms)))
        (update :entities #(reduce with-datom % matching-datoms)))))

(defn- read-fn-unimplemented [attr db eids vals]
  (throw (ex-info "No read-fn specified." {:attr attr
                                           :eids eids
                                           :vals vals})))

;; PUBLIC API

(defn pull-all
  ([db query] (pull-all db query read-fn-unimplemented))
  ([db query read-fn]
   (if-not (map? query)
     (pull-all db ::default-alias query read-fn)
     (as-> query aliases
       (reduce-kv
        (fn [result alias sub-query]
          (assoc result alias (pull-all db alias sub-query read-fn)))
        {} aliases))))
  ([db alias query read-fn]
   (let [pattern           (parse-memoized query)
         ctx               (pull-pattern db read-fn pattern)
         ;; keep only matching entities
         entity-filter     (:eid-filter ctx)
         entities          (:entities ctx)
         ;; do we need to pull :db/id?
         pull-eid?         (some #{[:attribute :db/id]} pattern)
         extract-entity    (if pull-eid?
                             (fn [eid] (assoc (entities eid) :db/id eid))
                             entities)
         matching-entities (into [] (map extract-entity) (entity-filter (into #{} (keys entities))))]
     matching-entities)))

(defn view-all!
  ([conn name query then!] (view-all! conn name query read-fn-unimplemented then!))
  ([conn name query read-fn then!]
   (let [pattern      (parse-memoized query)
         ;; Collect all attributes that this query depends on.
         dependencies (walk/dependencies pattern)
         ;; Do we need to pull :db/id at the top-level?
         pull-eid?    (some #{[:attribute :db/id]} pattern)
         maintain!    (fn [{:keys [db-after tx-data]}]
                        (when (some dependencies (map :a tx-data))
                          (let [ctx               (pull-pattern db-after read-fn pattern)
                                ;; keep only matching entities
                                entity-filter     (:eid-filter ctx)
                                entities          (:entities ctx)
                                extract-entity    (if pull-eid?
                                                    (fn [eid] (assoc (entities eid) :db/id eid))
                                                    entities)
                                matching-entities (into [] (map extract-entity) (entity-filter (into #{} (keys entities))))]
                            (then! db-after matching-entities))))]
     (d/listen! conn name maintain!))))

(defn merge-queries [& qs]
  (into [] (distinct) (apply concat qs)))

(defn derive-from-query
  [attr query f db eids values]
  (let [query    (merge-queries query [:db/id])
        entities (if (some? eids)
                   (d/pull-many db query eids)
                   (pull-all db query))
        ->datom  (fn [entity]
                   (when-some [v (f entity)]
                     (when (or (nil? values)
                               (contains? values v))
                       (d/datom (:db/id entity) attr v (:max-tx db) true))))]
    (->> entities
         (map ->datom)
         (remove nil?))))
