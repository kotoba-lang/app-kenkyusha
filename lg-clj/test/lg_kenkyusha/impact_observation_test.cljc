(ns lg-kenkyusha.impact-observation-test
  "Deterministic fixtures for the impact-observation contract — no network, no
  LLM. Covers the run's required scenarios: time-window refresh, bias/missing-
  data flags, provenance admission, and Hyakka proposal/readback. Health alone
  is NOT asserted as impact — retraction and negative evidence are preserved."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [lg-kenkyusha.impact-observation :as io]))

(def win-1 {:from "2025-09-01" :to "2026-02-28"})
(def win-2 {:from "2026-03-01" :to "2026-08-31"})

(defn good-signal
  [id & over]
  (merge {:signal-id id
          :source-url (str "https://doi.org/10.0000/" id)
          :source-class :citation-registry
          :source-language "en"
          :observed-at "2025-10-01"
          :content-hash (str "sha256-" id)
          :polarity :positive}
         (apply hash-map over)))

;; ── provenance admission ─────────────────────────────────────────────────────

(t/deftest provenance-gate-admits-and-preserves-missingness
  (let [signals [(good-signal "s1")
                 (good-signal "s2" :source-url "")            ; missing field
                 (good-signal "s3" :content-hash nil)         ; missing field
                 (good-signal "s4" :source-class :search-snippet)] ; forbidden class
        {:keys [admitted excluded]} (io/normalize-signals signals win-1)]
    (t/is (= ["s1"] (map :signal-id admitted)))
    (t/is (= #{[:missing-provenance [:source-url]]
               [:missing-provenance [:content-hash]]
               [:source-class-not-allowed [:search-snippet]]}
             (set (map (juxt :reason :fields) excluded))))
    (t/is (= 3 (count excluded)))))

(t/deftest out-of-window-signals-are-excluded-not-bucketed-in
  (let [signals [(good-signal "s1" :observed-at "2026-05-01")   ; in win-2 only
                 (good-signal "s2" :observed-at "2024-01-01")]  ; before any window
        {:keys [admitted excluded]} (io/normalize-signals signals win-1)]
    (t/is (empty? admitted))
    (t/is (= (conj (vec (map (juxt :reason :fields) excluded))
                   (let [{:keys [admitted]}
                         (io/normalize-signals signals win-2)]
                     [:admitted-in-win-2 (mapv :signal-id admitted)]))
             [[:outside-window [(:from win-1) (:to win-1)]]
              [:outside-window [(:from win-1) (:to win-1)]]
              [:admitted-in-win-2 ["s1"]]]))))

;; ── derived observation ──────────────────────────────────────────────────────

(t/deftest observation-tallies-without-netting-or-ranking
  (let [obs (io/build-observation
             {:dimension :scholarly-citation
              :window win-1
              :observed-at "2026-02-28T00:00:00Z"
              :signals [(good-signal "s1")
                        (good-signal "s2" :polarity :negative)
                        (good-signal "s3" :polarity :retraction)]})]
    (t/is (= "impact-observation/v1" (:contract obs)))
    (t/is (= 3 (get-in obs [:counts :measured])))
    ;; polarities are additive tallies — never netted, retractions never removed
    (t/is (= {:measured 3 :positive 1 :negative 1 :retraction 1} (:counts obs)))
    (t/is (contains? (:flags obs) :retraction-present))
    ;; hard boundaries: no ranking, no causal claims, empty claims, nil ranking
    (t/is (and (:ranking-forbidden obs) (:causal-claims-forbidden obs)))
    (t/is (nil? (:ranking obs)))
    (t/is (empty? (:claims obs)))))

(t/deftest determinism-same-inputs-byte-identical-observation
  (let [mk #(io/build-observation {:dimension :policy-citation
                                   :window win-1
                                   :observed-at "2026-02-28T00:00:00Z"
                                   :signals [(good-signal "s1") (good-signal "s2")]})]
    (t/is (= (pr-str (mk)) (pr-str (mk))))))

(t/deftest missing-data-is-unmeasured-not-imputed
  (let [obs (io/build-observation {:dimension :replication
                                   :window win-1
                                   :observed-at "2026-02-28T00:00:00Z"
                                   :signals [(good-signal "bad" :source-url "")]})]
    (t/is (zero? (get-in obs [:counts :measured])))
    (t/is (contains? (:flags obs) :missing-is-unmeasured))
    (t/is (contains? (:flags obs) :excluded-signals-present))
    (t/is (= 1 (get-in obs [:coverage :missing-provenance])))
    (t/is (= :partial (get-in obs [:coverage :coverage-claim])))
    ;; and no Hyakka proposal is fabricated from absence
    (t/is (nil? (io/->hyakka-proposal obs)))))

