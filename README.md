# PullQL

[![Clojars Project](https://img.shields.io/clojars/v/com.sixthnormal/pullql.svg)](https://clojars.org/com.sixthnormal/pullql)
[![cljdoc badge](https://cljdoc.org/badge/com.sixthnormal/pullql)](https://cljdoc.org/d/com.sixthnormal/pullql/CURRENT)

Declarative query languages like [GraphQL](https://graphql.org/) and
[Datomic Pull](https://docs.datomic.com/on-prem/pull.html) are popular
means of decoupling a normalized data model from its inherently
hierarchical display in a typical web UI. PullQL is an alternative
query language for DataScript, that is optimized for this use case.

PullQL extends Datomic pull queries in a few ways:

- [x] Selections
- [x] Multiple top-level queries via aliases
- [x] Derived attributes
- [x] Usable in Clojure and ClojureScript environments

PullQL *should* work on Datomic databases as well.

# Usage

All of the examples assume a given DataScript schema and some initial data.

``` clojure
(require '[datascript.core :as d])
(require '[pullql.core :refer [pull-all]])

(def schema
  {:human/name      {}
   :human/starships {:db/valueType   :db.type/ref
                     :db/cardinality :db.cardinality/many}
   :ship/name       {}
   :ship/class      {}})

(def data
 [{:human/name      "Naomi Nagata"
   :human/starships [{:db/id -1 :ship/name "Roci" :ship/class :ship.class/fighter}
                     {:ship/name "Anubis" :ship/class :ship.class/science-vessel}]}
  {:human/name      "Amos Burton"
   :human/starships [-1]}])

(def db
  (-> (d/empty-db schema)
      (d/db-with data)))
```

## Basics

The simplest possible PullQL query asks for one or more attributes across an entire database.

``` clojure
(pull-all db '[:human/name])

;; => [#:human{:name "Naomi Nagata"} 
;;     #:human{:name "Amos Burton"}]
```

``` clojure
(pull-all db '[:ship/name :ship/class])

;; => [#:ship{:name "Anubis", :class :ship.class/science-vessel} 
;;     #:ship{:name "Roci", :class :ship.class/fighter}]
```

Pulling a reference such as `:human/starships` resolves to a sequence of related entity ids.

``` clojure
(pull-all db '[:human/name :human/starships])

;; => [#:human{:name "Naomi Nagata", :starships (3 2)}
;;     #:human{:name "Amos Burton", :starships (2)}]
```

Referenced entities can be pulled recursively.

``` clojure
(pull-all db '[:human/name
               {:human/starships [:ship/name
                                  :ship/class]}])

;; => [#:human{:name      "Naomi Nagata",
;;             :starships (#:ship{:name  "Anubis",
;;                                :class :ship.class/science-vessel}
;;                         #:ship{:name  "Roci",
;;                                :class :ship.class/fighter})}
;;      #:human{:name "Amos Burton",
;;              :starships (#:ship{:name "Roci", 
;;	                           :class :ship.class/fighter})}]
```

Just like in Datomic and DataScript, references can be traversed in both directions. An underscore such as is in `:human/_starships` names the reverse relation.

``` clojure
(pull-all db '[:ship/name :human/_starships])

;; => [{:ship/name "Anubis", :human/_starships 1}
;;     {:ship/name "Roci", :human/_starships 4}]
```

## Selections

Most of the time we are interested in pulling only a subset of entities in the database. This is expressed by adding selective clauses to a PullQL query.

``` clojure
;; Pull ships for a specific human.
(pull-all db '[[:human/name "Naomi Nagata"] 
               {:human/starships [:ship/name :ship/class]}])

;; => [#:human{:name "Naomi Nagata", 
;;             :starships ({:ship/name "Anubis", 
;;                          :ship/class :ship.class/science-vessel}
;;                         {:ship/name "Roci",
;;                          :ship/class :ship.class/fighter})}]

;; Pull all humans, but include only ships of the fighter class.
(pull-all db '[:human/name
               {:human/starships [:ship/name
                                  [:ship/class :ship.class/fighter]]}])

;; => [#:human{:name "Naomi Nagata", 
;;             :starships (#:ship{:name "Roci", :class :ship.class/fighter})}
;;     #:human{:name "Amos Burton", 
;;             :starships (#:ship{:name "Roci", :class :ship.class/fighter})}]
```

We can use the wildcard symbol `_` whenever the precise value of an attribute is not relevant, as long as the entity is guaranteed to have *some* value associated for it.

``` clojure
(pull-all db '[[:constellation/name _]
               {:constellation/scenario [:scenario/name]}])

(pull-all db '[:human/name
               {:human/starships [:ship/name
                                  [:ship/class _]]}])
```

## Aliases

Multiple pull expressions can be sent as a single query using aliases.

``` clojure
(pull-all db '{:ship-detail [[:ship/name "Roci"] :ship/class]
               :all-classes [[:ship/class _]]})
	       
;; => {:ship-detail [#:ship{:name "Roci", :class :ship.class/fighter}], 
;;     :all-classes [#:ship{:class :ship.class/science-vessel}
;;                   #:ship{:class :ship.class/fighter}]}
```

## Derived Attributes

Derived attributes are computed on-the-fly when needed, rather than being stored
permanently. A similar implementation is explained in [1]. 

Derived attributes must be annotated as `:db/valueType :db.type/derived` in the schema. 

``` clojure
(def schema 
  {:human/name      {}
   :human/starships {:db/valueType   :db.type/ref
                     :db/cardinality :db.cardinality/many}
		     
   :ship/name       {}
   :ship/class      {}
   :ship/price      {:db/derived true}})
```

Implementations for any derived attributes must be provided via a function or multimethod. This function will be called with the name of the derived attribute, the current database value, and sets of entities and values to filter for. The function is expected to return datoms that will be incorporated into the result set, just as if they were stored in the database.

Most derivations follow the same schema: query a few existing (materialized) entities, compute some function of each one, and wrap the results in datoms. The `derive-from-query` helper encapsulates this pattern.

``` clojure
(require '[pullql.core :refer [derive-from-query]])

(defn read [attr db eids values]
  (case attr
    :ship/price (derive-from-query :ship/price
                                   [:db/id :ship/class]
                                   (fn [ship]
                                     (case (:ship/class ship)
                                       :ship.class/fighter        1000
                                       :ship.class/science-vessel 5000))
                                   db eids values)
    []))
```

With the `read` function defined, we can start using derived attributes in queries. Note how the `read` function must be passed to `pull-all` now.

``` clojure
(pull-all db '[:ship/name :ship/price] read)

;; => [#:ship{:name "Anubis", :price 5000} 
;;     #:ship{:name "Roci", :price 1000}]

(pull-all db '[{:human/starships [:ship/name :ship/price]}] read)

;; => [#:human{:starships (#:ship{:name "Anubis", :price 5000}
;;                         #:ship{:name "Roci", :price 1000})}
;;     #:human{:starships (#:ship{:name "Roci", :price 1000})}]
```

Selective clauses can be put on derived attributes as well.

``` clojure
(pull-all db '[:ship/name [:ship/price 1000]] read)

;; => [#:ship{:name "Roci", :price 1000}]
```

## Sources

A previous iteration of this language is described in detail in [0].

- [0][https://www.nikolasgoebel.com/2018/06/26/a-query-language.html]
- [1][http://www.nikolasgoebel.com/2018/03/25/derived-attributes-datascript.html]
