;; nbb test runner for the funding-observation contract (JVM-free gate).
;;   nbb --classpath "lg-clj/src:lg-clj/test" lg-clj/run_funding_tests.cljs
(ns lg-kenkyusha.host-funding
  (:require [clojure.test :as t]
            [lg-kenkyusha.funding-observation-test]))
(let [{:keys [test fail error]} (t/run-tests 'lg-kenkyusha.funding-observation-test)]
  (.exit js/process (if (pos? (+ (or fail 0) (or error 0))) 1 0)))
