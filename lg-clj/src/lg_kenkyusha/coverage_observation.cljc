(ns lg-kenkyusha.coverage-observation
  "One bounded contract for research *coverage observations* in the kenkyusha
  research loop (scope: scripts/hermes-research-itonami-bots/research-scope.edn,
  :coverage-unit and :epistemic-boundaries :missing-is-unmeasured /
  :worldwide-is-a-coverage-goal-not-a-completeness-claim).

  What this is: a versioned, reproducible audit of WHICH coverage units
  (discipline × jurisdiction × language × source × year × identifier-class,
  per :coverage-unit) were MEASURED by a set of admitted observation units, and
  which are UNMEASURED. Coverage is a coverage-GOAL accounting, not a
  completeness claim: unmeasured units are enumerated — never imputed, never
  zero-filled, never extrapolated across an axis.

  What this is NOT (epistemic boundaries, research-scope.edn):
    - not a completeness claim (worldwide-is-a-coverage-goal)
    - not a gap-filling generator (missing is unmeasured; we record, never
      impute or sample a 'representative' unit)
    - not a ranking (no unit is better than another; no ordering output)
    - not causation (an unmeasured unit is not a cause of anything)
    - not a research frontier detector (frontier detection is a different
      actor's job; this contract only accounts for measured/unmeasured)

  Pure .cljc — no I/O, no JSON, no LLM. Deterministic by construction: same
  inputs -> byte-identical observation."
  (:require [clojure.string :as str]))

;; ── contract identity ────────────────────────────────────────────────────────

(def CONTRACT-VERSION "coverage-observation/v1")

(def coverage-axes
  "research-scope.edn :coverage-unit. One observation covers ALL axes as a
  cross-product KEY SET — every unit carries a value per axis (use
  :unspecified when the source did not state one)."
  [:discipline :jurisdiction :language :source :year :identifier-class])

(def ^:private axis-value-ok?
  "A unit's axis value must be a non-empty string or keyword. Keywords are
  stringified at admission (deterministic, locale-free)."
  (comp seq str))

(defn- removev [pred coll] (vec (remove pred coll)))

(defn- unit-problems
  [unit]
  (let [missing (remove #(axis-value-ok? (get unit %))
                        coverage-axes)]
    (cond
      (seq missing) {:reason :missing-axis-value :fields (vec missing)}
      :else nil)))

;; ── admission (explicit-unit gate + missingness preservation) ────────────────

(defn normalize-units
  "Admit only coverage units that declare a value on every axis. Returns
  {:admitted [..] :excluded [{:unit-id .. :reason .. :fields ..}]} — excluded
  units are enumerated, so under-declared units are an observation, not a hole."
  [units]
  (reduce
   (fn [acc unit]
     (if-let [p (unit-problems unit)]
       (update acc :excluded conj (merge {:unit-id (get unit :unit-id)} p))
       (update acc :admitted conj
               (reduce (fn [u axis]
                         (update u axis #(if (keyword? %) (name %) (str %))))
                       unit
                       coverage-axes))))
   {:admitted [] :excluded []}
   units))

;; ── measured/unmeasured enumeration (the derived observation) ────────────────

(defn- unit-key
  "Deterministic cross-product key for one unit, axis-ordered."
  [unit]
  (mapv (fn [axis]
           (let [v (get unit axis "")]
             (cond (keyword? v) (name v)
                   :else (str v))))
         coverage-axes))

(defn- key->unit
  [k]
  (zipmap coverage-axes k))

(defn- distinct-measured
  [units]
  (vec (sort (distinct (map unit-key units)))))

(defn- flags-for
  [{:keys [admitted excluded]} measured unmeasured]
  (cond-> #{}
    (zero? (count admitted))      (conj :missing-is-unmeasured)
    (seq excluded)                (conj :excluded-units-present)
    (empty? measured)             (conj :no-units-measured)
    (empty? unmeasured)           (conj :all-units-measured-flagged-not-claimed)
    (= 1 (count (distinct (map #(get % :discipline) admitted))))
                                  (conj :single-discipline-dependency)))

(defn build-observation
  "Derive ONE coverage observation: which :coverage-unit cross-product units
  are measured by the admitted observation set, and which units inside the
  caller-declared audit frame are unmeasured.

  :frame is the caller-declared bounded space of units to account for (the
  coverage GOAL for this run). Frame units that were measured are :measured;
  frame units never measured are :unmeasured and enumerated verbatim. Units
  measured but OUTSIDE the frame are enumerated in :outside-frame-units —
  visible, never silently dropped, never folded into the frame's counts.

  No completeness claim anywhere: :coverage-claim is always :partial, and
  :unmeasured is a first-class field, not an absence."
  [{:keys [frame units observed-at]}]
  {:pre [(map? frame) (seq (str observed-at))
         (or (nil? units) (sequential? units))]}
  (let [{:keys [admitted excluded]} (normalize-units units)
        frame-keys  (distinct-measured (:units frame))
        measured-in (set frame-keys)
        all-keys    (distinct-measured admitted)
        inside      (filterv measured-in all-keys)
        outside     (removev #(contains? measured-in %) all-keys)
        unmeasured  (removev #(contains? (set inside) %) frame-keys)]
    {:contract CONTRACT-VERSION
     :method {:id "frame-difference-unit-tally" :version 1}
     :observed-at (str observed-at)
     :frame {:units (vec (:units frame))
             :declared-units (count frame-keys)}
     :counts {:frame-units (count frame-keys)
              :measured (count inside)
              :unmeasured (count unmeasured)
              :outside-frame (count outside)
              :excluded (count excluded)}
     :coverage {:admitted (count admitted)
                :excluded (count excluded)
                :coverage-claim :partial}
     :measured-units (mapv key->unit inside)
     :unmeasured-units (mapv key->unit unmeasured)
     :outside-frame-units (mapv key->unit outside)
     :flags (flags-for {:admitted admitted :excluded excluded}
                       inside unmeasured)
     ;; hard boundary markers: what this envelope deliberately does not contain
     :completeness-asserted nil
     :completeness-assertion-forbidden true
     :imputation-forbidden true
     :ranking-forbidden true
     :causal-claims-forbidden true
     :claims []
     :excluded-units (vec excluded)}))

(defn refresh-observation
  "Time-window refresh: build the next observation and APPEND a refresh-history
  entry to the previous one. History is append-only — prior observations are
  never mutated or dropped (coverage changes arrive as new observations)."
  [prev-observation {:keys [frame units observed-at]}]
  (let [next (build-observation {:frame frame :units units
                                 :observed-at observed-at})]
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
  "Shape a coverage observation into one app-hyakka sourced-claim proposal.
  Returns nil when nothing was measured — a proposal is never fabricated from
  absence. Only measured units are carried; unmeasured units stay in the
  observation envelope as a count, never exported as if they were data."
  [observation]
  (when (pos? (get-in observation [:counts :measured] 0))
    {:system/id (str "coverage-observation:" (:observed-at observation))
     :claim/type "coverage-observation"
     :claim/contract CONTRACT-VERSION
     :claim/counts (get-in observation [:counts])
     :claim/coverage (get-in observation [:coverage])
     :claim/measured-units (get observation :measured-units)
     :claim/unmeasured-count (get-in observation [:counts :unmeasured])
     :observed-at (:observed-at observation)
     :claim/flags (vec (:flags observation))
     :claim/no-completeness true
     :claim/no-imputation true
     :claim/no-ranking true
     :claim/no-causal-claim true}))

(defn- required-proposal-fields [proposal]
  (remove #(seq (str (get proposal % "")))
          [:system/id :claim/type :claim/contract :observed-at]))

(defn readback-proposal
  "Validate a Hyakka proposal READ BACK from the claim graph. Returns
  {:ok true :contract ..} when the round-trip preserves the contract identity
  and the boundary guards; {:ok false :errors [..]} otherwise. Readback never
  upgrades a rejected proposal, never fills an unmeasured unit, and never
  strips the completeness guard."
  [proposal]
  (let [errs (vec (concat
                   (map #(str "missing-field:" (name %))
                        (required-proposal-fields proposal))
                   (when-not (= CONTRACT-VERSION (:claim/contract proposal))
                     [(str "contract-mismatch:" (pr-str (:claim/contract proposal)))])
                   (when-not (true? (:claim/no-completeness proposal))
                     ["completeness-guard-missing"])
                   (when-not (true? (:claim/no-imputation proposal))
                     ["imputation-guard-missing"])
                   (when-not (true? (:claim/no-ranking proposal))
                     ["ranking-guard-missing"])
                   (when-not (true? (:claim/no-causal-claim proposal))
                     ["causal-guard-missing"])))]
    (if (seq errs)
      {:ok false :errors errs}
      {:ok true :contract (:claim/contract proposal)})))
