(ns lg-kenkyusha.research-work-observation-test
  "Deterministic fixtures for the research-work-observation contract — no
  network, no LLM. Covers: provenance admission with missingness preserved,
  forbidden source-class refusal, verbatim dedupe, preprint boundary
  (never peer-reviewed), determinism (byte-identical rebuild), append-only
  refresh, and Hyakka proposal/readback shape."
  (:require [clojure.test :as t]
            [lg-kenkyusha.research-work-observation :as rwo]))

(defn good-work
  [id & over]
  (merge {:work-id id
          :doi (str "10.0000/fix." id)
          :work-kind :journal-article
          :original-title (str "Original Title " id)
          :issuing-organization "Example Society for Testing"
          :source-url (str "https://doi.org/10.0000/fix." id)
          :source-class :doi-registration-agency
          :source-language "en"
          :observed-at "2026-09-01"
          :content-hash (str "sha256-" id)}
         (apply hash-map over)))

;; ── provenance admission ─────────────────────────────────────────────────────

(t/deftest provenance-gate-admits-and-preserves-missingness
  (let [works [(good-work "w1")
               (good-work "w2" :source-url "")                    ; missing field
               (good-work "w3" :content-hash nil)                 ; missing field
               (good-work "w4" :issuing-organization "")          ; missing field
               (good-work "w5" :source-class :third-party-wiki-prose)] ; forbidden
        {:keys [admitted excluded]} (rwo/admit-works works)]
    (t/is (= ["w1"] (map :work-id admitted)))
    (t/is (= #{[:missing-provenance [:source-url]]
               [:missing-provenance [:content-hash]]
               [:missing-provenance [:issuing-organization]]
               [:source-class-not-allowed [:third-party-wiki-prose]]}
             (set (map (juxt :reason :fields) excluded))))
    (t/is (= 4 (count excluded)))))

(t/deftest undeclared-work-kind-is-refused-not-inferred
  (let [{:keys [admitted excluded]} (rwo/admit-works [(good-work "w1" :work-kind nil)
                                                      (good-work "w2" :work-kind :mystery)])]
    (t/is (empty? admitted))
    (t/is (= #{:work-kind-not-declared}
             (set (map :reason excluded))))))

;; ── dedupe: verbatim key, first provenance wins ──────────────────────────────

(t/deftest duplicate-doi-keeps-first-provenance-and-enumerates-later
  (let [w1 (good-work "w1")
        w2 (good-work "w2" :doi "10.0000/fix.w1"              ; same doi verbatim
                      :source-url "https://mirror.example/w2"
                      :content-hash "sha256-other")
        {:keys [admitted excluded]} (rwo/admit-works [w1 w2])]
    (t/is (= ["w1"] (map :work-id admitted)))
    (t/is (= [[:duplicate-key [[:doi "10.0000/fix.w1"]]]]
             (map (juxt :reason :fields) excluded)))))

(t/deftest doi-is-preserved-verbatim-not-normalized
  ;; distinct registered forms are NOT merged: uppercase vs lowercase doi stay
  ;; two works — identifier preservation beats tidy dedupe.
  (let [{:keys [admitted]} (rwo/admit-works [(good-work "w1" :doi "10.0000/FIX.1")
                                             (good-work "w2" :doi "10.0000/fix.1")])]
    (t/is (= 2 (count admitted)))
    (t/is (= ["10.0000/FIX.1" "10.0000/fix.1"] (map :doi admitted)))))

