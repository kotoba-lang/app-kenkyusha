(ns lg-kenkyusha.coverage-observation-test
  "Deterministic fixtures for the coverage-observation contract — no network,
  no LLM. Covers the run's required scenarios: time-window refresh,
  bias/missing-data flags, provenance/unit admission, and Hyakka
  proposal/readback. Coverage is a goal accounting, never a completeness
  claim — unmeasured units are enumerated, never imputed."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [lg-kenkyusha.coverage-observation :as co]))

(def frame-units
  ;; caller-declared coverage GOAL for this audit frame (3 units)
  [{:unit-id "f-1" :discipline "0511-biology" :jurisdiction "JP"
    :language "ja" :source "doi-registration-agency" :year "2025"
    :identifier-class :doi}
   {:unit-id "f-2" :discipline "0612-database" :jurisdiction "US"
    :language "en" :source "official-dataset-repository" :year "2025"
    :identifier-class :ror}
   {:unit-id "f-3" :discipline "0511-biology" :jurisdiction "DE"
    :language "de" :source "institutional-repository" :year "2024"
    :identifier-class :orcid}])

(def frame {:units frame-units})

(defn measured-unit
  [id & over]
  (merge {:unit-id id
          :discipline "0511-biology" :jurisdiction "JP" :language "ja"
          :source "doi-registration-agency" :year "2025"
          :identifier-class :doi}
         (apply hash-map over)))

;; ── unit admission ───────────────────────────────────────────────────────────

(t/deftest unit-gate-admits-and-preserves-missingness
  (let [units [(measured-unit "u1")
               (measured-unit "u2" :language "")            ; missing axis
               (measured-unit "u3" :identifier-class nil)]  ; missing axis
        {:keys [admitted excluded]} (co/normalize-units units)]
    (t/is (= ["u1"] (map :unit-id admitted)))
    (t/is (= #{[:missing-axis-value [:language]]
               [:missing-axis-value [:identifier-class]]}
             (set (map (juxt :reason :fields) excluded))))
    ;; keywords are stringified deterministically at admission
    (t/is (= "doi" (get (first admitted) :identifier-class)))))

;; ── derived observation: measured vs unmeasured, frame difference ────────────

(t/deftest frame-difference-enumerates-measured-and-unmeasured
  (let [obs (co/build-observation
             {:frame frame
              :units [(measured-unit "u1")        ; == f-1
                      (measured-unit "u4"         ; outside the frame
                       :discipline "07-law" :jurisdiction "FR"
                       :language "fr" :source "official-patent-record"
                       :year "2026" :identifier-class :grant-id)]
              :observed-at "2026-09-05T00:00:00Z"})]
    (t/is (= "coverage-observation/v1" (:contract obs)))
    (t/is (= {:frame-units 3 :measured 1 :unmeasured 2 :outside-frame 1 :excluded 0}
             (:counts obs)))
    ;; measured and unmeasured units are BOTH enumerated — never zero-filled
    (t/is (= ["f-1"] (map :unit-id (map #(assoc % :unit-id "f-1")
                                        (:measured-units obs)))))
    (t/is (= 2 (count (:unmeasured-units obs))))
    (t/is (= 1 (count (:outside-frame-units obs))))
    ;; outside-frame units are visible, never silently folded into the frame
    (t/is (= "07-law" (get-in obs [:outside-frame-units 0 :discipline])))
    (t/is (= :partial (get-in obs [:coverage :coverage-claim])))
    (t/is (and (:completeness-assertion-forbidden obs)
               (:imputation-forbidden obs)
               (:ranking-forbidden obs)
               (:causal-claims-forbidden obs)))
    (t/is (nil? (:completeness-asserted obs)))
    (t/is (empty? (:claims obs)))))

(t/deftest no-units-measured-is-unmeasured-not-zero-coverage
  (let [obs (co/build-observation
             {:frame frame :units [] :observed-at "2026-09-05T00:00:00Z"})]
    (t/is (zero? (get-in obs [:counts :measured])))
    (t/is (= 3 (get-in obs [:counts :unmeasured])))
    (t/is (contains? (:flags obs) :missing-is-unmeasured))
    (t/is (contains? (:flags obs) :no-units-measured))
    ;; no Hyakka proposal is fabricated from absence
    (t/is (nil? (co/->hyakka-proposal obs)))))

