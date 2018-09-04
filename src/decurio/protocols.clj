(ns decurio.protocols
  (:import [clojure.lang IFn Keyword]))

(defprotocol Task
  (begin [task]
    "Runs the task, returning true, or else returns subtasks to
    run. Assignments can only be begun once.")
  (discharge [task]
    "Reports that a subtask has completed. Returns nil if there are
    still more to complete, a further sequence of subtasks that need
    to be executed, or true if the task is now complete.")
  (handle-error [task error]
    "If this task throws an error, this method is called to handle the
    error. This is also called to handle any errors re-thrown by the
    `handle-error` method of subtasks."))

(extend-protocol Task
  IFn
  (begin [task]
    (task)
    #_(try (task)
         (catch Throwable t (println t)))
    true)
  (discharge [f] (throw (ex-info "This is wrong."
                                 {:task-type 'IFn
                                  :task f})))
  (handle-error [_ t] (throw t)))

(defprotocol Assignment
  (begun? [assignment]
    "Returns true if the assignment has begun.")
  (completed? [assignment]
    "Returns true if the assignment has been completed.")
  (completion-promise [assignment]
    "Returns a promise that will block until assignment completion;
    dereferencing returns the assigned task."))

(defprotocol Machine
  (force-state [machine state])
  (fields [machine])
  (current-state [machine])
  (states [machine])
  (step [machine]))

(defprotocol Transitioner
  (transition [transition machine]))

(extend-protocol Transitioner
  Keyword
  (transition [kw machine] (force-state machine kw))
  IFn
  (transition [f machine] (force-state machine (f machine))))


