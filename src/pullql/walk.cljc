(ns pullql.walk)

(defmulti ^:private walk (fn [f state node] (first node)))

(defmethod walk :pattern [f state [_ specs]]
  (reduce (partial walk f) state specs))

(defmethod walk :attribute [f state [_ attr]]
  (f state [:attribute attr]))

(defmethod walk :expand [f state [_ map-spec]]
  (let [[attr pattern] (first map-spec)]
    (as-> state state
      (f state [:attribute attr])
      (walk f state [:pattern pattern]))))

(defmethod walk :clause [f state [_ clause]]
  (let [[_ data-pattern] clause
        [attr v]         data-pattern]
    (f state [:attribute attr])))

;; PUBLIC API

(defn fold [f state pattern]
  (walk f state [:pattern pattern]))

(defn dependencies
  "Returns a set of all aid's required to compute this pattern."
  [pattern]
  (fold (fn [aids [type data]]
          (if (= type :attribute)
            (conj aids data)
            aids))
        #{}
        pattern))
