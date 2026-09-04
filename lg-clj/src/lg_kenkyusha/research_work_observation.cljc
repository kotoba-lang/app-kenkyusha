(ns lg-kenkyusha.research-work-observation
  "One bounded contract for research *work observations* in the kenkyusha
  research loop (scope: scripts/hermes-research-itonami-bots/research-scope.edn,
  :entity-types :research-work / :dataset / :software, :source-policy
  :research-allow).

  What this is: a versioned, reproducible registration of externally observed
  research works (journal articles, conference papers, preprints, datasets,
  software) from allowed first-party/registry sources, carrying verbatim
  identifiers, provenance, an explicit publication-kind so preprints are never
  conflated with peer-reviewed articles, an append-only refresh history, and a
  Hyakka proposal/readback shape.

  What this is NOT (epistemic boundaries, research-scope.edn):
    - not validation (publication-is-not-validation)
    - not peer review (preprint-is-not-peer-reviewed-article;
      conference-acceptance-is-not-journal-peer-review)
    - not quality ranking (no researcher ordering, no venue ranking, ever)
    - not completeness (missing is unmeasured, and is flagged, never imputed)

  Pure .cljc — no I/O, no JSON, no LLM. Deterministic by construction: same
  inputs -> byte-identical observation."
  (:require [clojure.string :as str]))

;; ── contract identity ────────────────────────────────────────────────────────

(def CONTRACT-VERSION "research-work-observation/v1")

(def work-kinds
  "Explicit publication-kind taxonomy. The KIND is recorded, never inferred, and
  never upgraded: a preprint stays a preprint in every derived shape."
  [:journal-article :conference-paper :preprint :dataset :software])

(def allowed-source-classes
  "research-scope.edn :source-policy :research-allow. Anything else is refused
  admission (search snippets, generated summaries, third-party wiki prose and
  scraped profiles are forbidden source classes)."
  #{:doi-registration-agency :publisher-first-party :institutional-repository
    :official-study-registry :official-dataset-repository})

(def required-provenance-fields
  "research-scope.edn :required-common-fields — every admitted work must carry
  all of them, verbatim. A work missing any is EXCLUDED and enumerated, never
  silently dropped or imputed."
  [:source-url :source-class :source-language :observed-at :content-hash
   :issuing-organization :original-title])

(defn- declared-kind-ok?
  [work]
  (contains? (set work-kinds) (get work :work-kind)))

(defn- work-problems
  [work]
  (let [missing (remove #(seq (str (get work % ""))) required-provenance-fields)
        cls     (get work :source-class)
        cls-bad? (and (seq (str cls))
                      (not (contains? allowed-source-classes cls)))
        kind-bad? (not (declared-kind-ok? work))]
    (cond
      (seq missing)  {:reason :missing-provenance :fields (vec missing)}
      cls-bad?       {:reason :source-class-not-allowed :fields [cls]}
      kind-bad?      {:reason :work-kind-not-declared :fields [(get work :work-kind)]}
      :else nil)))

;; ── admission + dedupe (verbatim identifiers, first provenance wins) ─────────

(defn dedupe-key
  "Deterministic dedupe key. :doi is preserved VERBATIM (original case, original
  form) — no normalization that could silently merge distinct registered forms.
  Works without a doi key on the verbatim source-url instead."
  [work]
  (if (seq (str (get work :doi)))
    [:doi (str (get work :doi))]
    [:source-url (str (get work :source-url))]))

