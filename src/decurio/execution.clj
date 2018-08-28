(ns decurio.execution
  (:require [decurio.task :as t]
            [decurio.protocols :as p])
  (:import [java.util.concurrent ExecutorService]))

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
          (println "Task is complete."))))

;; ExecutorService

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

(defn- run-task
  [task ancestors executor]
  (let [t (p/begin task)]
    (some-> (if (true? t)
              (discharge-transitively ancestors)
              {:tasks t, :ancestors (cons task ancestors)})
            (load-tasks executor))))

(defn execute
  [task executor]
  (let [assignment (t/task->assignment task)
        return (p/completion-promise assignment)]
    (load-tasks {:tasks [assignment]
                 :ancestors ()}
                executor)
    return))

;; Single thread

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
  (let [assignment (t/task->assignment task)
        t (p/begin assignment)]
    (some-> (if (true? t)
              (discharge-transitively ancestors)
              {:tasks t
               :ancestors (cons assignment ancestors)})
            (load-tasks*))))
