(ns lg-kenkyusha.graphs.research-loop
  "kenkyusha `kenkyusha_research_loop` graph — clj/bb port onto a langgraph-clj
  StateGraph (ADR-2606280030 langgraph-python -> langgraph-clj).

  TOPOLOGY DEVIATION (load-bearing, noted): the Python server registers a SINGLE
  graph `kenkyusha_research_loop` whose builder lives OUTSIDE this app, in the
  shared `kotodama.kenkyusha.graph:build_graph` package (declared in langgraph.json
  via `../../../40-engine/.../kotoba-kotodama/py`). That package is not vendored
  into this app dir, so this namespace is a FAITHFUL REPRESENTATION of its
  documented Science-OS 6-role super-step co-scientist pipeline (CLAUDE.md:
  仮説生成 → 文献エビデンス収集 → 検証 → ranking/consensus), with the LLM and the
  evidence/source edges expressed as INJECTABLE seams (the actor swap pattern).
  The result envelope keys are exactly what server.py reads back:
  frontier_id / frontier_did / winner_hypothesis_id / consensus_level / next_action.

  Topology (linear super-steps; one run = one operation, no infinite inner loop):
    START → detect_frontier → generate_hypotheses → collect_evidence
          → evaluate_hypotheses → rank_consensus → finalize → END

  Injectable edges (tests rebind to deterministic stubs):
    *advisor*          (role prompt) → proposal string | seq | {:error ..}
                       (default: Murakumo loopback gateway, ADR-2605215000)
    *collect-evidence* (frontier hypotheses) → seq of evidence maps
                       (default: none wired offline → [])

  State is a Clojure map; each node returns a partial map merged into it
  (langgraph-clj merge semantics). Loads under babashka."
  (:require #?(:clj [cheshire.core :as json])
            [clojure.string :as str]
            [langgraph.graph :as g]))

;; ── config / Murakumo fleet (ADR-2605215000) ─────────────────────────────────

(def default-config {:repo "did:web:kenkyusha.etzhayyim.com"
                     :url "http://127.0.0.1:4000/v1"
                     :model "gemma3:4b"
                     :timeout-sec 120.0})
(def ^:dynamic *config* default-config)

;; The ONLY inference endpoints representable — Murakumo loopback / fleet.
(def murakumo-allowed-hosts
  #{"127.0.0.1:4000" "localhost:4000"
    "192.168.1.70:8077" "192.168.1.70:11434"
    "127.0.0.1:11434" "localhost:11434"})

(defn- clip [s n] (let [s (str s)] (subs s 0 (min n (count s)))))

;; ── frontier id (djb2, mirrors CLAUDE.md `frontier:{djb2Hash}`) ───────────────

