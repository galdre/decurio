(ns decurio.core
  (:require [decurio.protocols :as p]
            [decurio.task :as t])
  (:import [decurio.protocols Machine]))

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
            true)))
      (handle-error [this error]
        ;; TODO
        (throw error)))))

