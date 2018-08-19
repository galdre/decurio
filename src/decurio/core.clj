(ns decurio.core
  (:require [decurio.protocols :as p])
  (:import [clojure.lang IFn Keyword]
           [decurio.protocols Machine]))

(defn- transitive-discharge
  [[machine & machines]]
  (when-let [f (p/discharge machine)]
    (if (seq machines)nnn
      (recur machines)
      f)))

(extend-protocol p/Task
  IFn
  (run-task [f ancestors executor]
    (try (f parent-machine)
         (when-let [f (transitive-discharge ancestors)]
           (.submit executor (f executor)))))
  Machine
  (run-task [machine ancestors executor]
    (try (let [tasks (->> (p/step machine executor)
                          (map #(fn [] (run-task % (cons machine ancestors) executor))))]
           (if (seq tasks)
             (.submitAll executor tasks)
             (when-let [f (transitive-discharge (cons machine ancestors))]
               (.submit executor (f executor))))))))

(extend-protocol p/Transitioner
  Keyword
  (transition [kw machine] (p/force-state machine kw))
  IFn
  (transition [f machine] (->> (f) (p/force-state machine))))

(defrecord PrototypeState [coins ingress])
(defrecord PrototypeMachine
    [fields state states next-transition waiting]
  p/Machine
  (force-state [_ s] (reset! state s))
  (step [this executor]
    (locking this
      (let [{:keys [tasks transition]} (get states @state)]
        (reset! next-transition transition)
        (reset! waiting (inc (count tasks)))
        tasks
        (when tasks
          (doseq [task tasks]
            (.submit executor
                     (fn [] (run-task task this executor))))))))
  (discharge [this]
    (when (zero? @waiting)
      (transition @next-transition this)
      (fn [executor]
        (fn [] (run-task this nil executor)))))
  (discharge [this error])
  (discharge [this error machine]))

(defmacro defmachine
  [type-name fields+defaults states]
  ;; TODO: validate type-name
  ;; TODO: validate fields+defaults
  ;; TODO: validate states
  (let [[fields defaults] (apply map vector (partition 2 fields+defaults))
        state (gensym 'state)
        states (gensym 'states)
        discharger (gensym 'discharger)
        state-keys (keys states)
        ;;states (parse-states states)
        ]
    `(do
       (deftype ~type-name
           ~(conj (vec fields) state states discharger)
         p/Machine
         (force-state [_ state#]
           (assert (contains? ~state-keys state#))
           (reset ~state state#))
         (step [this# executor#]
           (let [state# (->> @~state (get ~states))
                 tasks# (seq (:tasks state#))
                 discharger# ((:λ-discharger state#)
                              tasks#)]
             (reset! ~discharger discharger#)
             (when tasks#
               (doseq [task# tasks#]
                 (.submit executor# task#)))
             (discharger#)))))))
x
