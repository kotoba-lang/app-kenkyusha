(ns lg-kenkyusha.event-observation
  "One bounded contract for scholarly-society / event-series / event-edition /
  venue *event observations* in the kenkyusha research loop (scope:
  scripts/hermes-research-itonami-bots/research-scope.edn, :entity-types
  :scholarly-society :event-series :event-edition :venue, :source-policy
  :events-allow).

  What this is: a versioned, reproducible COUNT of externally observed event
  entities per entity-type, over an explicit measurement window, carrying
  provenance, deterministic dedupe keys, coverage/missingness flags, an
  append-only refresh history, and a Hyakka proposal/readback shape.

  What this is NOT (epistemic boundaries, research-scope.edn):
    - not a ranking of societies, conferences, or venues (no ordering, ever)
    - not an endorsement (society membership or venue hosting is not support)
    - not a venue generalization (:venue is :event-edition-specific — a venue
      observed for one edition is never promoted to the series)
    - not completeness (missing is unmeasured, and is flagged, never imputed)

  Pure .cljc — no I/O, no JSON, no LLM. Deterministic by construction: same
  inputs + same window -> byte-identical observation."
  (:require [clojure.string :as str]))

;; ── contract identity ────────────────────────────────────────────────────────

(def CONTRACT-VERSION "event-observation/v1")

(def event-entity-types
  "research-scope.edn :entity-types, the event/society/venue subset. One
  observation covers exactly ONE entity-type — never a composite."
  [:scholarly-society :event-series :event-edition :venue])

