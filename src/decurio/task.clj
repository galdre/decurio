(ns decurio.task
  (:require [decurio.protocols :as p]
            [tesserae.core :as t])
  (:import [java.util.concurrent ExecutorService]
           [clojure.lang IFn]))

(defn staged-task
  ([stages] (staged-task stages nil))
  ([stages error-fn]
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
               true))))
       (handle-error [this error]
         (if error-fn
           (error-fn error)
           (throw error)))))))

(defn repeating-task
  ([task times] (repeating-task task times nil))
  ([task times error-fn]
   (let [v-times-remaining (volatile! nil)]
     (reify p/Task
       (begin [this]
         (locking this
           (vreset! v-times-remaining times)
           [task]))
       (discharge [this]
         (locking this
           (or (zero? (vswap! v-times-remaining dec))
               [task])))
       (handle-error [this error]
         (if error-fn
           (error-fn error)
           (throw error)))))))

(defn step-to
  ([machine state] (step-to machine state nil))
  ([machine state error-fn]
   (reify p/Task
     (begin [this] (list machine))
     (discharge [this]
       (or (->> machine p/current-state (identical? state))
           (list machine)))
     (handle-error [this error]
       (if error-fn
         (error-fn error)
         (throw error))))))

(defn- complete [completion-fn assignment completed? promise discharge]
  (when (true? discharge)
    (vreset! completed? true)
    (completion-fn promise assignment))
  discharge)

(def ^:private attain (partial complete deliver))
(def ^:private fail (partial complete t/fumble))

(defn task->assignment
  [task]
  (if (satisfies? p/Assignment task)
    task
    (let [begun? (volatile! false)
          completed? (volatile! false)
          completion (t/promise)]
      (reify p/Task
        (begin [this]
          (locking this
            (if (not @begun?)
              (do (vreset! begun? true)
                  (p/begin task))
              (println "already begun"))))
        (discharge [this]
          (if (and @begun? (not @completed?))
            (attain task completed? completion (p/discharge task))
            (println "can't discharge!")))
        (handle-error [this error]
          (try (p/handle-error task error)
               (catch Throwable t
                 (fail t completed? completion true))))
        p/Assignment
        (begun? [_] @begun?)
        (completed? [_] @completed?)
        (completion-promise [_] completion)))))
