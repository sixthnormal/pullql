(ns pullql.walk-test
  (:require
   [clojure.test :refer [deftest is testing run-tests]]
   [pullql.walk :refer [fold dependencies]]
   [pullql.core :refer [parse]]))

(deftest test-walk
  (let [pattern (parse '[:db/id
                         :parent/name
                         {:parent/child [:child/age
                                         [:child/relevant? true]]}])]
    (is (= #{:db/id :parent/name :parent/child :child/age :child/relevant?}
           (dependencies pattern)))))
