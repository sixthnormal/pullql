(ns pullql.core-test
  (:require
   [clojure.test :refer [deftest is testing run-tests]]
   [pullql.core :refer [parse pull-all derive-from-query]]
   [datascript.core :as d]))

(deftest test-parse
  (is (= [[:attribute :human/name] [:attribute :human/starships]]
         (parse '[:human/name :human/starships])))

  (is (= [[:attribute :db/id]
          [:attribute :human/name]
          [:expand {:human/starships [[:attribute :ship/name]
                                      [:attribute :ship/class]]}]]
         (parse '[:db/id
                  :human/name
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

    (testing "pulling :db/id"
      (is (= (set (d/q '[:find [(pull ?e [:db/id :human/name]) ...] :where [?e :human/name _]] db))
             (set (pull-all db '[:db/id :human/name])))))

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

    (testing "resolution of reverse attributes"
      (is (= [{:ship/name        "Anubis"
               :human/_starships 1}]
             (pull-all db '[[:ship/name "Anubis"]
                            :human/_starships]))))

    (testing "constraints on reverse attributes"
      (is (= [{:ship/name        "Anubis"
               :human/_starships 1}
              {:ship/name        "Roci"
               :human/_starships 4}]
             (pull-all db '[:ship/name
                            [:human/_starships _]]))))

    (testing "recursive resolution of nested reverse attributes"
      (is (= [{:ship/name        "Anubis"
               :human/_starships {:db/id      1
                                  :human/name "Naomi Nagata"}}]
             (pull-all db '[[:ship/name "Anubis"]
                            {:human/_starships [:db/id :human/name]}]))))

    (testing "top-level filter clause"
      (is (= [#:human{:name      "Naomi Nagata"
                      :starships '({:db/id 3 :ship/name "Anubis" :ship/class :ship.class/science-vessel}
                                   {:db/id 2 :ship/name "Roci" :ship/class :ship.class/fighter})}]
             (pull-all db '[[:human/name "Naomi Nagata"] 
                            {:human/starships [:db/id :ship/name :ship/class]}]))))

    (testing "top-level filter clause w/ placeholder"
      (is (= [#:human{:name      "Naomi Nagata"
                      :starships '(#:ship{:name "Anubis" :class :ship.class/science-vessel} #:ship{:name "Roci" :class :ship.class/fighter})}
              #:human{:name      "Amos Burton"
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

(deftest test-derived
  (let [read   (fn [attr db eids values]
                 (case attr
                   :ship/price (derive-from-query :ship/price
                                                  [:db/id :ship/class]
                                                  (fn [ship]
                                                    (case (:ship/class ship)
                                                      :ship.class/fighter        1000
                                                      :ship.class/science-vessel 5000))
                                                  db eids values)
                   []))
        schema {:human/name      {}
                :human/starships {:db/valueType   :db.type/ref
                                  :db/cardinality :db.cardinality/many}
                :ship/name       {}
                :ship/class      {}
                :ship/price      {:db/derived true}}
        data   [{:human/name      "Naomi Nagata"
                 :human/starships [{:db/id -1 :ship/name "Roci" :ship/class :ship.class/fighter}
                                   {:ship/name "Anubis" :ship/class :ship.class/science-vessel}]}
                {:human/name      "Amos Burton"
                 :human/starships [-1]}
                {:ship/name "Weirdo"}]
        db     (-> (d/empty-db schema)
                   (d/db-with data))]
    
    (testing "derived attributes"
      (is (= #{{:ship/name "Roci" :ship/price 1000}
               {:ship/name "Anubis" :ship/price 5000}
               {:ship/name "Weirdo"}}
             (set (pull-all db '[:ship/name :ship/price] read)))))

    (testing "derived attributes on nested entities"
      (is (= #{{:human/starships [{:ship/name "Roci" :ship/price 1000}]}
               {:human/starships [{:ship/name "Anubis" :ship/price 5000}
                                  {:ship/name "Roci" :ship/price 1000}]}}
             (set (pull-all db '[{:human/starships [:ship/name :ship/price]}] read)))))

    (testing "clauses can be put on derived attributes as well"
      (is (= #{{:ship/name "Roci" :ship/price 1000}
               {:ship/name "Anubis" :ship/price 5000}}
             (set (pull-all db '[:ship/name
                                 [:ship/price _]] read))))
      (is (= #{{:ship/name "Roci" :ship/price 1000}}
             (set (pull-all db '[:ship/name
                                 [:ship/price 1000]] read)))))))
