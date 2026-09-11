(ns lg-kenkyusha.funding-observation-test
  "Deterministic fixtures for the funding-observation contract — no network,
  no LLM. Covers: provenance admission with missingness preserved, forbidden
  source-class refusal, verbatim dedupe, sponsorship-never-grant boundary,
  amounts recorded verbatim and never aggregated, determinism (byte-identical
  rebuild), append-only refresh, and Hyakka proposal/readback shape."
  (:require [clojure.test :as t]
            [lg-kenkyusha.funding-observation :as fo]))

(defn good-event
  [id & over]
  (merge {:event-id id
          :funding-id (str "AWD-" id)
          :funding-kind :grant
          :funder-name "Example Funding Agency"
          :recipient-name "Example Society for Testing"
          :announced-amount-verbatim "¥10,000,000"
          :source-url (str "https://funder.example/awards/" id)
          :source-class :funder-first-party
          :source-language "ja"
          :observed-at "2026-09-01"
          :content-hash (str "sha256-" id)}
         (apply hash-map over)))

;; ── provenance admission ─────────────────────────────────────────────────────

(t/deftest provenance-gate-admits-and-preserves-missingness
  (let [events [(good-event "e1")
                (good-event "e2" :funder-name "")          ; missing field
                (good-event "e3" :content-hash nil)        ; missing field
                (good-event "e4" :recipient-name "")       ; missing field
                (good-event "e5" :source-class :press-aggregator)] ; forbidden
        {:keys [admitted excluded]} (fo/admit-events events)]
    (t/is (= ["e1"] (map :event-id admitted)))
    (t/is (= #{[:missing-provenance [:funder-name]]
               [:missing-provenance [:content-hash]]
               [:missing-provenance [:recipient-name]]
               [:source-class-not-allowed [:press-aggregator]]}
             (set (map (juxt :reason :fields) excluded))))
    (t/is (= 4 (count excluded)))))

(t/deftest undeclared-funding-kind-is-refused-not-inferred
  (let [{:keys [admitted excluded]}
        (fo/admit-events [(good-event "e1" :funding-kind nil)
                          (good-event "e2" :funding-kind :mystery)])]
    (t/is (empty? admitted))
    (t/is (= #{:funding-kind-not-declared}
             (set (map :reason excluded))))))

;; ── dedupe: verbatim key, first provenance wins ──────────────────────────────

(t/deftest duplicate-funding-id-keeps-first-provenance-and-enumerates-later
  (let [e1 (good-event "e1")
        e2 (good-event "e2" :funding-id "AWD-e1"            ; same id verbatim
                       :source-url "https://mirror.example/e2"
                       :content-hash "sha256-other")
        {:keys [admitted excluded]} (fo/admit-events [e1 e2])]
    (t/is (= ["e1"] (map :event-id admitted)))
    (t/is (= [[:duplicate-key [[:funding-id "AWD-e1"]]]]
             (map (juxt :reason :fields) excluded)))))

(t/deftest funding-id-is-preserved-verbatim-not-normalized
  ;; distinct registered forms are NOT merged: differently-cased ids stay
  ;; two events — identifier preservation beats tidy dedupe.
  (let [{:keys [admitted]} (fo/admit-events [(good-event "e1" :funding-id "awd-e1")
                                             (good-event "e2" :funding-id "AWD-e1")])]
    (t/is (= 2 (count admitted)))
    (t/is (= ["awd-e1" "AWD-e1"] (map :funding-id admitted)))))