(defn djb2
  "Classic djb2 string hash → unsigned 32-bit, hex (matches the frontier nanoid)."
  [s]
  (let [h (reduce (fn [acc c] (bit-and (+ (* acc 33) (int c)) 0xffffffff))
                  5381 (seq (str s)))]
    #?(:clj (format "%08x" h) :cljs (.toString h 16))))

;; ── injectable Murakumo advisor edge ──────────────────────────────────────────

(defn assert-murakumo
  "Refuse any LLM endpoint outside the Murakumo fleet (http only) — ADR-2605215000."
  [endpoint]
  (let [[_ scheme host] (or (re-find #"^([A-Za-z][A-Za-z0-9+.\-]*)://([^/?#]*)" (str endpoint))
                            [nil nil nil])]
    (when-not (and (= "http" (some-> scheme str/lower-case))
                   (contains? murakumo-allowed-hosts (some-> host str/lower-case)))
      (throw (ex-info (str "inference endpoint " (pr-str endpoint)
                           " is outside the Murakumo fleet (ADR-2605215000)")
                      {:murakumo-only-violation true :endpoint endpoint})))))

(defn advisor-with
  "Default `*advisor*`: POST a chat-completions request to the Murakumo loopback
  gateway (no-server-key, read-only). Returns the proposal text or {:error ...}."
  [http-post {:keys [url model timeout-sec]} _role prompt]
  (when-not (fn? http-post)
    (throw (ex-info "Kenkyusha advisor requires an explicit HTTP POST capability"
                    {:capability :kenkyusha/murakumo-http-post})))
  #?(:clj
     (try
       (let [url      (str/replace (str url) #"/+$" "")
             _        (assert-murakumo url)
             resp     (http-post (str url "/chat/completions")
                            {:headers {"Content-Type" "application/json"}
                             :timeout (long (* 1000 (double timeout-sec)))
                             :throw false
                             :body (json/generate-string {:model model
                                              :messages [{:role "user" :content (str prompt)}]
                                              :max_tokens 800
                                              :temperature 0.3})})]
         (if (>= (:status resp) 400)
           {:error (str "murakumo " (:status resp) ": " (clip (:body resp) 200))}
           (let [body (json/parse-string (:body resp) true)
                 txt  (some-> (get-in body [:choices 0 :message :content]) str str/trim)]
             (if (seq txt) txt {:error "advisor returned empty proposal"}))))
       (catch Exception e {:error (clip (.getMessage e) 200)}))
     :cljs {:error "advisor not implemented for cljs"}))

(def ^:dynamic *advisor* nil)

(defn advisor [role prompt]
  (if (fn? *advisor*)
    (*advisor* role prompt)
    {:error "advisor capability not configured"}))

;; Default evidence collector: nothing wired offline (injected in prod/tests).
(def ^:dynamic *collect-evidence* (fn [_frontier _hyps] []))

;; ── nodes (6-role super-step pipeline) ────────────────────────────────────────

(defn node-detect-frontier
  "Role 1 — frontier detection / open. Derives the frontier id+did from the title
  (or the cron discipline), and normalizes the run parameters."
  [state]
  (let [title (str/trim (or (:frontierTitle state) (:title state) ""))
        discipline (str (or (:primaryDiscipline state) "0613"))
        title (if (str/blank? title)
                (str "frontier:" discipline)        ; cron path has no explicit title
                title)
        max-h (max 2 (min (int (or (:maxHypotheses state) 4)) 8))
        fid   (djb2 (str discipline "|" title))]
    {:frontierTitle title
     :primaryDiscipline discipline
     :maxHypotheses max-h
     :frontier_id fid
     :frontier_did (str (:repo *config*) ":frontier:" fid)
     :super_step 0}))

(defn- ->statements
  "Normalize an advisor proposal into a vector of <= n statement strings."
  [proposal n]
  (cond
    (sequential? proposal) (vec (take n (map str proposal)))
    (and (string? proposal) (seq (str/trim proposal)))
    (->> (str/split-lines proposal)
         (map #(str/replace % #"^\s*[-*\d.)：:]+\s*" ""))
         (map str/trim)
         (remove str/blank?)
         (take n)
         vec)
    :else []))

(defn node-generate-hypotheses
  "Role 2 — hypothesis generation (Murakumo LLM). Falls back to deterministic
  placeholders if the advisor errors/returns nothing, so the loop always yields
  an envelope (offline-safe)."
  [state]
  (let [n      (:maxHypotheses state)
        prompt (str "Research frontier: " (:frontierTitle state)
                    "\nDiscipline (ISCED-F): " (:primaryDiscipline state)
                    "\nPropose " n " concise, falsifiable hypotheses (one per line).")
        res    (advisor :generate prompt)
        stmts  (->statements (if (map? res) nil res) n)
        stmts  (if (seq stmts)
                 stmts
                 (mapv #(str "Hypothesis " (inc %) " for " (:frontierTitle state)) (range n)))
        hyps   (vec (map-indexed
                     (fn [i s]
                       {:hypothesis_id (str (:frontier_id state) "-h" (inc i))
                        :statement s
                        :confidence_score 0.0
                        :elo_rating 1200
                        :supporting_evidence 0
                        :contradicting_evidence 0
                        :super_step 1
                        :status "proposed"})
                     stmts))]
    {:hypotheses hyps :super_step 1}))

(defn node-collect-evidence
  "Role 3 — evidence collection from sources (bunken/hanrei/isbn — injectable).
  Tallies supporting/contradicting counts per hypothesis."
  [state]
  (let [evidence (vec (*collect-evidence* (select-keys state [:frontier_id :primaryDiscipline])
                                          (:hypotheses state)))
        by-hyp   (group-by :hypothesis_id evidence)
        hyps'    (mapv (fn [h]
                         (let [es (get by-hyp (:hypothesis_id h) [])
                               sup (count (filter #(= "supports" (:evidence_type %)) es))
                               con (count (filter #(= "contradicts" (:evidence_type %)) es))]
                           (assoc h :supporting_evidence sup :contradicting_evidence con)))
                       (:hypotheses state))]
    {:evidence evidence :hypotheses hyps' :evidence_count (count evidence) :super_step 2}))

(defn- score [h]
  (let [s (:supporting_evidence h) c (:contradicting_evidence h)
        total (+ s c)]
    (if (zero? total) 0.0 (double (/ s total)))))

(defn node-evaluate-hypotheses
  "Role 4 — verification / scoring. Confidence = support ratio; elo nudged by
  net evidence."
  [state]
  (let [hyps' (mapv (fn [h]
                      (let [cs (score h)
                            net (- (:supporting_evidence h) (:contradicting_evidence h))]
                        (assoc h
                               :confidence_score cs
                               :elo_rating (+ (:elo_rating h) (* 16 net))
                               :status (cond
                                         (>= cs 0.75) "supported"
                                         (and (pos? (:contradicting_evidence h))
                                              (<= cs 0.25)) "refuted"
                                         (pos? (+ (:supporting_evidence h)
                                                  (:contradicting_evidence h))) "investigating"
                                         :else "proposed"))))
                    (:hypotheses state))]
    {:hypotheses hyps' :super_step 3}))

(defn- consensus-level
  "Map the winner's support ratio + corroboration to the frontier consensus_level
  enum (none | disputed | emerging | partial | strong)."
  [winner]
  (let [cs (or (:confidence_score winner) 0.0)
        sup (or (:supporting_evidence winner) 0)
        con (or (:contradicting_evidence winner) 0)]
    (cond
      (zero? (+ sup con)) "none"
      (and (>= cs 0.85) (>= sup 5)) "strong"
      (>= cs 0.6) "partial"
      (and (pos? con) (< (Math/abs (- cs 0.5)) 0.15)) "disputed"
      :else "emerging")))

(defn node-rank-consensus
  "Role 5 — ranking. Sort by elo; the top hypothesis is the winner; derive the
  frontier consensus_level."
  [state]
  (let [ranked (vec (sort-by :elo_rating > (:hypotheses state)))
        winner (first ranked)]
    {:hypotheses ranked
     :winner_hypothesis_id (:hypothesis_id winner "")
     :consensus_level (if winner (consensus-level winner) "none")
     :super_step 4}))

(defn node-finalize
  "Role 6 — meta-review / envelope. Decide next_action and emit the result the
  server reads back."
  [state]
  (let [cl (:consensus_level state)
        next-action (case cl
                      "strong" "publish"
                      "partial" "iterate"
                      "disputed" "collect_more_evidence"
                      "iterate")]
    {:next_action next-action
     :hypothesis_count (count (:hypotheses state))
     :super_step 5}))

(defn build
  "Compile the kenkyusha_research_loop StateGraph (6-role super-step pipeline)."
  []
  (-> (g/state-graph)
      (g/add-node :detect_frontier node-detect-frontier)
      (g/add-node :generate_hypotheses node-generate-hypotheses)
      (g/add-node :collect_evidence node-collect-evidence)
      (g/add-node :evaluate_hypotheses node-evaluate-hypotheses)
      (g/add-node :rank_consensus node-rank-consensus)
      (g/add-node :finalize node-finalize)
      (g/add-edge :detect_frontier :generate_hypotheses)
      (g/add-edge :generate_hypotheses :collect_evidence)
      (g/add-edge :collect_evidence :evaluate_hypotheses)
      (g/add-edge :evaluate_hypotheses :rank_consensus)
      (g/add-edge :rank_consensus :finalize)
      (g/set-entry-point :detect_frontier)
      (g/set-finish-point :finalize)
      (g/compile-graph)))

(def GRAPH (build))
