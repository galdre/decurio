(ns decurio.core
  (:require [decurio.protocols :as p])
  (:import [clojure.lang IFn Keyword]
           [decurio.protocols Machine]
           [java.util.concurrent Executors ExecutorService]))

(defn- discharge-transitively
  [[machine & machines]]
  (when-let [ready-machine (p/discharge machine)]
    (if (seq machines)
      (recur machines)
      ready-machine))) ;; tier-1 machine

(defn- launch-tier-1
  [machine executor]
  (println "> launch-tier-1")
  (let [state @(:α-state machine)]
    (if-not (-> machine :states state :transition)
      (println "Machine is finished.")
      (->> (p/run-task machine nil executor)
           (fn [])
           (.submit executor)))))

(defn- task->runnable
  [task ancestors executor]
  (println "> task->runnable")
  (fn []
    (p/run-task task ancestors executor)))

(extend-protocol p/Task
  IFn
  (run-task [f ancestors executor]
    (println (str "> run-task:IFn - " (.getName (Thread/currentThread))))
    (swap! (:α-fields (first ancestors)) f)
    (when-let [tier-1-machine (discharge-transitively ancestors)]
      (launch-tier-1 tier-1-machine executor)))
  Machine
  (run-task [machine ancestors executor]
    (println (str "> run-task:Machine - " (.getName (Thread/currentThread))))
    (let [tasks (p/step machine)]
      (if (seq tasks)
        (doseq [task tasks]
          (.submit ^ExecutorService executor (task->runnable task
                                                             (cons machine ancestors)
                                                             executor)))
        (when-let [tier-1-machine (or (some-> ancestors seq (discharge-transitively))
                                      machine)]
          (launch-tier-1 tier-1-machine executor))))))

(extend-protocol p/Transitioner
  Keyword
  (transition [kw machine] (p/force-state machine kw))
  IFn
  (transition [f machine] (->> (f) (p/force-state machine))))

(defrecord PrototypeState [coins ingress])
(defrecord PrototypeMachine
    [α-fields α-state states α-transitioner αi-waiting]
  p/Machine
  (force-state [_ s] (reset! α-state s))
  (step [this]
    (println "> step")
    (locking this
      (let [{:keys [tasks transition]} (get states @α-state)]
        (if (seq tasks)
          (do (reset! α-transitioner transition)
              (reset! αi-waiting (count tasks))
              tasks)
          (do (p/transition transition this)
              nil)))))
  (discharge [this]
    (locking this
      (when (zero? (swap! αi-waiting dec))
        (p/transition @α-transitioner this)
        this)))
  (discharge [this error])
  (discharge [this error machine]))

(defn unlock
  [state]
  (update state :coins inc))

(defn push
  [state]
  (update state :ingress inc))

(def prototype-states
  {:locked {:tasks [unlock]
            :transition :closed}
   :closed {:tasks [push]
            :transition :open}
   :open {:transition #(if (< (rand) 0.05) :finished :locked)}
   :finished {}})

(def prototype-fields (PrototypeState. 0 0))

(defrecord Tier2State [moved])
(defn move [state] (update state :moved inc))
(defrecord Tier1State [shared tier-2s moved])
(defn share [state] (update state :shared inc))
(let [v (volatile! (cycle [:moving :moving :moving :share]))]
  (defn moving-transition
    []
    (if (< (rand) 0.05)
      :finished
      (first (vswap! v next)))))
(defn make-tier-2
  []
  (PrototypeMachine. (atom (Tier2State. 0))
                     (atom :moving)
                     {:moving {:tasks [move]
                               :transition :moving}}
                     (atom nil)
                     (atom 0)))
(defn make-tier-1
  []
  (let [tier-2s (repeatedly 20 make-tier-2)]
    (PrototypeMachine. (atom (Tier1State. 0 tier-2s 0))
                       (atom :moving)
                       {:moving {:tasks (cons move tier-2s)
                                 :transition moving-transition}
                        :share {:tasks [share]
                                :transition :moving}
                        :finished {}}
                       (atom nil)
                       (atom 0))))


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
