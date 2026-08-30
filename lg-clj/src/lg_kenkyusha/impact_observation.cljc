(ns lg-kenkyusha.impact-observation
  "One bounded contract for research *impact observations* in the kenkyusha
  research loop (scope: scripts/hermes-research-itonami-bots/research-scope.edn,
  :entity-types :impact-observation, :source-policy :impact-allow).

  What this is: a versioned, reproducible COUNT of externally observed impact
  signals per dimension, over an explicit measurement window, carrying
  provenance, uncertainty/coverage flags, an append-only refresh history, and a
  Hyakka proposal/readback shape.

  What this is NOT (epistemic boundaries, research-scope.edn):
    - not a universal score, not a ranking (no researcher ordering, ever)
    - not causation (citation is not support; correlation is not causation)
    - not endorsement (funding is not endorsement)
    - not completeness (missing is unmeasured, and is flagged, never imputed)

  Pure .cljc — no I/O, no JSON, no LLM. Deterministic by construction: same
  inputs + same window -> byte-identical observation."
  (:require [clojure.string :as str]))

;; ── contract identity ────────────────────────────────────────────────────────

(def CONTRACT-VERSION "impact-observation/v1")

(def impact-dimensions
  "research-scope.edn :impact-dimensions. One observation covers exactly ONE
  dimension — never a cross-dimension composite."
  [:scholarly-citation :replication :correction :retraction
   :policy-citation :patent-citation :standard-adoption
   :clinical-guideline-citation :dataset-or-software-reuse])

(def allowed-source-classes
  "research-scope.edn :source-policy :impact-allow. Anything else is refused
  admission as a measured signal (search snippets, generated summaries,
  third-party wiki prose and scraped profiles are forbidden source classes)."
  #{:citation-registry :official-policy-document :official-patent-record
    :standards-body-first-party :guideline-publisher-first-party
    :publisher-correction-or-retraction})

(def required-provenance-fields
  "research-scope.edn :required-common-fields, the subset every admitted signal
  must carry. A signal missing any of these is EXCLUDED and counted as missing —
  preserved, never silently dropped or imputed."
  [:source-url :source-class :source-language :observed-at :content-hash])

;; ── signal admission (provenance gate + missingness preservation) ────────────

(defn- signal-problems
  [signal window]
  (let [missing (remove #(seq (str (get signal % ""))) required-provenance-fields)
        cls    (get signal :source-class)
        cls-bad? (and (seq (str cls))
                      (not (contains? allowed-source-classes cls)))
        at     (get signal :observed-at)
        outside? (and (seq (str at)) window
                      (or (pos? (compare (str at) (str (:to window))))
                          (neg? (compare (str at) (str (:from window))))))]
    (cond
      (seq missing)     {:reason :missing-provenance :fields (vec missing)}
      cls-bad?          {:reason :source-class-not-allowed :fields [cls]}
      outside?          {:reason :outside-window :fields [(:from window) (:to window)]}
      :else nil)))

(defn normalize-signals
  "Admit only provenance-complete, allowed-source, in-window signals. Returns
  {:admitted [..] :excluded [{:signal-id .. :reason .. :fields ..}]} — excluded
  signals are enumerated, so missingness is an observation, not a hole."
  [signals window]
  (reduce
   (fn [acc signal]
     (if-let [p (signal-problems signal window)]
       (update acc :excluded conj (merge {:signal-id (get signal :signal-id)} p))
       (update acc :admitted conj signal)))
   {:admitted [] :excluded []}
   signals))

;; ── derived observation ──────────────────────────────────────────────────────

(defn- flags-for
  [{:keys [admitted excluded]} retraction-negatives]
  (cond-> #{}
    (zero? (count admitted))          (conj :missing-is-unmeasured)
    (seq excluded)                    (conj :excluded-signals-present)
    (pos? retraction-negatives)       (conj :retraction-present)
    (= 1 (count (set (map :source-url admitted)))) (conj :single-source-dependency)))

(defn build-observation
  "Derive ONE observation for ONE dimension over ONE explicit window.
  Counts are additive tallies — polarities are never netted against each other,
  retractions are never subtracted away, and there is no score, no rank, and no
  causal field anywhere in the envelope."
  [{:keys [dimension window signals observed-at]}]
  {:pre [(contains? (set impact-dimensions) dimension)
         (map? window) (seq (str (:from window))) (seq (str (:to window)))
         (seq (str observed-at))]}
  (let [{:keys [admitted excluded]} (normalize-signals signals window)
        polarity  (fnil identity :unspecified)
        positives  (count (filter #(= :positive (polarity (:polarity %))) admitted))
        negatives  (count (filter #(= :negative (polarity (:polarity %))) admitted))
        retractions (count (filter #(= :retraction (polarity (:polarity %))) admitted))
        missingness (count (filter #(= :missing-provenance (:reason %)) excluded))]
    {:contract CONTRACT-VERSION
     :method {:id "additive-signal-tally" :version 1}
     :dimension dimension
     :window {:from (str (:from window)) :to (str (:to window))}
     :observed-at (str observed-at)
     :counts {:measured (count admitted)
              :positive positives
              :negative negatives
              :retraction retractions}
     :coverage {:admitted (count admitted)
                :excluded (count excluded)
                :missing-provenance missingness
                :coverage-claim :partial}
     :flags (flags-for {:admitted admitted :excluded excluded} retractions)
     ;; hard boundary markers: what this envelope deliberately does not contain
     :ranking nil
     :ranking-forbidden true
     :causal-claims-forbidden true
     :claims []
     :excluded-signals (vec excluded)
     :admitted-signals (vec admitted)}))

(defn refresh-observation
  "Time-window refresh: build the next observation and APPEND a refresh-history
  entry to the previous one. History is append-only — prior observations are
  never mutated or dropped (corrections arrive as new observations)."
  [prev-observation {:keys [dimension window signals observed-at]}]
  (let [next (build-observation {:dimension dimension
                                 :window window
                                 :signals signals
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
  dimension. Only admitted signals' provenance is carried. Returns nil when
  nothing was measured — a proposal is never fabricated from absence."
  [observation]
  (when (pos? (get-in observation [:counts :measured] 0))
    {:system/id (str "impact-observation:"
                     (name (:dimension observation)) ":"
                     (get-in observation [:window :from]) ".."
                     (get-in observation [:window :to]))
     :claim/type "impact-observation"
     :claim/contract CONTRACT-VERSION
     :claim/dimension (name (:dimension observation))
     :claim/counts (get-in observation [:counts])
     :claim/coverage (get-in observation [:coverage])
     :source-url (first (distinct (map :source-url (get observation :admitted-signals))))
     :observed-at (:observed-at observation)
     :claim/flags (vec (:flags observation))
     :claim/no-ranking true
     :claim/no-causal-claim true}))

(defn- required-proposal-fields [proposal]
  (remove #(seq (str (get proposal % "")))
          [:system/id :claim/type :claim/contract :claim/dimension
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
                   (when-not (true? (:claim/no-causal-claim proposal))
                     ["causal-guard-missing"])))]
    (if (seq errs)
      {:ok false :errors errs}
      {:ok true :contract (:claim/contract proposal)
       :dimension (:claim/dimension proposal)})))
