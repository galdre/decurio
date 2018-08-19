(ns decurio.protocols)

(defprotocol Task
  (run-task [task parent-machine executor] "Runs the task."))

(defprotocol Transitioner
  (transition [transition machine]))

(defprotocol Machine
  (force-state [machine state])
  (step [machine])
  (discharge [machine]
    [machine error]
    [machine error submachine]))
