(ns lg-kenkyusha.funding-observation
  "One bounded contract for research *funding / sponsorship observations* in
  the kenkyusha research loop (scope:
  scripts/hermes-research-itonami-bots/research-scope.edn,
  :entity-types :funding / :sponsorship, :source-policy :research-allow).

  What this is: a versioned, reproducible registration of externally observed
  research funding events (grants, sponsorships, awards, commissioned
  research) from allowed first-party/registry sources, carrying verbatim
  identifiers and amounts-as-announced, provenance, an explicit
  funding-kind, and a Hyakka proposal/readback shape.

  What this is NOT (epistemic boundaries, research-scope.edn):
    - not an aggregate (amounts are never summed; total-funding is forbidden
      because announced amounts mix currencies, periods, and contingencies)
    - not a success signal (a funding event is not a quality or impact
      judgment about the recipient or the funded work)
    - not completeness (missing is unmeasured, and is flagged, never imputed)
    - not a solicitation surface: registration only. No grant application,
      sponsorship proposal, or contact of any party is represented here.

  Pure .cljc — no I/O, no JSON, no LLM. Deterministic by construction: same
  inputs -> byte-identical observation."
  (:require [clojure.string :as str]))

;; ── contract identity ────────────────────────────────────────────────────────

(def CONTRACT-VERSION "funding-observation/v1")

(def funding-kinds
  "Explicit funding-kind taxonomy. The KIND is recorded, never inferred, and
  never upgraded: a sponsorship stays a sponsorship and is never counted as a
  grant."
  [:grant :sponsorship :award :commissioned-research])

(def allowed-source-classes
  "research-scope.edn :source-policy :research-allow. Anything else is refused
  admission (search snippets, generated summaries, third-party wiki prose,
  scraped profiles, and press aggregators are forbidden source classes)."
  #{:funder-first-party :recipient-first-party :official-funding-registry})

(def required-provenance-fields
  "research-scope.edn :required-common-fields — every admitted event must carry
  all of them, verbatim. An event missing any is EXCLUDED and enumerated,
  never silently dropped or imputed."
  [:source-url :source-class :source-language :observed-at :content-hash
   :funder-name :recipient-name])

(defn- declared-kind-ok?
  [event]
  (contains? (set funding-kinds) (get event :funding-kind)))

(defn- event-problems
  [event]
  (let [missing (remove #(seq (str (get event % "")))
                        required-provenance-fields)
        cls     (get event :source-class)
        cls-bad? (and (seq (str cls))
                      (not (contains? allowed-source-classes cls)))
        kind-bad? (not (declared-kind-ok? event))]
    (cond
      (seq missing)  {:reason :missing-provenance :fields (vec missing)}
      cls-bad?       {:reason :source-class-not-allowed :fields [cls]}
      kind-bad?      {:reason :funding-kind-not-declared
                      :fields [(get event :funding-kind)]}
      :else nil)))

;; ── admission + dedupe (verbatim identifiers, first provenance wins) ─────────

(defn dedupe-key
  "Deterministic dedupe key. :funding-id is preserved VERBATIM (original case,
  original form) — no normalization that could silently merge distinct
  registered forms. Events without a funding-id key on the verbatim
  source-url instead."
  [event]
  (if (seq (str (get event :funding-id)))
    [:funding-id (str (get event :funding-id))]
    [:source-url (str (get event :source-url))]))

