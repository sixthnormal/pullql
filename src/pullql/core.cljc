(ns pullql.core
  (:require
   [clojure.set :as set]
   [clojure.spec.alpha :as s]
   [datascript.core :as d]
   [datascript.db :as db-internals])
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

(defn- is-attr? [db attr property]
  (contains? (db-internals/-attrs-by db property) attr))

(defmulti ^:private impl (fn [ctx node] (first node)))

(defn- pull-attr
  ([db read-fn attr]
   (if (is-attr? db attr :db.type/derived)
     (read-fn attr db nil nil)
     (d/datoms db :aevt attr)))
  ([db read-fn attr eids]
   (if (is-attr? db attr :db.type/derived)
     (read-fn attr db eids nil)
     (->> (d/datoms db :aevt attr)
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
        derived?         (is-attr? db attr :db.type/derived)
        placeholder?     (= '_ v)
        matching-datoms  (cond
                           derived?     (read-fn attr db nil #{v}) ; @TODO deal with eids here?
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

;; PUBLIC API

(defn pull-all
  ([db query] (pull-all db query (fn [attr db eids] (throw (ex-info "No read-fn specified." {:attr attr})))))
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

(defn merge-queries [& qs]
  (into [] (distinct) (apply concat qs)))
