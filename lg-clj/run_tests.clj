;; lg-kenkyusha clj/bb test runner (repo rule: run_tests.clj, NOT .sh).
;;
;;   bb run_tests.clj      (from 60-apps/etzhayyim-project-kenkyusha/lg-clj/)
;;   bb test               (bb.edn task alias)
;;
;; Exits non-zero if any test fails or errors.
(ns lg-kenkyusha.host
  (:require [babashka.http-client :as http]
            [clojure.test :as t]
            [lg-kenkyusha.graphs.research-loop :as research]
            [lg-kenkyusha.impact-observation-test]
            [lg-kenkyusha.event-observation-test]
            [lg-kenkyusha.coverage-observation-test]
            [lg-kenkyusha.smoke-test]))

(defn- env [name default] (or (System/getenv name) default))

(def config
  {:repo (env "KENKYUSHA_REPO_DID" (:repo research/default-config))
   :url (env "MURAKUMO_URL" (:url research/default-config))
   :model (env "MURAKUMO_MODEL" (:model research/default-config))
   :timeout-sec (Double/parseDouble (env "MURAKUMO_TIMEOUT_SEC" "120"))})

(defn advisor [role prompt]
  (research/advisor-with http/post config role prompt))

(defn with-capabilities [f]
  (binding [research/*config* config research/*advisor* advisor] (f)))

(let [{:keys [fail error]}
      (t/run-tests 'lg-kenkyusha.impact-observation-test
                   'lg-kenkyusha.coverage-observation-test
                   'lg-kenkyusha.smoke-test)]
  (System/exit (if (pos? (+ (or fail 0) (or error 0))) 1 0)))