(defn admit-events
  "Admit only provenance-complete, allowed-source, declared-kind events,
  deduped by `dedupe-key`. On collision the FIRST admitted event's provenance
  is kept and the later duplicate is enumerated — provenance is never
  overwritten. Returns {:admitted [..] :excluded [{:event-id .. :reason ..
  :fields ..}]}."
  [events]
  (loop [events events, seen #{}, admitted [], excluded []]
    (if-let [event (first events)]
      (if-let [p (event-problems event)]
        (recur (next events) seen admitted
               (conj excluded (merge {:event-id (get event :event-id)} p)))
        (let [k (dedupe-key event)]
          (if (contains? seen k)
            (recur (next events) seen admitted
                   (conj excluded {:event-id (get event :event-id)
                                   :reason :duplicate-key :fields [(vec k)]}))
            (recur (next events) (conj seen k) (conj admitted event) excluded))))
      {:admitted admitted :excluded excluded})))

;; ── derived observation ──────────────────────────────────────────────────────

(defn- flags-for
  [{:keys [admitted excluded]}]
  (cond-> #{}
    (zero? (count admitted))        (conj :missing-is-unmeasured)
    (seq excluded)                  (conj :excluded-events-present)
    (some #(str/blank? (str (:announced-amount-verbatim %))) admitted)
                                    (conj :amount-missing-unmeasured)
    (= 1 (count (set (map :source-url admitted))))
                                    (conj :single-source-dependency)
    (some #(= :sponsorship (:funding-kind %)) admitted)
                                    (conj :sponsorship-present-not-grant)))

(defn build-observation
  "Derive ONE observation for ONE batch of funding events. Counts are additive
  tallies per declared kind — a sponsorship is never counted as a grant. The
  :amounts-verbatim are recorded as announced; NO sum, average, conversion,
  or comparison is computed anywhere in the envelope."
  [{:keys [events observed-at]}]
  {:pre [(seq (str observed-at)) (or (nil? events) (sequential? events))]}
  (let [{:keys [admitted excluded]} (admit-events events)
        by-kind (fn [k] (count (filter #(= k (:funding-kind %)) admitted)))]
    {:contract CONTRACT-VERSION
     :method {:id "verbatim-registration-tally" :version 1}
     :observed-at (str observed-at)
     :counts {:registered (count admitted)
              :grant (by-kind :grant)
              :sponsorship (by-kind :sponsorship)
              :award (by-kind :award)
              :commissioned-research (by-kind :commissioned-research)
              :duplicates (count (filter #(= :duplicate-key (:reason %)) excluded))
              :excluded (count excluded)}
     :coverage {:admitted (count admitted)
                :excluded (count excluded)
                :coverage-claim :partial}
     :flags (flags-for {:admitted admitted :excluded excluded})
     ;; hard boundary markers: what this envelope deliberately does not contain
     :amount-aggregate nil
     :amount-aggregation-forbidden true
     :amount-currency-normalization-forbidden true
     :quality-score nil
     :quality-scoring-forbidden true
     :success-rate nil
     :success-rate-forbidden true
     :solicitation-forbidden true
     :claims []
     :excluded-events (vec excluded)
     :admitted-events (mapv #(select-keys % [:event-id :funding-id :funding-kind
                                             :funder-name :recipient-name
                                             :announced-amount-verbatim
                                             :source-url :source-class
                                             :source-language :observed-at
                                             :content-hash])
                            admitted)}))

(defn refresh-observation
  "Build the next observation and APPEND a refresh-history entry to the
  previous one. History is append-only — prior observations are never mutated
  or dropped (corrections arrive as new observations, never as rewrites)."
  [prev-observation {:keys [events observed-at]}]
  (let [next (build-observation {:events events :observed-at observed-at})]
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
  admitted events' provenance is carried, with identifiers and amounts
  verbatim. Returns nil when nothing was registered — a proposal is never
  fabricated from absence."
  [observation]
  (when (pos? (get-in observation [:counts :registered] 0))
    {:system/id (str "funding-observation:" (:observed-at observation))
     :claim/type "funding-observation"
     :claim/contract CONTRACT-VERSION
     :claim/counts (get-in observation [:counts])
     :claim/coverage (get-in observation [:coverage])
     :claim/events (get-in observation [:admitted-events])
     :source-url (first (distinct (map :source-url
                                       (get observation :admitted-events))))
     :observed-at (:observed-at observation)
     :claim/flags (vec (:flags observation))
     :claim/no-amount-aggregate true
     :claim/no-quality-score true
     :claim/no-success-rate true
     :claim/no-solicitation true}))

(defn- required-proposal-fields [proposal]
  (remove #(seq (str (get proposal % "")))
          [:system/id :claim/type :claim/contract :observed-at]))

(defn readback-proposal
  "Validate a Hyakka proposal READ BACK from the claim graph. Returns
  {:ok true :contract ..} when the round-trip preserves the contract identity,
  provenance, and the boundary guards; {:ok false :errors [..]} otherwise.
  Readback never upgrades a rejected proposal, never re-admits a forbidden
  source class, and never aggregates amounts."
  [proposal]
  (let [errs (vec (concat
                   (map #(str "missing-field:" (name %))
                        (required-proposal-fields proposal))
                   (when-not (= CONTRACT-VERSION (:claim/contract proposal))
                     [(str "contract-mismatch:" (pr-str (:claim/contract proposal)))])
                   (when-not (true? (:claim/no-amount-aggregate proposal))
                     ["amount-aggregate-guard-missing"])
                   (when-not (true? (:claim/no-quality-score proposal))
                     ["quality-score-guard-missing"])
                   (when-not (true? (:claim/no-success-rate proposal))
                     ["success-rate-guard-missing"])
                   (when-not (true? (:claim/no-solicitation proposal))
                     ["solicitation-guard-missing"])))]
    (if (seq errs)
      {:ok false :errors errs}
      {:ok true :contract (:claim/contract proposal)})))
