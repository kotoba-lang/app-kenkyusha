(ns lg-kenkyusha.smoke-test
  "Smoke tests for the lg-kenkyusha clj port — clojure.test analogue of the
  Python `lg/tests/test_smoke.py`, plus graph/node-behaviour tests the original
  could not run offline (the LLM + sources + DB are injectable seams here, so the
  6-role super-step pipeline verifies end-to-end under bb with stubs)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            #?(:clj [cheshire.core :as json])
            [langgraph.graph :as g]
            [lg-kenkyusha.kotoba-datomic :as kd]
            [lg-kenkyusha.server :as server]
            [lg-kenkyusha.store :as store]
            [lg-kenkyusha.graphs.research-loop :as rl]))

(def expected-graphs #{"kenkyusha_research_loop"})

;; ── server registry parity (mirrors test_smoke.py) ──────────────────────────

(deftest server-module-imports
  (is (map? server/GRAPHS)))

(deftest graphs-match-expected-set
  (is (= expected-graphs (set (keys server/GRAPHS)))))

(deftest all-graphs-invocable
  (doseq [[nm graph] server/GRAPHS]
    (is (some? graph) (str "GRAPHS[" nm "] nil"))))

#?(:clj
   (deftest langgraph-json-graphs-match-server
     ;; parity with test_langgraph_json_graphs_match_server (cwd = lg-clj/)
     (let [cfg (json/parse-string (slurp "../lg/langgraph.json") true)]
       (is (= (set (map name (keys (:graphs cfg)))) (set (keys server/GRAPHS)))))))

