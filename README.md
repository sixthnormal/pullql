# Pull QL

An extension of Datomic's pull query language, optimized for execution
across all entities in a Datomic/DataScript database. For many
entities, it is between one and two orders of magnitude faster than
`d/pull-many`. PullQL is thus intended to fill a similar role as
GraphQL. 

In addition to the basic pull functionality, PullQL supports filters
and derived attributes.

A previous iteration of this language is described in detail in [0].

``` clojure
(require '[pullql.core :refer [pull-all]])

;; simplest possible pull query
(pull-all db '[:constellation/name])

;; recursively querying relations
(pull-all db '[:constellation/name
               {:constellation/scenario [:scenario/name]}])
               
;; additionally, PullQL supports filters
(pull-all db '[[:constellation/name "Fachstationen"]
               {:constellation/scenario [:scenario/name]}])
               
;; filters can appear in nested clauses as well
(pull-all db '[:constellation/name
               {:constellation/scenario [:scenario/name
                                         {:scenario/discourse [:discourse/name
										                       [:discourse/niveau 1]]}]}])
```

## Derived Attributes

@TODO

## ClojureScript Support

@TODO

[0][https://www.nikolasgoebel.com/2018/06/26/a-query-language.html]