(t/deftest single-source-dependency-flagged
  (let [obs (io/build-observation
             {:dimension :standard-adoption
              :window win-1
              :observed-at "2026-02-28T00:00:00Z"
              :signals [(good-signal "s1" :source-url "https://example.org/x")
                        (good-signal "s2" :source-url "https://example.org/x")]})]
    (t/is (= 2 (get-in obs [:counts :measured])))
    (t/is (contains? (:flags obs) :single-source-dependency))))

(t/deftest one-dimension-per-observation-guard
  (t/is (thrown? Throwable
                 (io/build-observation {:dimension :not-a-dimension
                                        :window win-1
                                        :observed-at "2026-02-28"
                                        :signals []}))))

;; ── time-window refresh + append-only history ────────────────────────────────

(t/deftest refresh-appends-history-and-never-mutates-previous
  (let [first-obs (io/refresh-observation nil
                    {:dimension :scholarly-citation
                     :window win-1
                     :observed-at "2026-02-28T00:00:00Z"
                     :signals [(good-signal "s1")]})
        prev-prstr (pr-str first-obs)
        second-obs (io/refresh-observation first-obs
                     {:dimension :scholarly-citation
                      :window win-2
                      :observed-at "2026-08-31T00:00:00Z"
                      :signals [(good-signal "s4" :observed-at "2026-05-01")]})]
    ;; the refresh targets the NEW window, not the old one
    (t/is (= (:from win-2) (get-in second-obs [:window :from])))
    (t/is (= 1 (get-in second-obs [:counts :measured])))
    ;; append-only: history grows by exactly one entry, prior entry preserved
    (t/is (= 2 (count (:refresh-history second-obs))))
    (t/is (true? (get-in second-obs [:refresh-history 0 :initial])))
    (t/is (= "2026-08-31T00:00:00Z" (get-in second-obs [:refresh-history 1 :refreshed-at])))
    (t/is (= (:window first-obs) (get-in second-obs [:refresh-history 1 :from-window])))
    ;; previous observation is untouched
    (t/is (= prev-prstr (pr-str first-obs)))))

;; ── Hyakka proposal / readback ───────────────────────────────────────────────

(t/deftest hyakka-proposal-carries-provenance-and-guards
  (let [obs (io/build-observation
             {:dimension :scholarly-citation
              :window win-1
              :observed-at "2026-02-28T00:00:00Z"
              :signals [(good-signal "s1") (good-signal "s2")]})
        p (io/->hyakka-proposal obs)]
    (t/is (some? p))
    (t/is (= "impact-observation" (:claim/type p)))
    (t/is (= "impact-observation/v1" (:claim/contract p)))
    (t/is (= "scholarly-citation" (:claim/dimension p)))
    (t/is (str/starts-with? (:source-url p) "https://doi.org/"))
    (t/is (= "2026-02-28T00:00:00Z" (:observed-at p)))
    (t/is (true? (:claim/no-ranking p)))
    (t/is (true? (:claim/no-causal-claim p)))
    ;; readback round-trip accepts the well-formed proposal
    (t/is (= {:ok true :contract "impact-observation/v1"
              :dimension "scholarly-citation"}
             (select-keys (io/readback-proposal p) [:ok :contract :dimension])))))

(t/deftest readback-rejects-tampered-proposals
  (let [obs (io/build-observation
             {:dimension :patent-citation
              :window win-1
              :observed-at "2026-02-28T00:00:00Z"
              :signals [(good-signal "s1")]})
        p (io/->hyakka-proposal obs)]
    ;; strip provenance
    (let [r (io/readback-proposal (dissoc p :observed-at :source-url))]
      (t/is (false? (:ok r)))
      (t/is (some #(re-find #"missing-field:observed-at" %) (:errors r))))
    ;; swap the contract identity
    (t/is (false? (:ok (io/readback-proposal (assoc p :claim/contract "impact-observation/v2")))))
    ;; remove a safety guard
    (t/is (false? (:ok (io/readback-proposal (assoc p :claim/no-ranking false)))))))

(t/deftest citation-is-not-support-correlation-is-not-causation
  ;; the envelope cannot express support or causation even if a caller tries:
  ;; counts only record that a signal was observed, with polarity preserved.
  (let [obs (io/build-observation
             {:dimension :scholarly-citation
              :window win-1
              :observed-at "2026-02-28T00:00:00Z"
              :signals [(good-signal "s1" :polarity :positive)
                        (good-signal "s2" :polarity :negative)]})]
    (t/is (= 1 (get-in obs [:counts :positive])))
    (t/is (= 1 (get-in obs [:counts :negative])))
    (t/is (empty? (:claims obs)))
    (t/is (nil? (get obs :cause)))
    (t/is (nil? (get obs :endorsement)))
    (t/is (nil? (get obs :rankings)))))