#?(:clj
   (deftest langgraph-json-has-cron-for-research-loop
     (let [cfg (json/parse-string (slurp "../lg/langgraph.json") true)
           cron-ids (set (map #(or (:graph_id %) (:graph %)) (:crons cfg)))]
       (is (contains? cron-ids "kenkyusha_research_loop")))))

;; ── dispatch surface (/health /ok /graphs /runs /xrpc) ──────────────────────

(defn- req [m p & {:keys [headers query body]}]
  {:method m :path p :headers (or headers {}) :query (or query {}) :body (or body {})})

(def ^:private ctx {:store (store/->fake-frontier-store)})

(deftest health-endpoint
  (let [r (server/handle-request ctx (req :get "/health"))]
    (is (= 200 (:status r)))
    (is (true? (get-in r [:body :ok])))
    (is (= "lg-kenkyusha" (get-in r [:body :app])))
    (is (= expected-graphs (set (get-in r [:body :graphs]))))))

(deftest ok-endpoint-lists-graphs
  (let [r (server/handle-request ctx (req :get "/ok"))]
    (is (= 200 (:status r)))
    (is (= expected-graphs (set (get-in r [:body :graphs]))))))

(deftest graphs-endpoint
  (is (= expected-graphs (set (get-in (server/handle-request ctx (req :get "/graphs"))
                                      [:body :graphs])))))

(deftest unknown-graph-404
  (is (= 404 (:status (server/handle-request
                       ctx (req :post "/runs" :body {:assistant_id "nope" :input {}}))))))

(deftest unknown-nsid-xrpc-404
  (is (= 404 (:status (server/handle-request
                       ctx (req :post "/xrpc/com.etzhayyim.apps.kenkyusha.unknownMethod"))))))

(deftest unknown-route-404
  (is (= 404 (:status (server/handle-request ctx (req :get "/nope"))))))

;; ── three-path auth (parity with _enforce_auth) ─────────────────────────────

(deftest auth-open-when-unset
  (testing "LG_KENKYUSHA_API_KEY unset → open"
    (with-redefs [server/enforce-auth (fn [& _] nil)]
      (is (= 200 (:status (server/handle-request ctx (req :post "/runs" :body {}))))))))

(deftest auth-cron-exempt
  (is (nil? (server/enforce-auth {:x-cron "1"}))))

(deftest auth-values-are-explicitly-bound
  (binding [server/*auth-config* {:internal-secret "internal" :api-key "api"}]
    (is (= 401 (:status (server/enforce-auth {}))))
    (is (nil? (server/enforce-auth {:internal-token "internal"})))
    (is (nil? (server/enforce-auth {:x-api-key "api"})))))

(deftest publish-requires-title
  (is (= 400 (:status (server/handle-request ctx (req :post "/frontiers/publish" :body {}))))))

;; ── frontier id (djb2) ───────────────────────────────────────────────────────

(deftest djb2-stable
  (is (= (rl/djb2 "abc") (rl/djb2 "abc")))
  (is (not= (rl/djb2 "abc") (rl/djb2 "abd")))
  (is (re-matches #"[0-9a-f]+" (rl/djb2 "0613|frontier:0613"))))

;; ── research-loop graph end-to-end (stub advisor + evidence) ────────────────

(deftest research-loop-fallback-offline
  ;; advisor errors → deterministic placeholder hypotheses → still a full envelope
  (binding [rl/*advisor* (fn [_ _] {:error "no murakumo offline"})]
    (let [out (g/invoke rl/GRAPH {:frontierTitle "Dark matter halos"
                                  :primaryDiscipline "0533" :maxHypotheses 3})]
      (is (= 3 (:hypothesis_count out)))
      (is (seq (:frontier_id out)))
      (is (str/starts-with? (:frontier_did out) "did:web:kenkyusha.etzhayyim.com:frontier:"))
      (is (= "none" (:consensus_level out)) "no evidence → no consensus")
      (is (= "iterate" (:next_action out))))))

(deftest research-loop-with-evidence-stubbed
  (binding [rl/*advisor* (fn [_role _prompt] ["H-alpha drives X" "H-beta drives Y"])
            rl/*collect-evidence*
            (fn [frontier hyps]
              (let [hid (:hypothesis_id (first hyps))]
                (into (vec (for [_ (range 6)]
                             {:hypothesis_id hid :frontier_id (:frontier_id frontier)
                              :evidence_type "supports" :source_type "bunken"}))
                      [{:hypothesis_id hid :evidence_type "contradicts" :source_type "hanrei"}])))]
    (let [out (g/invoke rl/GRAPH {:frontierTitle "Topic" :primaryDiscipline "0613" :maxHypotheses 2})
          winner (first (:hypotheses out))]
      (is (= 2 (count (:hypotheses out))))
      (is (= (:hypothesis_id winner) (:winner_hypothesis_id out)))
      (is (= 6 (:supporting_evidence winner)))
      (is (= 1 (:contradicting_evidence winner)))
      (is (#{"strong" "partial"} (:consensus_level out)) "high support ratio → strong/partial")
      (is (= "supported" (:status winner))))))

(deftest cron-path-no-title
  ;; cron input has no frontierTitle — detect_frontier synthesizes one
  (binding [rl/*advisor* (fn [_ _] {:error "offline"})]
    (let [out (g/invoke rl/GRAPH {:primaryDiscipline "0613" :maxHypotheses 4})]
      (is (= "frontier:0613" (:frontierTitle out)))
      (is (= 4 (:hypothesis_count out))))))

;; ── publish endpoint envelope (parity with /frontiers/publish) ──────────────

(deftest publish-endpoint-envelope
  (binding [rl/*advisor* (fn [_ _] {:error "offline"})]
    (let [r (server/handle-request ctx (req :post "/frontiers/publish"
                                            :body {:title "New frontier" :primaryDiscipline "0613"}))]
      (is (= 200 (:status r)))
      (is (true? (get-in r [:body :ok])))
      (is (seq (get-in r [:body :frontier_id])))
      (is (contains? (:body r) :winner_hypothesis_id))
      (is (= "none" (get-in r [:body :consensus_level]))))))

;; ── store seam: frontier-state + list clamp (parity with DB endpoints) ──────

(def ^:private seeded-store
  (store/->fake-frontier-store
   {:frontiers {"abc" {:frontier_id "abc" :title "F" :status "active"}
                "def" {:frontier_id "def" :title "G" :status "resolved"}}
    :hypotheses {"abc" [{:hypothesis_id "abc-h1" :elo_rating 1200}
                        {:hypothesis_id "abc-h2" :elo_rating 1400}]}
    :evidence {"abc" [{:evidence_id "e1"}]}}))

(deftest frontier-state-route
  (let [r (server/handle-request {:store seeded-store}
                                 (req :get "/frontiers/abc/state"))]
    (is (= 200 (:status r)))
    (is (= "F" (get-in r [:body :frontier :title])))
    (is (= "abc-h2" (get-in r [:body :top_hypothesis :hypothesis_id])) "top = highest elo")
    (is (= 1 (count (get-in r [:body :evidence]))))))

(deftest frontier-state-404
  (is (= 404 (:status (server/handle-request {:store seeded-store}
                                             (req :get "/frontiers/zzz/state"))))))

(deftest list-frontiers-clamp-and-filter
  (let [r (server/handle-request {:store seeded-store}
                                 (req :get "/frontiers" :query {:limit 9999 :status "active"}))]
    (is (= 500 (get-in r [:body :limit])) "limit clamped to 500")
    (is (= 1 (get-in r [:body :total])))
    (is (= "active" (get-in r [:body :frontiers 0 :status])))))

(deftest xrpc-get-frontier-requires-id
  (is (= 400 (:status (server/handle-request {:store seeded-store}
                                             (req :post server/nsid-get :body {}))))))

(deftest xrpc-get-frontier-routes
  (let [r (server/handle-request {:store seeded-store}
                                 (req :post server/nsid-get :body {:frontier_id "abc"}))]
    (is (= 200 (:status r)))
    (is (= "F" (get-in r [:body :frontier :title])))))

;; ── Murakumo guard (ADR-2605215000) ─────────────────────────────────────────

(deftest murakumo-guard
  (testing "off-fleet endpoint refused"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs :default)
                 (rl/assert-murakumo "https://api.openai.com/v1"))))
  (testing "malformed and lookalike endpoints refused"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs :default)
                 (rl/assert-murakumo "not-a-url")))
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs :default)
                 (rl/assert-murakumo "http://127.0.0.1.attacker.example:4000/v1"))))
  (testing "loopback gateway allowed"
    (is (nil? (rl/assert-murakumo "http://127.0.0.1:4000/v1")))))

(deftest absent-advisor-is-deterministic-and-offline
  (is (= {:error "advisor capability not configured"}
         (rl/advisor :generate "prompt"))))

(deftest raw-http-capability-is-required
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs :default)
                        #"explicit HTTP POST capability"
                        (rl/advisor-with nil rl/default-config :generate "prompt"))))

(deftest kotoba-http-capability-is-required
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs :default)
                        #"explicit Kotoba HTTP capability required"
                        (kd/q (kd/->client "graph") "[:find ?e]"))))

(deftest injected-kotoba-wire-contract
  #?(:clj
     (let [seen (atom nil)]
       (binding [kd/*config* {:xrpc-url "https://kotoba.test/"
                              :bearer "secret"
                              :graph "kenkyusha-test"}
                 kd/*post-json!* (fn [url opts]
                                   (reset! seen [url opts])
                                   {:status 200 :body "{\"rows\":[]}"})]
         (is (= [] (kd/q (kd/->client) "[:find ?e]")))
         (is (= "https://kotoba.test/xrpc/ai.etzhayyim.apps.kotoba.datomic.q"
                (first @seen)))
         (is (= "Bearer secret"
                (get-in @seen [1 :headers "Authorization"])))))))

(deftest injected-advisor-wire-contract
  #?(:clj
     (let [seen (atom nil)
           result (rl/advisor-with
                   (fn [url opts]
                     (reset! seen [url opts])
                     {:status 200
                      :body "{\"choices\":[{\"message\":{\"content\":\"proposal\"}}]}"})
                   rl/default-config :generate "prompt")]
       (is (= "proposal" result))
       (is (= "http://127.0.0.1:4000/v1/chat/completions" (first @seen)))
       (is (= 120000 (get-in @seen [1 :timeout])))
       (is (re-find #"max_tokens.*800" (get-in @seen [1 :body]))))))
