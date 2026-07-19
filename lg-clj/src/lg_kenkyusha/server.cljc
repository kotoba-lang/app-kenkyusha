(ns lg-kenkyusha.server
  "lg-kenkyusha dispatch surface — clj/bb port of `lg/lg_kenkyusha/server.py`
  (ADR-2606280030 langgraph-python -> langgraph-clj).

  The Python file is a FastAPI app exposing (LangGraph-compatible):
    GET  /health /ok                       → liveness + graph ids
    GET  /graphs                           → list graph ids
    POST /runs                             → invoke a graph synchronously
    POST /frontiers/publish                → Science-OS Step 1 (publish + run loop)
    GET  /frontiers/{id}/state             → frontier + top hypothesis + evidence
    GET  /frontiers                        → list active frontiers (clamped)
    POST /xrpc/com.etzhayyim.apps.kenkyusha.{publishFrontier,getFrontier,listFrontiers}

  `handle-request` is a pure ring-ish dispatcher (method/path/headers/query/body
  -> {:status :body}) so the routing + three-path auth are deterministically
  testable. Binding it to a concrete socket (org.httpkit.server) is the one
  remaining infra leg, deferred so the live FastAPI pod is never disturbed.

  NOTE (coexist — CRITICAL): the DEPLOYED runtime is still the FastAPI pod
  (lg/lg_kenkyusha/server.py via langgraph.json/Dockerfile/Helm). This clj
  dispatcher is the verified, additive twin and COEXISTS until a human cuts over."
  (:require [clojure.string :as str]
            [langgraph.graph :as g]
            [lg-kenkyusha.store :as store]
            [lg-kenkyusha.graphs.research-loop :as research-loop]))

(def ^:dynamic *auth-config*
  {:internal-secret "" :api-key ""})

