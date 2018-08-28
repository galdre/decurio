(ns decurio.task
  (:require [decurio.protocols :as p])
  (:import [java.util.concurrent ExecutorService]
           [clojure.lang IFn]))

(extend-protocol p/Task
  ;; raw/simple types:
  IFn ;; raw fns are tasks machines might load to operate on their fields
  (begin [task]
    (try (task)
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
        (locking this
          (vreset! v-stages-remaining stages)
          (if-let [tasks (-> v-stages-remaining deref first seq)]
            (do (vswap! v-stages-remaining next)
                (vreset! v-i-remaining (count tasks))
                tasks)
            true)))
      (discharge [this]
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
        (locking this
          (vreset! v-times-remaining times)
          [task]))
      (discharge [this]
        (locking this
          (or (zero? (vswap! v-times-remaining dec))
              [task]))))))

(defn step-to
  [machine state]
  (reify p/Task
    (begin [this] (list machine))
    (discharge [this]
      (or (->> machine p/current-state (identical? state))
          (list machine)))))

(defn- complete [assignment completed? promise discharge]
  (when (true? discharge)
    (vreset! completed? true)
    (deliver promise assignment))
  discharge)

(defn task->assignment
  [task]
  (let [begun? (volatile! false)
        completed? (volatile! false)
        completion (promise)]
    (reify p/Task
      (begin [this]
        (locking this
          (if (not @begun?)
            (do (vreset! begun? true)
                (p/begin task))
            (println "already begun"))))
      (discharge [this]
        (if (and @begun? (not @completed?))
          (complete this completed? completion (p/discharge task))
          (println "can't discharge!")))
      p/Assignment
      (begun? [_] @begun?)
      (completed? [_] @completed?)
      (completion-promise [_] completion))))
