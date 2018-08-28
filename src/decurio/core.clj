(ns decurio.core
  (:require [decurio.protocols :as p]
            [decurio.task :as t])
  (:import [clojure.lang IFn Keyword]
           [decurio.protocols Machine]
           [java.util.concurrent Executors ExecutorService]))

(extend-protocol p/Transitioner
  Keyword
  (transition [kw machine] (p/force-state machine kw))
  IFn
  (transition [f machine] (p/force-state machine (f machine))))

(defn machine
  [fields states initial-state]
  (let [v-current-state (volatile! initial-state)
        v-transitioner (volatile! nil)
        v-i-remaining (volatile! -1)]
    (reify p/Machine
      (force-state [_ s] (vreset! v-current-state s))
      (fields [_] fields)
      (states [_] states)
      (current-state [_] @v-current-state)
      (step [this]
        (locking this
          (let [{:keys [tasks transition]} (get states @v-current-state)]
            (if (seq tasks)
              (do (vreset! v-transitioner transition)
                  (vreset! v-i-remaining (count tasks))
                  tasks)
              (do (p/transition transition this)
                  true)))))
      p/Task
      (begin [this]
        (if (empty? (get states @v-current-state))
          (println "Machine is done.")
          (p/step this)))
      (discharge [this]
        (locking this
          #_(println "Before vswapping, it's " @v-i-remaining)
          (when (zero? (vswap! v-i-remaining dec))
            (p/transition @v-transitioner this)
            true))))))

(defrecord Tier2State [moved])
(defn move [state] (update state :moved #(vswap! % %2) inc))
(defrecord Tier1State [shared tier-2s moved])
(defn share [state] (update state :shared #(vswap! % %2) inc))
(let [v (volatile! (cycle [:moving :moving :moving :share]))]
  (defn moving-transition
    [_]
    (if (< (rand) 0.05)
      :finished
      (first (vswap! v next)))))

(defn make-tier-2
  []
  (let [the-state (Tier2State. (volatile! 0))]
    (machine the-state
             {:moving {:tasks [(partial move the-state)]
                       :transition :moving}}
             :moving)))
(defn make-tier-1
  []
  (let [tier-2s (repeatedly 20 make-tier-2)
        the-state (Tier1State. (volatile! 0) tier-2s (volatile! 0))]
    (machine the-state
             {:moving {:tasks (cons (partial move the-state) tier-2s)
                       :transition moving-transition}
              :share {:tasks [(partial share the-state)]
                      :transition :moving}
              :finished {}}
             :moving)))

(defn alternate-tier-1
  []
  (let [tier-2s (repeatedly 20 make-tier-2)
        the-state (Tier1State. (volatile! 0) tier-2s (volatile! 0))]
    (machine the-state
             {:moving {:tasks (cons (partial move the-state) tier-2s)
                       :transition (fn [_] (if (< (rand) 0.05) :finished :share))}
              :share {:tasks [(t/repeating-task (partial share the-state) 3)]
                      :transition :moving}
              :finished {}}
             :moving)))

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