(t/deftest work-without-doi-dedupes-on-source-url
  (let [{:keys [admitted excluded]}
        (rwo/admit-works [(good-work "w1" :doi nil)
                          (good-work "w2" :doi nil
                                     :source-url "https://doi.org/10.0000/fix.w1")])]
    (t/is (= 1 (count admitted)))
    (t/is (= 1 (count (filter #(= :duplicate-key (:reason %)) excluded))))))

;; ── observation envelope: preprint boundary, no quality score ────────────────

(t/deftest preprint-is-counted-as-preprint-and-never-peer-reviewed
  (let [obs (rwo/build-observation
             {:works [(good-work "w1" :work-kind :preprint)
                      (good-work "w2" :work-kind :journal-article)]
              :observed-at "2026-09-01T00:00:00Z"})]
    (t/is (= 1 (get-in obs [:counts :preprint])))
    (t/is (= 1 (get-in obs [:counts :journal-article])))
    (t/is (contains? (:flags obs) :preprint-present-not-peer-reviewed))
    (t/is (false? (:peer-review-asserted obs)))
    (t/is (true? (:peer-review-assertion-forbidden obs)))
    (t/is (nil? (:quality-score obs)))
    (t/is (true? (:quality-scoring-forbidden obs)))))

(t/deftest determinism-same-inputs-byte-identical-observation
  (let [mk #(pr-str (rwo/build-observation
                     {:works [(good-work "w1") (good-work "w2" :work-kind :dataset)]
                      :observed-at "2026-09-01T00:00:00Z"}))]
    (t/is (= (mk) (mk)))))

(t/deftest empty-batch-is-unmeasured-not-zero-quality
  (let [obs (rwo/build-observation {:works [] :observed-at "2026-09-01T00:00:00Z"})]
    (t/is (contains? (:flags obs) :missing-is-unmeasured))
    (t/is (= 0 (get-in obs [:counts :registered])))))

;; ── append-only refresh ──────────────────────────────────────────────────────

(t/deftest refresh-appends-history-without-mutating-prior
  (let [o1 (rwo/build-observation {:works [(good-work "w1")]
                                   :observed-at "2026-08-01T00:00:00Z"})
        o2 (rwo/refresh-observation
            o1 {:works [(good-work "w1") (good-work "w2" :work-kind :software)]
                :observed-at "2026-09-01T00:00:00Z"})]
    (t/is (= "2026-08-01T00:00:00Z" (:observed-at o1)))      ; prior untouched
    (t/is (= 1 (count (:refresh-history o2))))
    (t/is (= "2026-08-01T00:00:00Z" (get-in o2 [:refresh-history 0 :prior-observed-at])))
    (t/is (= 2 (get-in o2 [:counts :registered])))))

;; ── Hyakka proposal / readback ───────────────────────────────────────────────

(t/deftest proposal-carries-provenance-and-guards-readback-ok
  (let [obs (rwo/build-observation {:works [(good-work "w1" :doi "10.0000/FIX.W1")
                                            (good-work "w2" :work-kind :preprint)]
                                    :observed-at "2026-09-01T00:00:00Z"})
        proposal (rwo/->hyakka-proposal obs)]
    (t/is (some? proposal))
    (t/is (= "10.0000/FIX.W1" (get-in proposal [:claim/works 0 :doi]))) ; verbatim
    (t/is (true? (:claim/no-quality-score proposal)))
    (t/is (false? (:claim/peer-review-asserted proposal)))
    (t/is (= {:ok true :contract rwo/CONTRACT-VERSION}
             (select-keys (rwo/readback-proposal proposal) [:ok :contract])))))

(t/deftest proposal-nil-when-nothing-registered
  (let [obs (rwo/build-observation {:works [] :observed-at "2026-09-01T00:00:00Z"})]
    (t/is (nil? (rwo/->hyakka-proposal obs)))))

(t/deftest readback-rejects-guard-stripping-and-contract-drift
  (let [obs (rwo/build-observation {:works [(good-work "w1")]
                                    :observed-at "2026-09-01T00:00:00Z"})
        proposal (rwo/->hyakka-proposal obs)]
    (t/is (false? (:ok (rwo/readback-proposal (dissoc proposal :claim/no-ranking)))))
    (t/is (false? (:ok (rwo/readback-proposal (assoc proposal :claim/contract "other/v1")))))
    (t/is (false? (:ok (rwo/readback-proposal (assoc proposal :claim/peer-review-asserted true)))))))
