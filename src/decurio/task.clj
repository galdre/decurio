(ns decurio.task
  (:require [decurio.protocols :as p])
  (:import [java.util.concurrent ExecutorService]
           [clojure.lang IFn]))

(defn- discharge-transitively
  "Returns a sequence of tasks to execute."
  [[task & tasks]]
  #_(println "discharging the stack")
  (when-let [discharge-val (p/discharge task)]
    (cond (sequential? discharge-val)
          {:tasks discharge-val
           :ancestors (cons task tasks)}
          (seq tasks)
          (do #_(println "recurring")
            (recur tasks))
          (true? discharge-val)
          {:tasks (list task)
           :ancestors ()}))) ;; tier-1 machine

(declare run-task)
(defn- task->runnable
  [task ancestors executor]
  (fn []
    (run-task task ancestors executor)))

(defn- load-tasks
  [{:keys [tasks ancestors]} ^ExecutorService executor]
  #_(println "loading task")
  (when tasks
    (doseq [task tasks]
      (->> (task->runnable task ancestors executor)
           (.submit executor)))))

(defn run-task
  [task ancestors executor]
  (let [t (p/begin task)]
    (load-tasks (if (true? t)
                  (discharge-transitively ancestors)
                  {:tasks t
                   :ancestors (cons task ancestors)})
                executor)))

(declare run-task*)
(defn task->runnable*
  [task ancestors]
  (fn []
    (run-task* task ancestors)))

(defn load-tasks*
  [{:keys [tasks ancestors]}]
  (when tasks
    (doseq [task tasks]
      ((task->runnable* task ancestors)))))

(defn run-task*
  [task ancestors]
  (let [t (p/begin task)]
    (load-tasks* (if (true? t)
                   (discharge-transitively ancestors)
                   {:tasks t
                    :ancestors (cons task ancestors)}))))

(extend-protocol p/Task
  ;; raw/simple types:
  IFn ;; raw fns are tasks machines might load to operate on their fields
  (begin [task]
    #_(println "beginning IFn task")
    (try
      (task)
      (catch Throwable t (println t)))
    true)
  (discharge [f] (throw (ex-info "This is wrong."
                                 {:task-type 'IFn
                                  :task f}))))

(defn staged-task
  [stages]
  (let [v-stages-remaining (volatile! nil)
        v-i-remaining (volatile! 0)
        v-ancestors (volatile! nil)]
    (reify p/Task
      (begin [this]
        #_(println "beginning staged-task")
        (locking this
          (vreset! v-stages-remaining stages)
          (if-let [tasks (-> v-stages-remaining deref first seq)]
            (do (vswap! v-stages-remaining next)
                (vreset! v-i-remaining (count tasks))
                tasks)
            true)))
      (discharge [this]
        #_(println "discharging the staged task")
        (locking this
          (when (zero? (vswap! v-i-remaining dec))
            (if-let [tasks (-> v-stages-remaining deref first seq)]
              (do (vswap! v-stages-remaining next)
                  (vreset! v-i-remaining (count tasks))
                  tasks)
              true)))))))

(defn repeating-task
  [task times]
  (let [v-times-remaining (volatile! nil)]
    (reify p/Task
      (begin [this]
        #_(println "beginning the repeating task")
        (locking this
          (vreset! v-times-remaining times)
          [task]))
      (discharge [this]
        #_(println "discharging the repeating task")
        (locking this
          (or (zero? (vswap! v-times-remaining dec))
              (do #_(println "I guess it wasn't zero") false)
              [task]))))))

(defn step-to ;; TODO: as tier-1 just keeps going
  [machine state]
  (reify p/Task
    (begin [this] (list machine))
    (discharge [this]
      (or (->> machine p/current-state (identical? state))
          (list machine)))))