(defn admit-works
  "Admit only provenance-complete, allowed-source, declared-kind works, deduped
  by `dedupe-key`. On collision the FIRST admitted work's provenance is kept
  and the later duplicate is enumerated — provenance is never overwritten.
  Returns {:admitted [..] :excluded [{:work-id .. :reason .. :fields ..}]}."
  [works]
  (loop [works works, seen #{}, admitted [], excluded []]
    (if-let [work (first works)]
      (if-let [p (work-problems work)]
        (recur (next works) seen admitted
               (conj excluded (merge {:work-id (get work :work-id)} p)))
        (let [k (dedupe-key work)]
          (if (contains? seen k)
            (recur (next works) seen admitted
                   (conj excluded {:work-id (get work :work-id)
                                   :reason :duplicate-key :fields [(vec k)]}))
            (recur (next works) (conj seen k) (conj admitted work) excluded))))
      {:admitted admitted :excluded excluded})))

;; ── derived observation ──────────────────────────────────────────────────────

(defn- flags-for
  [{:keys [admitted excluded]}]
  (cond-> #{}
    (zero? (count admitted))          (conj :missing-is-unmeasured)
    (seq excluded)                    (conj :excluded-works-present)
    (some #(= :preprint (:work-kind %)) admitted)
                                      (conj :preprint-present-not-peer-reviewed)
    (= 1 (count (set (map :source-url admitted))))
                                      (conj :single-source-dependency)
    (some #(contains? #{:dataset :software} (:work-kind %)) admitted)
                                      (conj :non-article-work-present)))

(defn build-observation
  "Derive ONE observation for ONE batch of works. Counts are additive tallies
  per declared kind — a preprint is never counted as a journal article, and no
  quality, rank, or peer-review status is derived anywhere in the envelope."
  [{:keys [works observed-at]}]
  {:pre [(seq (str observed-at)) (or (nil? works) (sequential? works))]}
  (let [{:keys [admitted excluded]} (admit-works works)
        by-kind (fn [k] (count (filter #(= k (:work-kind %)) admitted)))]
    {:contract CONTRACT-VERSION
     :method {:id "verbatim-registration-tally" :version 1}
     :observed-at (str observed-at)
     :counts {:registered (count admitted)
              :journal-article (by-kind :journal-article)
              :conference-paper (by-kind :conference-paper)
              :preprint (by-kind :preprint)
              :dataset (by-kind :dataset)
              :software (by-kind :software)
              :duplicates (count (filter #(= :duplicate-key (:reason %)) excluded))
              :excluded (count excluded)}
     :coverage {:admitted (count admitted)
                :excluded (count excluded)
                :coverage-claim :partial}
     :flags (flags-for {:admitted admitted :excluded excluded})
     ;; hard boundary markers: what this envelope deliberately does not contain
     :peer-review-asserted false
     :peer-review-assertion-forbidden true
     :quality-score nil
     :quality-scoring-forbidden true
     :causal-claims-forbidden true
     :ranking-forbidden true
     :claims []
     :excluded-works (vec excluded)
     :admitted-works (mapv #(select-keys % [:work-id :doi :work-kind
                                            :original-title :source-url
                                            :source-class :source-language
                                            :observed-at :content-hash
                                            :issuing-organization])
                           admitted)}))

(defn refresh-observation
  "Build the next observation and APPEND a refresh-history entry to the previous
  one. History is append-only — prior observations are never mutated or dropped
  (corrections arrive as new observations, never as rewrites)."
  [prev-observation {:keys [works observed-at]}]
  (let [next (build-observation {:works works :observed-at observed-at})]
    (assoc next
           :refresh-history
           (conj (vec (:refresh-history prev-observation))
                 (if prev-observation
                   {:refreshed-at (str observed-at)
                    :prior-observed-at (:observed-at prev-observation)
                    :prior-contract (:contract prev-observation)}
                   {:refreshed-at (str observed-at) :initial true})))))

;; ── Hyakka proposal / readback ───────────────────────────────────────────────

(defn ->hyakka-proposal
  "Shape an observation into one app-hyakka sourced-claim proposal. Only
  admitted works' provenance is carried, with identifiers verbatim. Returns nil
  when nothing was registered — a proposal is never fabricated from absence."
  [observation]
  (when (pos? (get-in observation [:counts :registered] 0))
    {:system/id (str "research-work-observation:" (:observed-at observation))
     :claim/type "research-work-observation"
     :claim/contract CONTRACT-VERSION
     :claim/counts (get-in observation [:counts])
     :claim/coverage (get-in observation [:coverage])
     :claim/works (get-in observation [:admitted-works])
     :source-url (first (distinct (map :source-url
                                       (get observation :admitted-works))))
     :observed-at (:observed-at observation)
     :claim/flags (vec (:flags observation))
     :claim/peer-review-asserted false
     :claim/no-quality-score true
     :claim/no-causal-claim true
     :claim/no-ranking true}))

(defn- required-proposal-fields [proposal]
  (remove #(seq (str (get proposal % "")))
          [:system/id :claim/type :claim/contract :observed-at]))

(defn readback-proposal
  "Validate a Hyakka proposal READ BACK from the claim graph. Returns
  {:ok true :contract ..} when the round-trip preserves the contract identity,
  provenance, and the boundary guards; {:ok false :errors [..]} otherwise.
  Readback never upgrades a rejected proposal, never re-admits a forbidden
  source class, and never turns a preprint into a peer-reviewed article."
  [proposal]
  (let [errs (vec (concat
                   (map #(str "missing-field:" (name %))
                        (required-proposal-fields proposal))
                   (when-not (= CONTRACT-VERSION (:claim/contract proposal))
                     [(str "contract-mismatch:" (pr-str (:claim/contract proposal)))])
                   (when-not (false? (:claim/peer-review-asserted proposal))
                     ["peer-review-guard-missing"])
                   (when-not (true? (:claim/no-quality-score proposal))
                     ["quality-score-guard-missing"])
                   (when-not (true? (:claim/no-causal-claim proposal))
                     ["causal-guard-missing"])
                   (when-not (true? (:claim/no-ranking proposal))
                     ["ranking-guard-missing"])))]
    (if (seq errs)
      {:ok false :errors errs}
      {:ok true :contract (:claim/contract proposal)})))