(t/deftest event-without-funding-id-dedupes-on-source-url
  (let [{:keys [admitted excluded]}
        (fo/admit-events [(good-event "e1" :funding-id nil)
                          (good-event "e2" :funding-id nil
                                      :source-url "https://funder.example/awards/e1")])]
    (t/is (= 1 (count admitted)))
    (t/is (= 1 (count (filter #(= :duplicate-key (:reason %)) excluded))))))

;; ── observation envelope: sponsorship boundary, no aggregation ───────────────

(t/deftest sponsorship-is-counted-as-sponsorship-and-never-as-grant
  (let [obs (fo/build-observation
             {:events [(good-event "e1" :funding-kind :sponsorship)
                       (good-event "e2")]
              :observed-at "2026-09-01T00:00:00Z"})]
    (t/is (= 1 (get-in obs [:counts :sponsorship])))
    (t/is (= 1 (get-in obs [:counts :grant])))
    (t/is (contains? (:flags obs) :sponsorship-present-not-grant))
    (t/is (nil? (:amount-aggregate obs)))
    (t/is (true? (:amount-aggregation-forbidden obs)))
    (t/is (true? (:amount-currency-normalization-forbidden obs)))
    (t/is (nil? (:success-rate obs)))
    (t/is (true? (:success-rate-forbidden obs)))
    (t/is (true? (:solicitation-forbidden obs)))))

(t/deftest amounts-stay-verbatim-strings
  ;; "¥10,000,000" and "JPY 10 million" are recorded as announced — never
  ;; parsed into a number, never converted, never compared.
  (let [obs (fo/build-observation
             {:events [(good-event "e1" :announced-amount-verbatim "JPY 10 million")
                       (good-event "e2" :announced-amount-verbatim "")]
              :observed-at "2026-09-01T00:00:00Z"})]
    (t/is (= ["JPY 10 million" ""]
             (map :announced-amount-verbatim (:admitted-events obs))))
    (t/is (contains? (:flags obs) :amount-missing-unmeasured))))

(t/deftest determinism-same-inputs-byte-identical-observation
  (let [mk #(pr-str (fo/build-observation
                     {:events [(good-event "e1") (good-event "e2" :funding-kind :award)]
                      :observed-at "2026-09-01T00:00:00Z"}))]
    (t/is (= (mk) (mk)))))

(t/deftest empty-batch-is-unmeasured-not-zero-funding
  (let [obs (fo/build-observation {:events [] :observed-at "2026-09-01T00:00:00Z"})]
    (t/is (contains? (:flags obs) :missing-is-unmeasured))
    (t/is (= 0 (get-in obs [:counts :registered])))))

;; ── append-only refresh ──────────────────────────────────────────────────────

(t/deftest refresh-appends-history-without-mutating-prior
  (let [o1 (fo/build-observation {:events [(good-event "e1")]
                                  :observed-at "2026-08-01T00:00:00Z"})
        o2 (fo/refresh-observation
            o1 {:events [(good-event "e1") (good-event "e2" :funding-kind :commissioned-research)]
                :observed-at "2026-09-01T00:00:00Z"})]
    (t/is (= "2026-08-01T00:00:00Z" (:observed-at o1)))     ; prior untouched
    (t/is (= 1 (count (:refresh-history o2))))
    (t/is (= "2026-08-01T00:00:00Z" (get-in o2 [:refresh-history 0 :prior-observed-at])))
    (t/is (= 2 (get-in o2 [:counts :registered])))))

;; ── Hyakka proposal / readback ───────────────────────────────────────────────

(t/deftest proposal-carries-provenance-and-guards-readback-ok
  (let [obs (fo/build-observation {:events [(good-event "e1" :funding-id "AWD/2026-001")
                                            (good-event "e2" :funding-kind :sponsorship)]
                                   :observed-at "2026-09-01T00:00:00Z"})
        proposal (fo/->hyakka-proposal obs)
        rb (fo/readback-proposal proposal)]
    (t/is (some? proposal))
    (t/is (:ok rb))
    (t/is (= "funding-observation/v1" (:contract rb)))
    (t/is (= ["AWD/2026-001" "AWD-e2"] (map :funding-id (:claim/events proposal))))
    (t/is (true? (:claim/no-amount-aggregate proposal)))
    (t/is (true? (:claim/no-solicitation proposal)))))

(t/deftest empty-observation-proposes-nothing
  (let [obs (fo/build-observation {:events [] :observed-at "2026-09-01T00:00:00Z"})]
    (t/is (nil? (fo/->hyakka-proposal obs)))))

(t/deftest tampered-proposal-fails-readback
  (let [obs (fo/build-observation {:events [(good-event "e1")]
                                   :observed-at "2026-09-01T00:00:00Z"})
        proposal (fo/->hyakka-proposal obs)
        stripped (dissoc proposal :claim/no-amount-aggregate)
        rebranded (assoc proposal :claim/contract "funding-observation/v0")]
    (t/is (false? (:ok (fo/readback-proposal stripped))))
    (t/is (false? (:ok (fo/readback-proposal rebranded))))))
