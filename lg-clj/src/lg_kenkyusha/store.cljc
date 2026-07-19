(ns lg-kenkyusha.store
  "Frontier persistence stores — clj/bb port of the asyncpg/RisingWave reads in
  lg_kenkyusha/server.py (ADR-2606280030).

  `FrontierStore` is the narrow protocol the dispatcher uses for the read
  endpoints (`/frontiers/{id}/state`, `/frontiers`). Two implementations:

  - `->KotobaFrontierStore` — production, backed by the kotoba Datom log (graph
    `kenkyusha-v1`) via lg-kenkyusha.kotoba-datomic. SUBSTRATE BOUNDARY: the
    Python pod read RisingWave (`graphar.vertex_kenkyusha_*`); the clj twin is
    forbidden RisingWave and targets the kotoba Datom log instead.
  - `->FakeFrontierStore` — in-memory atom, used by the smoke tests so the
    dispatch + clamp logic is verified deterministically without a live pod.

  Both return the SAME envelope shape the Python endpoints returned:
    frontier-state -> {:frontier {..} :top_hypothesis {..}|nil :evidence [..]}
    list-frontiers -> [{..frontier..} ...]"
  (:require [lg-kenkyusha.kotoba-datomic :as kd]))

(defprotocol FrontierStore
  (frontier-state [this frontier-id]
    "Fetch a frontier + its top hypothesis + recent evidence, or nil if absent.")
  (list-frontiers* [this opts]
    "List frontiers. opts = {:limit <int already-clamped> :status <str|nil>}."))

;; ── kotoba Datom-log implementation ───────────────────────────────────────────

(defrecord KotobaFrontierStore [dm]
  FrontierStore
  (frontier-state [_ frontier-id]
    (let [frontier (kd/pull dm (str "frontier:" frontier-id))]
      (when frontier
        (let [hyps (kd/q dm (str "[:find ?h :where [?h :hypothesis/frontier_id \""
                                 frontier-id "\"]]"))
              top  (->> hyps
                        (map (fn [row] (kd/pull dm (str (first row)))))
                        (remove nil?)
                        (sort-by #(or (get % "elo_rating") 0) >)
                        first)
              ev   (kd/q dm (str "[:find ?e :where [?e :evidence/frontier_id \""
                                 frontier-id "\"]]"))]
          {:frontier frontier
           :top_hypothesis top
           :evidence (vec (->> ev (map (fn [row] (kd/pull dm (str (first row)))))
                               (remove nil?)))}))))
  (list-frontiers* [_ {:keys [limit status]}]
    (let [rows (kd/q dm "[:find ?f :where [?f :frontier/type \"KenkyushaFrontier\"]]")
          all  (->> rows (map (fn [row] (kd/pull dm (str (first row))))) (remove nil?))
          filt (if status (filter #(= status (get % "status")) all) all)]
      (vec (take limit filt)))))

(defn ->kotoba-frontier-store [dm]
  (->KotobaFrontierStore dm))

(defn default-store
  "Production store = kotoba Datom log (graph kenkyusha-v1)."
  []
  (->kotoba-frontier-store (kd/->client)))

;; ── in-memory fake (tests) ────────────────────────────────────────────────────

(defrecord FakeFrontierStore [db]
  FrontierStore
  (frontier-state [_ frontier-id]
    (when-let [f (get-in @db [:frontiers frontier-id])]
      {:frontier f
       :top_hypothesis (->> (get-in @db [:hypotheses frontier-id])
                            (sort-by #(or (:elo_rating %) 0) >)
                            first)
       :evidence (vec (get-in @db [:evidence frontier-id] []))}))
  (list-frontiers* [_ {:keys [limit status]}]
    (let [all  (vals (get @db :frontiers))
          filt (if status (filter #(= status (:status %)) all) all)]
      (vec (take limit filt)))))

(defn ->fake-frontier-store
  "Seed an in-memory store. seed = {:frontiers {id frontier} :hypotheses {fid [..]}
  :evidence {fid [..]}}."
  ([] (->FakeFrontierStore (atom {:frontiers {} :hypotheses {} :evidence {}})))
  ([seed] (->FakeFrontierStore (atom (merge {:frontiers {} :hypotheses {} :evidence {}} seed)))))