(def allowed-source-classes
  "research-scope.edn :source-policy :events-allow. Anything else is refused
  admission (search snippets, generated summaries, third-party wiki prose,
  scraped profiles, and inferred sponsorship are forbidden source classes)."
  #{:society-first-party :conference-organizer-first-party :venue-first-party})

(def allowed-identifiers
  "research-scope.edn :identifiers, the subset applicable to event entities.
  Preserved verbatim; never minted, never normalized away."
  #{:ror :issn :isbn :registry-id :official-event-id})

(def required-provenance-fields
  "research-scope.edn :required-common-fields. An event record missing any of
  these is EXCLUDED and counted as missing — preserved, never silently dropped
  or imputed."
  [:source-url :source-class :source-language :observed-at :content-hash
   :issuing-organization :original-title])

;; ── deterministic dedupe key ─────────────────────────────────────────────────

(defn dedupe-key
  "Deterministic content-derived key: source namespace (host of :source-url) +
  identifier namespace/value (first present allowed identifier) + entity-type
  + :original-title. Byte-stable; no hashing of mutable display text beyond the
  title itself. Two records with the same key are the SAME entity observation —
  the first provenance wins and later arrivals append to refresh history, they
  never overwrite."
  [event]
  (let [url (str (get event :source-url ""))
        host (subs url 0 (min (count url)
                              (or (str/index-of url "/" 8) (count url))))
        id-ns (first (filter #(seq (str (get event % ""))) allowed-identifiers))
        id-val (when id-ns (str (get event id-ns)))]
    (str host "|" (if id-ns (name id-ns) "no-identifier") "|" (or id-val "-")
         "|" (name (get event :entity-type))
         "|" (get event :original-title))))

;; ── record admission (provenance gate + missingness preservation) ────────────

(defn- event-problems
  [event window]
  (let [missing (remove #(seq (str (get event % ""))) required-provenance-fields)
        cls     (get event :source-class)
        cls-bad? (and (seq (str cls))
                      (not (contains? allowed-source-classes cls)))
        etype   (get event :entity-type)
        etype-bad? (and (seq (str etype))
                        (not (contains? event-entity-types etype)))
        at      (get event :observed-at)
        outside? (and (seq (str at)) window
                      (or (pos? (compare (str at) (str (:to window))))
                          (neg? (compare (str at) (str (:from window))))))]
    (cond
      (seq missing)   {:reason :missing-provenance :fields (vec missing)}
      cls-bad?        {:reason :source-class-not-allowed :fields [cls]}
      etype-bad?      {:reason :entity-type-not-allowed :fields [etype]}
      outside?        {:reason :outside-window :fields [(:from window) (:to window)]}
      :else nil)))

(defn normalize-events
  "Admit only provenance-complete, allowed-source, allowed-entity-type,
  in-window event records; dedupe by :dedupe-key keeping the FIRST provenance.
  Returns {:admitted [..] :duplicates [..] :excluded [..]} — duplicates and
  excluded records are enumerated, so missingness is an observation, not a hole."
  [events window]
  (let [{:keys [admitted excluded]}
        (reduce
         (fn [acc event]
           (if-let [p (event-problems event window)]
             (update acc :excluded conj (merge {:event-id (get event :event-id)} p))
             (update acc :admitted conj (assoc event :dedupe-key (dedupe-key event)))))
         {:admitted [] :excluded []}
         events)
        ;; first provenance wins; later same-key records are enumerated duplicates
        by-key (reduce (fn [m e]
                         (update m (:dedupe-key e) (fnil conj []) e))
                       {} admitted)]
    {:admitted (vec (map first (vals by-key)))
     :duplicates (vec (mapcat rest (vals by-key)))
     :excluded excluded}))

;; ── derived observation ──────────────────────────────────────────────────────

(defn- flags-for
  [{:keys [admitted duplicates excluded]}]
  (cond-> #{}
    (zero? (count admitted))          (conj :missing-is-unmeasured)
    (seq excluded)                    (conj :excluded-events-present)
    (seq duplicates)                  (conj :duplicates-collapsed)
    (= 1 (count (set (map :source-url admitted)))) (conj :single-source-dependency)))

(defn build-observation
  "Derive ONE observation for ONE entity-type over ONE explicit window. Counts
  are additive tallies of distinct observed entities — there is no score, no
  rank, and no endorsement field anywhere in the envelope. A :venue observation
  is always edition-scoped (the boundary is a hard flag, never a promotion)."
  [{:keys [entity-type window events observed-at]}]
  {:pre [(contains? (set event-entity-types) entity-type)
         (map? window) (seq (str (:from window))) (seq (str (:to window)))
         (seq (str observed-at))]}
  (let [{:keys [admitted duplicates excluded]} (normalize-events events window)]
    {:contract CONTRACT-VERSION
     :method {:id "distinct-entity-tally" :version 1}
     :entity-type entity-type
     :window {:from (str (:from window)) :to (str (:to window))}
     :observed-at (str observed-at)
     :counts {:measured (count admitted)
              :duplicates (count duplicates)
              :excluded (count excluded)}
     :coverage {:admitted (count admitted)
                :duplicates (count duplicates)
                :excluded (count excluded)
                :coverage-claim :partial}
     :flags (flags-for {:admitted admitted :duplicates duplicates :excluded excluded})
     ;; hard boundary markers: what this envelope deliberately does not contain
     :ranking nil
     :ranking-forbidden true
     :endorsement-forbidden true
     :venue-is-event-edition-specific true
     :excluded-events (vec excluded)
     :duplicates (vec duplicates)
     :admitted-events (vec admitted)}))

(defn refresh-observation
  "Time-window refresh: build the next observation and APPEND a refresh-history
  entry to the previous one. History is append-only — prior observations are
  never mutated or dropped (later editions/corrections arrive as new
  observations, never overwrites)."
  [prev-observation {:keys [entity-type window events observed-at]}]
  (let [next (build-observation {:entity-type entity-type
                                 :window window
                                 :events events
                                 :observed-at observed-at})]
    (assoc next
           :refresh-history
           (conj (vec (:refresh-history prev-observation))
                 (if prev-observation
                   {:refreshed-at (str observed-at)
                    :from-window (:window prev-observation)
                    :prior-observed-at (:observed-at prev-observation)
                    :prior-contract (:contract prev-observation)}
                   {:refreshed-at (str observed-at) :initial true})))))

;; ── Hyakka proposal / readback ───────────────────────────────────────────────

(defn ->hyakka-proposal
  "Shape an observation into one app-hyakka sourced-claim proposal for its
  entity-type. Only admitted events' provenance is carried, with original
  language and identifiers verbatim. Returns nil when nothing was measured —
  a proposal is never fabricated from absence."
  [observation]
  (when (pos? (get-in observation [:counts :measured] 0))
    {:system/id (str "event-observation:"
                     (name (:entity-type observation)) ":"
                     (get-in observation [:window :from]) ".."
                     (get-in observation [:window :to]))
     :claim/type "event-observation"
     :claim/contract CONTRACT-VERSION
     :claim/entity-type (name (:entity-type observation))
     :claim/counts (get-in observation [:counts])
     :claim/coverage (get-in observation [:coverage])
     :source-url (first (distinct (map :source-url (get observation :admitted-events))))
     :observed-at (:observed-at observation)
     :claim/flags (vec (:flags observation))
     :claim/no-ranking true
     :claim/no-endorsement true
     :claim/venue-is-event-edition-specific true}))

(defn- required-proposal-fields [proposal]
  (remove #(seq (str (get proposal % "")))
          [:system/id :claim/type :claim/contract :claim/entity-type
           :observed-at]))

(defn readback-proposal
  "Validate a Hyakka proposal READ BACK from the claim graph. Returns
  {:ok true :contract ..} when the round-trip preserves the contract identity
  and provenance; {:ok false :errors [..]} otherwise. Readback never upgrades a
  rejected proposal and never re-admits a forbidden source class."
  [proposal]
  (let [errs (vec (concat
                   (map #(str "missing-field:" (name %))
                        (required-proposal-fields proposal))
                   (when-not (= CONTRACT-VERSION (:claim/contract proposal))
                     [(str "contract-mismatch:" (pr-str (:claim/contract proposal)))])
                   (when-not (true? (:claim/no-ranking proposal))
                     ["ranking-guard-missing"])
                   (when-not (true? (:claim/no-endorsement proposal))
                     ["endorsement-guard-missing"])
                   (when-not (true? (:claim/venue-is-event-edition-specific proposal))
                     ["venue-scope-guard-missing"])))]
    (if (seq errs)
      {:ok false :errors errs}
      {:ok true :contract (:claim/contract proposal)
       :entity-type (:claim/entity-type proposal)})))
