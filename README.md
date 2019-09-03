# Pull QL

An extension of Datomic's pull query language, optimized for execution
across all entities in a Datomic/DataScript database. For many
entities, it is between one and two orders of magnitude faster than
`d/pull-many`. PullQL is thus intended to fill a similar role as
GraphQL. 

**Features**

- [x] Basic pull functionality
- [x] Multiple top-level queries via aliases
- [x] Filter clauses on both root entities and children
- [x] Derived attributes
- [x] ClojureScript support

A previous iteration of this language is described in detail in [0].

``` clojure
(require '[pullql.core :refer [pull-all]])

;; simplest possible pull query
(pull-all db '[:constellation/name])

;; recursively querying relations
(pull-all db '[:constellation/name
               {:constellation/scenario [:scenario/name]}])
			   
;; multiple top-level queries via aliasing
(pull-all db '{:structure [:constellation/name 
                           {:constellation/scenario [:scenario/name]}]
               :detail    [[:scenario/name "Testszenario"]
			               {:scenario/discourse [:discourse/name :discourse/niveau]}]})
               
;; additionally, PullQL supports filters
(pull-all db '[[:constellation/name "Fachstationen"]
               {:constellation/scenario [:scenario/name]}])
               
;; filters can appear in nested clauses as well
(pull-all db '[:constellation/name
               {:constellation/scenario [:scenario/name
                                         {:scenario/discourse [:discourse/name
	                                                      [:discourse/niveau 1]]}]}])
															   
;; filters support a wildcard '_', expressing a required attribute
(pull-all db '[[:constellation/name _]
               {:constellation/scenario [:scenario/name]}])

(pull-all db '[:constellation/name
               {:constellation/scenario [:scenario/name
                                         {:scenario/discourse [:discourse/name
						              [:discourse/niveau _]]}]}])

```

## Derived Attributes

Derived attributes can be thought of as relations between entities and
values whose datoms are computed on the fly, rather than being stored
permanently. A similar implementation is explained in [1].

Derived attributes must be annotated as `:db/valueType
:db.type/derived` in the schema. Definitions for all derived
attributes are specified via a function:

@TODO

## Sources

- [0][https://www.nikolasgoebel.com/2018/06/26/a-query-language.html]
- [1][http://www.nikolasgoebel.com/2018/03/25/derived-attributes-datascript.html]
