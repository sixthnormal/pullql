(ns pullql.core-test
  (:require
   [clojure.test :refer [deftest is testing run-tests]]
   [pullql.core :refer [parse pull-all]]
   [datascript.core :as d]))

(deftest test-parse
  (is (= [[:attribute :human/name] [:attribute :human/starships]]
         (parse '[:human/name :human/starships])))

  (is (= [[:attribute :human/name]
          [:expand {:human/starships [[:attribute :ship/name]
                                      [:attribute :ship/class]]}]]
         (parse '[:human/name
                  {:human/starships [:ship/name :ship/class]}])))

  (is (= [[:clause [:data-pattern [:db/id 1002]]]
          [:attribute :human/name]
          [:expand {:human/starships [[:clause [:data-pattern [:ship/name "Anubis"]]]
                                      [:attribute :ship/class]]}]]
         (parse '[[:db/id 1002]
                  :human/name
                  {:human/starships [[:ship/name "Anubis"]
                                     :ship/class]}])))

  (is (= [[:clause [:data-pattern [:db/id 1002]]]
          [:clause [:data-pattern [:human/name '_]]]
          [:expand {:human/starships [[:clause [:data-pattern [:ship/name "Anubis"]]]
                                      [:attribute :ship/class]]}]]
         (parse '[[:db/id 1002]
                  [:human/name _]
                  {:human/starships [[:ship/name "Anubis"]
                                     :ship/class]}]))))

(deftest test-pull-all
  (let [schema {:human/name      {}
                :human/starships {:db/valueType   :db.type/ref
                                  :db/cardinality :db.cardinality/many}
                :ship/name       {}
                :ship/class      {}}
        data   [{:human/name      "Naomi Nagata"
                 :human/starships [{:db/id -1 :ship/name "Roci" :ship/class :ship.class/fighter}
                                   {:ship/name "Anubis" :ship/class :ship.class/science-vessel}]}
                {:human/name      "Amos Burton"
                 :human/starships [-1]}]
        db     (-> (d/empty-db schema)
                   (d/db-with data))]
    
    (is (= #{["Amos Burton"] ["Naomi Nagata"]}
           (d/q '[:find ?name :where [?e :human/name ?name]] db)))

    (is (= (set (d/q '[:find [(pull ?e [:human/name]) ...] :where [?e :human/name _]] db))
           (set (pull-all db '[:human/name]))))

    (testing "recursive resolution of expand patterns"
      ;; @TODO can't compare w/ d/q directly, because order of
      ;; children is different
      (is (= [#:human{:name      "Naomi Nagata"
                      :starships '(#:ship{:name "Anubis" :class :ship.class/science-vessel} #:ship{:name "Roci" :class :ship.class/fighter})}
              #:human{:name      "Amos Burton"
                      :starships '(#:ship{:name "Roci" :class :ship.class/fighter})}]
             (pull-all db '[:human/name
                            {:human/starships [:ship/name
                                               :ship/class]}]))))

    (testing "top-level filter clause"
      (is (= [#:human{:name      "Naomi Nagata"
                      :starships '(#:ship{:name "Anubis" :class :ship.class/science-vessel} #:ship{:name "Roci" :class :ship.class/fighter})}]
             (pull-all db '[[:human/name "Naomi Nagata"] 
                            {:human/starships [:ship/name :ship/class]}]))))

    (testing "top-level filter clause w/ placeholder"
      (is (= [#:human{:name      "Naomi Nagata"
                      :starships '(#:ship{:name "Anubis" :class :ship.class/science-vessel} #:ship{:name "Roci" :class :ship.class/fighter})}
              #:human{:name "Amos Burton"
                      :starships '(#:ship{:name "Roci" :class :ship.class/fighter})}]
             (pull-all db '[[:human/name _]
                            {:human/starships [:ship/name :ship/class]}]))))
    
    (testing "nested filter clause"
      (is (= [#:human{:name      "Naomi Nagata"
                      :starships '(#:ship{:name "Roci" :class :ship.class/fighter})}
              #:human{:name      "Amos Burton"
                      :starships '(#:ship{:name "Roci" :class :ship.class/fighter})}]
             (pull-all db '[:human/name
                            {:human/starships [:ship/name
                                               [:ship/class :ship.class/fighter]]}]))))

    (testing "nested filter clause w/ placeholder"
      (is (= [#:human{:name      "Naomi Nagata"
                      :starships '(#:ship{:name "Anubis" :class :ship.class/science-vessel} #:ship{:name "Roci" :class :ship.class/fighter})}
              #:human{:name      "Amos Burton"
                      :starships '(#:ship{:name "Roci" :class :ship.class/fighter})}]
             (pull-all db '[:human/name
                            {:human/starships [:ship/name
                                               [:ship/class _]]}]))))

    
    (testing "multiple top-level patterns are supported via aliases"
      (let [query-a '[:human/name
                      {:human/starships [:ship/name
                                         :ship/class]}]
            query-b '[:ship/class]]
        (is (= {:human   (pull-all db query-a)
                :classes (pull-all db query-b)}
               (pull-all db {:human   query-a
                             :classes query-b})))))))
