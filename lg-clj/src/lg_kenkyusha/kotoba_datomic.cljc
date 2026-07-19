(ns lg-kenkyusha.kotoba-datomic
  "kotoba datomic XRPC client — clj/bb port of the read surface lg_kenkyusha
  used via asyncpg/RisingWave (ADR-2606280030 langgraph-python -> langgraph-clj).

  SUBSTRATE DEVIATION (load-bearing): server.py reads frontier/hypothesis/evidence
  rows from RisingWave (`graphar.vertex_kenkyusha_*`) over asyncpg. The substrate
  boundary FORBIDS RisingWave for the clj actor twin, so this client targets the
  kotoba Datom log instead — `ai.etzhayyim.apps.kotoba.datomic.{q,pull}` — the same
  canonical read surface used by the lg-docs twin. httpx -> babashka.http-client.

  Read-only `q`/`pull` carry no server key by default (no-server-key, read-only,
  ADR-2606072802 / ADR-2605215000). Endpoint resolution honors
  `KOTOBA_XRPC_URL`/`KOTOBA_URL`; auth (when present) = Bearer JWT (`KOTOBA_BEARER`)."
  (:require [clojure.string :as str]
            #?(:clj [cheshire.core :as json])))

(def ^:dynamic *config*
  {:xrpc-url "http://kotoba.kotoba.svc.cluster.local:8080"
   :bearer ""
   :graph "kenkyusha-v1"})

(def ^:dynamic *post-json!*
  (fn [& _]
    (throw (ex-info "explicit Kotoba HTTP capability required"
                    {:capability :kotoba-http}))))

(defn- headers []
  (if (seq (:bearer *config*))
    {"Authorization" (str "Bearer " (:bearer *config*))}
    {}))

(defrecord KotobaDatomic [graph])

(defn ->client
  ([] (->KotobaDatomic (:graph *config*)))
  ([graph] (->KotobaDatomic graph)))

#?(:clj
   (defn- post-json [path body]
     (*post-json!*
      (str (str/replace (:xrpc-url *config*) #"/+$" "") path)
      {:headers (merge {"Content-Type" "application/json"} (headers))
       :body (json/generate-string body)
       :throw false})))

(defn q
  "Datalog query over the kotoba graph. Returns a vector of result rows."
  ([dm query-edn] (q dm query-edn nil))
  ([dm query-edn inputs-edn]
   #?(:clj
      (let [body (cond-> {:graph (:graph dm) :query_edn query-edn}
                   (seq inputs-edn) (assoc :inputs_edn inputs-edn))
            resp (post-json "/xrpc/ai.etzhayyim.apps.kotoba.datomic.q" body)]
        (when (>= (:status resp) 400)
          (throw (ex-info "kotoba q failed" {:status (:status resp) :body (:body resp)})))
        (or (get (json/parse-string (:body resp) true) :rows) []))
      :cljs (throw (ex-info "q not implemented for cljs" {})))))

(defn pull
  "Pull an entity's attribute map (or nil if absent)."
  [dm entity]
  #?(:clj
     (let [body {:graph (:graph dm) :entity entity}
           resp (post-json "/xrpc/ai.etzhayyim.apps.kotoba.datomic.pull" body)]
       (cond
         (= 404 (:status resp)) nil
         (>= (:status resp) 400) (throw (ex-info "kotoba pull failed" {:status (:status resp)}))
         :else (get (json/parse-string (:body resp) true) :entity)))
     :cljs (throw (ex-info "pull not implemented for cljs" {}))))