(defn- now-ms [] #?(:clj (System/currentTimeMillis) :cljs (.now js/Date)))

(def GRAPHS
  {"kenkyusha_research_loop" research-loop/GRAPH})

;; ── auth: three accepted paths (mirrors server.py `_enforce_auth`) ────────────
;;   1. exempt (x-cron=1)                          — cron sidechannel
;;   2. X-Kotodama-Internal-Token == DISPATCHER_INTERNAL_SECRET — MCP-adapter proxy
;;   3. x-api-key == LG_KENKYUSHA_API_KEY           — yoro / dispatcher
;; Returns nil when authorized, or a {:status 401 ..} response map otherwise.

(defn enforce-auth
  [{:keys [x-api-key x-cron internal-token]}]
  (let [expected-internal (:internal-secret *auth-config*)
        expected          (:api-key *auth-config*)]
    (cond
      (= "1" x-cron) nil
      (and internal-token (seq expected-internal) (= internal-token expected-internal)) nil
      (= "" expected) nil
      (and x-api-key (= x-api-key expected)) nil
      :else {:status 401 :body {:detail "x-api-key mismatch"}})))

(defn- auth-of [headers]
  {:x-api-key      (get headers "x-api-key")
   :x-cron         (get headers "x-cron")
   :internal-token (get headers "x-kotodama-internal-token")})

(defn- err->status [e]
  (let [m (str #?(:clj (.getMessage e) :cljs (or (.-message e) e)))]
    {:status 500 :body {:detail (subs m 0 (min 300 (count m)))}}))

(defn- clamp [v lo hi] (max lo (min (int v) hi)))

;; ── handlers ──────────────────────────────────────────────────────────────────

(defn health []
  {:status 200 :body {:ok true :app "lg-kenkyusha" :ts (now-ms)
                      :graphs (vec (keys GRAPHS))}})

(defn list-graphs []
  {:status 200 :body {:graphs (vec (keys GRAPHS))}})

(defn run-graph
  "POST /runs — invoke a registered graph synchronously."
  [body]
  (let [graph-id (or (:graph body) (:assistant_id body) "kenkyusha_research_loop")
        graph    (get GRAPHS graph-id)]
    (if (nil? graph)
      {:status 404 :body {:detail (str "unknown graph: " graph-id)}}
      (let [t0 (now-ms)]
        (try
          (let [result (g/invoke graph (or (:input body) {}))]
            {:status 200 :body {:ok true :graph graph-id
                                :duration_ms (- (now-ms) t0)
                                :result result}})
          (catch #?(:clj Exception :cljs :default) e (err->status e)))))))

(defn publish-frontier
  "POST /frontiers/publish — Science-OS Step 1. Validate title, run the loop,
  return the result envelope."
  [body]
  (let [title (str/trim (or (:title body) ""))]
    (if (str/blank? title)
      {:status 400 :body {:detail "title required"}}
      (let [discipline (str (or (:primaryDiscipline body) "0613"))
            max-h      (clamp (or (:maxHypotheses body) 4) 2 8)
            t0         (now-ms)]
        (try
          (let [result (g/invoke (get GRAPHS "kenkyusha_research_loop")
                                 {:frontierTitle title
                                  :primaryDiscipline discipline
                                  :maxHypotheses max-h})]
            {:status 200
             :body {:ok true
                    :duration_ms (- (now-ms) t0)
                    :frontier_id (:frontier_id result "")
                    :frontier_did (:frontier_did result "")
                    :winner_hypothesis_id (:winner_hypothesis_id result "")
                    :consensus_level (:consensus_level result "none")
                    :next_action (:next_action result "iterate")
                    :result result}})
          (catch #?(:clj Exception :cljs :default) e (err->status e)))))))

(defn frontier-state
  "GET /frontiers/{id}/state — read via the injectable store seam."
  [st frontier-id]
  (try
    (if-let [res (store/frontier-state st frontier-id)]
      {:status 200 :body res}
      {:status 404 :body {:detail "frontier not found"}})
    (catch #?(:clj Exception :cljs :default) e (err->status e))))

(defn list-frontiers
  "GET /frontiers — list via the store seam, limit clamped to [1,500]."
  [st {:keys [limit status]}]
  (try
    (let [lim  (clamp (or limit 50) 1 500)
          rows (store/list-frontiers* st {:limit lim :status status})]
      {:status 200 :body {:frontiers (vec rows) :limit lim :offset 0 :total (count rows)}})
    (catch #?(:clj Exception :cljs :default) e (err->status e))))

;; ── NSID (XRPC) aliases — Phase 2A MCP facade (parity with server.py) ─────────

(def nsid-publish "/xrpc/com.etzhayyim.apps.kenkyusha.publishFrontier")
(def nsid-get     "/xrpc/com.etzhayyim.apps.kenkyusha.getFrontier")
(def nsid-list    "/xrpc/com.etzhayyim.apps.kenkyusha.listFrontiers")

;; ── pure dispatcher ────────────────────────────────────────────────────────────

(def ^:private state-path-re #"^/frontiers/([^/]+)/state$")

(defn handle-request
  "Pure dispatcher. ctx = {:store <FrontierStore>}. req = {:method :path :headers
  :query :body}. -> {:status :body}."
  [{:keys [store]} {:keys [method path headers query body]}]
  (let [method (keyword (str/lower-case (name method)))
        headers (or headers {})
        auth   (auth-of headers)]
    (cond
      (and (= :get method) (#{"/health" "/ok"} path)) (health)
      (and (= :get method) (= path "/graphs"))        (list-graphs)

      (and (= :post method) (= path "/runs"))
      (or (enforce-auth auth) (run-graph (or body {})))

      (and (= :post method) (= path "/frontiers/publish"))
      (or (enforce-auth (dissoc auth :x-cron :internal-token)) (publish-frontier (or body {})))

      (and (= :get method) (re-find state-path-re (str path)))
      (or (enforce-auth (dissoc auth :x-cron)) (frontier-state store (second (re-find state-path-re path))))

      (and (= :get method) (= path "/frontiers"))
      (or (enforce-auth (dissoc auth :x-cron))
          (list-frontiers store {:limit (some-> (:limit query) int) :status (:status query)}))

      ;; NSID XRPC aliases (POST bodies) ───────────────────────────────────────
      (and (= :post method) (= path nsid-publish))
      (or (enforce-auth (dissoc auth :x-cron)) (publish-frontier (or body {})))

      (and (= :post method) (= path nsid-get))
      (or (enforce-auth (dissoc auth :x-cron))
          (let [fid (str/trim (or (:frontier_id body) ""))]
            (if (str/blank? fid)
              {:status 400 :body {:detail "frontier_id required"}}
              (frontier-state store fid))))

      (and (= :post method) (= path nsid-list))
      (or (enforce-auth (dissoc auth :x-cron))
          (list-frontiers store {:limit (some-> (:limit body) int)
                                 :status (when (string? (:status body)) (:status body))}))

      :else {:status 404 :body {:detail "not found"}})))

;; ── optional concrete httpkit listener (parity infra leg; NOT auto-started) ──
;;
;; Deferred: the deployed runtime is the FastAPI pod. A bb-native socket would be
;;   (org.httpkit.server/run-server (fn [ring-req] ...adapt to handle-request...))
;; but is intentionally NOT wired so this twin never binds a port alongside prod.