(t/deftest single-discipline-dependency-flagged
  (let [obs (co/build-observation
             {:frame frame
              :units [(measured-unit "u1")
                      (measured-unit "u1b")]   ; same discipline as u1
              :observed-at "2026-09-05T00:00:00Z"})]
    (t/is (contains? (:flags obs) :single-discipline-dependency))))

(t/deftest determinism-same-inputs-byte-identical-observation
  (let [mk #(co/build-observation
             {:frame frame
              :units [(measured-unit "u1") (measured-unit "u2b"
                                            :discipline "0612-database"
                                            :jurisdiction "US" :language "en"
                                            :source "official-dataset-repository"
                                            :year "2025" :identifier-class :ror)]
              :observed-at "2026-09-05T00:00:00Z"})]
    (t/is (= (pr-str (mk)) (pr-str (mk))))))

;; ── time-window refresh + append-only history ────────────────────────────────

(t/deftest refresh-appends-history-and-never-mutates-previous
  (let [first-obs (co/refresh-observation nil
                    {:frame frame
                     :units [(measured-unit "u1")]
                     :observed-at "2026-03-01T00:00:00Z"})
        prev-prstr (pr-str first-obs)
        second-obs (co/refresh-observation first-obs
                     {:frame frame
                      :units [(measured-unit "u1")
                              (measured-unit "u2b"
                               :discipline "0612-database"
                               :jurisdiction "US" :language "en"
                               :source "official-dataset-repository"
                               :year "2025" :identifier-class :ror)]
                      :observed-at "2026-09-05T00:00:00Z"})]
    ;; the refresh grew measured units 1 -> 2
    (t/is (= 2 (get-in second-obs [:counts :measured])))
    ;; append-only: history grows by exactly one entry, prior entry preserved
    (t/is (= 2 (count (:refresh-history second-obs))))
    (t/is (true? (get-in second-obs [:refresh-history 0 :initial])))
    (t/is (= "2026-09-05T00:00:00Z"
             (get-in second-obs [:refresh-history 1 :refreshed-at])))
    (t/is (= "2026-03-01T00:00:00Z"
             (get-in second-obs [:refresh-history 1 :prior-observed-at])))
    ;; previous observation is untouched
    (t/is (= prev-prstr (pr-str first-obs)))))

;; ── Hyakka proposal / readback ───────────────────────────────────────────────

(t/deftest hyakka-proposal-carries-guards-and-readback-round-trips
  (let [obs (co/build-observation
             {:frame frame
              :units [(measured-unit "u1") (measured-unit "u1b")]
              :observed-at "2026-09-05T00:00:00Z"})
        p (co/->hyakka-proposal obs)]
    (t/is (some? p))
    (t/is (= "coverage-observation" (:claim/type p)))
    (t/is (= "coverage-observation/v1" (:claim/contract p)))
    (t/is (= 1 (get-in p [:claim/counts :measured])))
    ;; unmeasured units are COUNTED but never exported as data
    (t/is (= 2 (:claim/unmeasured-count p)))
    (t/is (nil? (:claim/unmeasured-units p)))
    (t/is (every? #(true? (get p %))
                  [:claim/no-completeness :claim/no-imputation
                   :claim/no-ranking :claim/no-causal-claim]))
    (t/is (= {:ok true :contract "coverage-observation/v1"}
             (select-keys (co/readback-proposal p) [:ok :contract])))))

(t/deftest readback-rejects-tampered-proposals
  (let [obs (co/build-observation
             {:frame frame :units [(measured-unit "u1")]
              :observed-at "2026-09-05T00:00:00Z"})
        p (co/->hyakka-proposal obs)]
    ;; strip provenance
    (let [r (co/readback-proposal (dissoc p :observed-at))]
      (t/is (false? (:ok r)))
      (t/is (some #(re-find #"missing-field:observed-at" %) (:errors r))))
    ;; swap the contract identity
    (t/is (false? (:ok (co/readback-proposal
                        (assoc p :claim/contract "coverage-observation/v2")))))
    ;; strip a safety guard — a completeness claim must not survive readback
    (t/is (false? (:ok (co/readback-proposal (assoc p :claim/no-completeness false)))))
    (t/is (false? (:ok (co/readback-proposal (assoc p :claim/no-imputation false)))))))
