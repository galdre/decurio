(ns decurio.core-test
  (:require [clojure.test :refer :all]
            [decurio.core :refer :all]))

(defmachine Particle
  [position (init-position)
   evaluate default-fitness
   swarm-best (init-best position)
   local-best (init-best position)
   momentum (init-momentum position)]
  {:step {:tasks [(Λ-step evaluate)]
          :transition :step}})

(defmachine Swarm
  [size 10
   best (init-best)
   hive-best (init-best)
   particles (repeatedly size #(particle :best best))]
  {:step {:tasks particles
          :transition (serial (times 5 :step)
                              :evaluate)}
   :evaluate {:transition evaluate-swarm}
   :finished {:transition (finished :finished)}})

(defmachine Hive
  [size 5
   hive-best (init-best)
   swarms (repeatedly size #(swarm :best hive-best))]
  {:step {:tasks swarms
          :transition (serial (times 5 :step)
                              :evaluate)}
   :evaluate {:transition evaluate-hive}
   :share {:tasks (Λ-share-among-swarms swarms)
           :transition :reset-swarms}
   :reset-swarms {:tasks (reset-all swarms :step)
                  :transition :step}
   :finished {:transition (finished :finished)}})

(defn unlock
  [state]
  (update state :coins inc))

(defn push
  [state]
  (update state :ingress inc))

(defmachine Prototype
  [coins 0
   ingress 0]
  {:locked {:tasks [unlock]
            :transition :closed}
   :closed {:tasks [push]
            :transition :open}
   :open {:transition :locked}})
