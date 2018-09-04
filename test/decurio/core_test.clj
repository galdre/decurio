(ns decurio.core-test
  (:require [clojure.test :refer :all]
            [decurio.core :as c]
            [decurio.protocols :as p]
            [decurio.task :as t]
            [decurio.execution :as ex])
  (:import [java.util.concurrent Executors]))

;; Very simple tests first

(defn count-transitions
  [state]
  (fn [machine]
    (update (p/fields machine) :transitions swap! inc)
    state))

(defn circular-machine-tier-3
  []
  (let [state {:transitions (atom 0)}]
    (c/machine state
               {:one {:transition (count-transitions :two)}
                :two {:transition (count-transitions :three)}
                :three {:transition (count-transitions :one)}}
               :one)))

(defn circular-machine-tier-2
  []
  (let [tier-3 (circular-machine-tier-3)
        state {:tier-3 tier-3
               :transitions (atom 0)}]
    (c/machine state
               {:one {:tasks [(t/staged-task (repeat 3 [tier-3]))]
                      :transition (count-transitions :one)}}
               :one)))

(defn circular-machine-tier-1
  []
  (let [tier-2 (circular-machine-tier-2)
        state {:tier-2 tier-2
               :transitions (atom 0)}]
    (c/machine state
               {:one {:tasks [(t/repeating-task tier-2 3)]
                      :transition (count-transitions :two)}
                :two {:tasks [(t/repeating-task tier-2 3)]
                      :transition (count-transitions :three)}
                :three {:tasks [(t/repeating-task tier-2 3)]
                        :transition (count-transitions :four)}
                :four {:tasks [(t/repeating-task tier-2 3)]
                       :transition (count-transitions :five)}}
               :one)))

(deftest basic-circular-machines-test
  (let [cmt1 (circular-machine-tier-1)]
    (ex/run-task* (t/step-to cmt1 :five) ())
    (let [tier-1-fields (p/fields cmt1)
          _ (is (= 4 @(:transitions tier-1-fields)))
          tier-2-fields (p/fields (:tier-2 tier-1-fields))
          _ (is (= 12 @(:transitions tier-2-fields)))
          tier-3-fields (p/fields (:tier-3 tier-2-fields))
          _ (is (= 36 @(:transitions tier-3-fields)))]
      (is true))))

(deftest concurrent-circular-machines-test
  (let [cmt1 (circular-machine-tier-1)
        es (Executors/newCachedThreadPool)]
    @(ex/execute (t/step-to cmt1 :five) es)
    (let [tier-1-fields (p/fields cmt1)
          _ (is (= 4 @(:transitions tier-1-fields)))
          tier-2-fields (p/fields (:tier-2 tier-1-fields))
          _ (is (= 12 @(:transitions tier-2-fields)))
          tier-3-fields (p/fields (:tier-3 tier-2-fields))
          _ (is (= 36 @(:transitions tier-3-fields)))]
      (is true))))

;; A little more complicated:

(defn branching-machine-tier-3
  []
  (let [state {:transitions (atom 0)}]
    (c/machine state
               {:one {:transition (count-transitions :one)}}
               :one)))

(defn branching-machine-tier-2
  []
  (let [tier-3s (repeatedly 10 branching-machine-tier-3)
        state {:tier-3s tier-3s
               :transitions (atom 0)}]
    (c/machine state
               {:one {:tasks (map #(t/staged-task (repeat 5 [% % %]))
                                  tier-3s)
                      :transition (count-transitions :one)}}
               :one)))

(defn branching-machine-tier-1
  []
  (let [first-tier-2s (repeatedly 10 branching-machine-tier-2)
        second-tier-2s (repeatedly 10 branching-machine-tier-2)
        state {:first-tier-2s first-tier-2s
               :second-tier-2s second-tier-2s
               :transitions (atom 0)}]
    (c/machine state
               {:one {:tasks (map #(t/repeating-task % 4) first-tier-2s)
                      :transition (count-transitions :two)}
                :two {:tasks (map #(t/repeating-task % 4) second-tier-2s)
                      :transition (count-transitions :finished)}}
               :one)))

(deftest basic-branching-machines-test
  (let [bmt1 (branching-machine-tier-1)]
    (ex/run-task* (t/step-to bmt1 :finished) ())
    (let [tier-1-fields (p/fields bmt1)
          _ (is (= 2 @(:transitions tier-1-fields)))
          tier-2s-fields (map p/fields
                              (concat (:first-tier-2s tier-1-fields)
                                      (:second-tier-2s tier-1-fields)))
          _ (is (= 20 (count tier-2s-fields)))
          _ (is (every? #(= 4 @(:transitions %)) tier-2s-fields))
          tier-3s-fields (sequence (comp (mapcat :tier-3s) (map p/fields))
                                   tier-2s-fields)
          _ (is (= 200 (count tier-3s-fields)))
          _ (is (every? #(= 60 @(:transitions %)) tier-3s-fields))]
      (is true))))

(deftest concurrent-branching-machines-test
  (let [bmt1 (branching-machine-tier-1)
        es (Executors/newCachedThreadPool)]
    @(ex/execute (t/step-to bmt1 :finished) es)
    (let [tier-1-fields (p/fields bmt1)
          _ (is (= 2 @(:transitions tier-1-fields)))
          tier-2s-fields (map p/fields
                              (concat (:first-tier-2s tier-1-fields)
                                      (:second-tier-2s tier-1-fields)))
          _ (is (= 20 (count tier-2s-fields)))
          _ (is (every? #(= 4 @(:transitions %)) tier-2s-fields))
          tier-3s-fields (sequence (comp (mapcat :tier-3s) (map p/fields))
                                   tier-2s-fields)
          _ (is (= 200 (count tier-3s-fields)))
          _ (is (every? #(= 60 @(:transitions %)) tier-3s-fields))]
      (is true))))

(defn error-producing-machine
  []
  (c/machine {}
             {:one {:tasks [#(inc %)], :transition :finished}}
             :one))

(defn machine-wrapping-error-producing-machine
  []
  (let [problem-child (error-producing-machine)
        state {:problem-child problem-child}]
    (c/machine state
               {:one {:tasks [problem-child], :transition :one}}
               :one)))

(defn machine-wrapping-error-producing-machine-squared
  []
  (let [bad-manager (machine-wrapping-error-producing-machine)
        state {:bad-manager bad-manager}]
    (c/machine state
               {:one {:tasks [bad-manager], :transition :one}}
               :one)))

#_(deftest basic-error-propagation-test
  (let [cmt1 (machine-wrapping-error-producing-machine-squared)]
    (let ) (ex/exec (t/step-to cmt1 :five) ())
    (let [tier-1-fields (p/fields cmt1)
          _ (is (= 4 @(:transitions tier-1-fields)))
          tier-2-fields (p/fields (:tier-2 tier-1-fields))
          _ (is (= 12 @(:transitions tier-2-fields)))
          tier-3-fields (p/fields (:tier-3 tier-2-fields))
          _ (is (= 36 @(:transitions tier-3-fields)))]
      (is true))))

(deftest concurrent-error-propagation-test
  (let [cmt1 (machine-wrapping-error-producing-machine-squared)
        es (Executors/newCachedThreadPool)
        result @(ex/execute (t/step-to cmt1 :five) es)]
    (try
      (println ["foo" result])
      (is (instance? Throwable result))
      (finally (.shutdownNow es)))))
