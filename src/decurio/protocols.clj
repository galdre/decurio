(ns decurio.protocols)

(defprotocol Task
  (begin [task] "Runs the task, returning true, or else returns subtasks to run.")
  (discharge [task] "Reports that a subtask has completed. Returns nil if there are still more to complete, a further sequence of subtasks that need to be executed, or true if the task is now complete."))

(defprotocol Transitioner
  (transition [transition machine]))

(defprotocol Machine
  (force-state [machine state])
  (fields [machine])
  (current-state [machine])
  (states [machine])
  (step [machine]))
