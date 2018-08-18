(ns pullql.core
  (:require
   [clojure.set :as set]
   [clojure.spec.alpha :as s]
   [datascript.core :as d]
   [datascript.db :as db-internals])
  (:import [datascript.db Datom]))

;; GRAMMAR

(s/def ::pattern (s/coll-of ::attr-spec))
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

(comment

  (parse '[:human/name :human/starships])
  
  (parse '{:human [:human/name
                   {:human/starships [:ship/name :ship/class]}]})

  (time
   (parse-memoized '{:human [[:db/id 1002]
                             :human/name
                             {:human/starships [[:ship/name "Anubis"]
                                                :ship/class]}]})))

;; INTERPRETER

(defn- is-attr? [db attr property]
  (contains? (db-internals/-attrs-by db property) attr))

(defmulti ^:private impl (fn [ctx node] (first node)))

(defn- pull-pattern
  ([db pattern] (pull-pattern db pattern #{} true))
  ([db pattern eids] (pull-pattern db pattern eids false))
  ([db pattern eids root?]
   (let [ctx {:db         db
              :entities   {}
              :root?      root?
              :eids       eids
              :eid-filter identity}]
     (impl ctx [:pattern pattern]))))

(defmethod impl :pattern [ctx [_ specs]]
  (reduce impl ctx specs))

(defmethod impl :attribute [{:keys [db eids root?] :as ctx} [_ attr]]
  (let [datoms     (if root?
                     (d/datoms db :aevt attr)
                     (sequence (filter (fn [^Datom d] (contains? eids (.-e d)))) (d/datoms db :aevt attr)))
        with-datom (if (is-attr? db attr :db.type/ref)
                     (fn [entities ^Datom d] (update-in entities [(.-e d) attr] conj (.-v d)))
                     (fn [entities ^Datom d] (assoc-in entities [(.-e d) attr] (.-v d))))]
    (update ctx :entities #(reduce with-datom % datoms))))

(defmethod impl :expand [{:keys [db eids root?] :as ctx} [_ map-spec]]
  (let [[attr pattern]    (first map-spec)
        datoms            (if root?
                            (d/datoms db :aevt attr)
                            (sequence (filter (fn [^Datom d] (contains? eids (.-e d)))) (d/datoms db :aevt attr)))
        child-eids        (into #{} (map (fn [^Datom d] (.-v d))) datoms)
        child-ctx         (pull-pattern db pattern child-eids)
        child-filter      (:eid-filter child-ctx)
        matching-children (select-keys (:entities child-ctx) (child-filter (:eids child-ctx)))
        with-datom        (if (is-attr? db attr :db.cardinality/many)
                            (fn [entities ^Datom d]
                              (if-some [[_ child] (find matching-children (.-v d))]
                                (update-in entities [(.-e d) attr] conj child)
                                entities))
                            (fn [entities ^Datom d]
                              (if-some [[_ child] (find matching-children (.-v d))]
                                (assoc-in entities [(.-e d) attr] child)
                                entities)))]
    (update ctx :entities #(reduce with-datom % datoms))))

(defmethod impl :clause [{:keys [db] :as ctx} [_ clause]]
  (let [[_ data-pattern] clause
        [attr v]         data-pattern
        indexed?         (is-attr? db attr :db/index)
        matching-datoms  (if indexed?
                           (d/datoms db :avet attr v)
                           (->> (d/datoms db :aevt attr)
                                (sequence (filter (fn [^Datom d] (= (.-v d) v))))))
        with-datom       (if (is-attr? db attr :db.type/ref)
                           (fn [entities ^Datom d] (update-in entities [(.-e d) attr] conj (.-v d)))
                           (fn [entities ^Datom d] (assoc-in entities [(.-e d) attr] (.-v d))))]
    (-> ctx
        (update :eid-filter comp (partial set/intersection (into #{} (map #(.-e %)) matching-datoms)))
        (update :entities #(reduce with-datom % matching-datoms)))))

;; PUBLIC API

(defn pull-all [db query]
  (let [pattern           (parse-memoized query)
        ctx               (pull-pattern db pattern)
        ;; keep only matching entities
        entity-filter     (:eid-filter ctx)
        entities          (:entities ctx)
        matching-entities (into [] (map entities) (entity-filter (into #{} (keys entities))))]
    matching-entities))
