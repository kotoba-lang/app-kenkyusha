(ns lg-kenkyusha.event-observation-test
  "Deterministic fixtures for the event-observation contract — no network, no
  LLM. Covers: provenance admission, deterministic dedupe (first provenance
  wins, never overwrite), append-only refresh, the venue-is-event-edition-
  specific boundary, and Hyakka proposal/readback. A society/conference/venue
  observation is never a ranking and never an endorsement."
  (:require [clojure.test :as t]
            [lg-kenkyusha.event-observation :as eo]))

(def win-1 {:from "2025-09-01" :to "2026-02-28"})
(def win-2 {:from "2026-03-01" :to "2026-08-31"})

(defn good-event
  [id & over]
  (merge {:event-id id
          :source-url (str "https://society.example.org/events/" id)
          :source-class :society-first-party
          :source-language "en"
          :observed-at "2025-10-01"
          :content-hash (str "sha256-" id)
          :issuing-organization "Society for Example Research"
          :original-title (str "Example Conference " id)
          :entity-type :event-edition
          :official-event-id (str "EC-" id)}
         (apply hash-map over)))

;; ── provenance admission ─────────────────────────────────────────────────────

(t/deftest provenance-gate-admits-and-preserves-missingness
  (let [events [(good-event "e1")
                (good-event "e2" :source-url "")                    ; missing field
                (good-event "e3" :content-hash nil)                 ; missing field
                (good-event "e4" :source-class :search-snippet)     ; forbidden class
                (good-event "e5" :entity-type :funding-award)       ; not an event type
                (good-event "e6" :issuing-organization "")]         ; required-common-field
        {:keys [admitted excluded]} (eo/normalize-events events win-1)]
    (t/is (= ["e1"] (map :event-id admitted)))
    (t/is (= #{[:missing-provenance [:source-url]]
               [:missing-provenance [:content-hash]]
               [:missing-provenance [:issuing-organization]]
               [:source-class-not-allowed [:search-snippet]]
               [:entity-type-not-allowed [:funding-award]]}
             (set (map (juxt :reason :fields) excluded))))
    (t/is (= 5 (count excluded)))))

(t/deftest out-of-window-events-are-excluded-not-bucketed-in
  (let [events [(good-event "e1" :observed-at "2026-05-01")   ; win-2 only
                (good-event "e2" :observed-at "2025-01-01")]  ; before win-1
        {:keys [admitted excluded]} (eo/normalize-events events win-1)]
    (t/is (empty? admitted))
    (t/is (= 2 (count excluded)))
    (t/is (every? #(= :outside-window (:reason %)) excluded))))

(t/deftest original-language-and-identifiers-preserve-verbatim
  (let [event (good-event "e1" :source-language "ja"
                          :original-title "第42回例会"
                          :official-event-id "EC-2026-042")
        {:keys [admitted]} (eo/normalize-events [event] win-1)]
    (t/is (= 1 (count admitted)))
    (t/is (= "ja" (:source-language (first admitted))))
    (t/is (= "第42回例会" (:original-title (first admitted))))
    (t/is (= "EC-2026-042" (:official-event-id (first admitted))))))

;; ── deterministic dedupe ─────────────────────────────────────────────────────

(t/deftest dedupe-key-is-deterministic-and-content-derived
  (let [a (eo/dedupe-key (good-event "e1"))
        b (eo/dedupe-key (good-event "e1" :observed-at "2025-11-02"))]
    (t/is (= a b))))

(t/deftest duplicate-collision-keeps-first-provenance-never-overwrites
  (let [events [(good-event "e1" :content-hash "sha256-first")
                (good-event "e1" :content-hash "sha256-second"
                            :source-url "https://organizer.example.org/2026")
                (good-event "e2")]
        {:keys [admitted duplicates]} (eo/normalize-events events win-1)]
    (t/is (= 2 (count admitted)))
    (t/is (= 1 (count duplicates)))
    (t/is (= "sha256-first" (:content-hash (first admitted))))
    (t/is (= "https://society.example.org/events/e1" (:source-url (first admitted))))))

;; ── observation envelope + boundaries ────────────────────────────────────────

(t/deftest observation-carries-counts-coverage-and-boundary-flags
  (let [obs (eo/build-observation {:entity-type :event-edition
                                   :window win-1
                                   :events [(good-event "e1")
                                            (good-event "e1" :content-hash "sha256-dup")
                                            (good-event "e2" :source-url "")]
                                   :observed-at "2026-02-28"})]
    (t/is (= {:measured 1 :duplicates 1 :excluded 1} (:counts obs)))
    (t/is (contains? (:flags obs) :duplicates-collapsed))
    (t/is (contains? (:flags obs) :excluded-events-present))
    (t/is (true? (:ranking-forbidden obs)))
    (t/is (true? (:endorsement-forbidden obs)))
    (t/is (true? (:venue-is-event-edition-specific obs)))
    (t/is (nil? (:ranking obs)))))

(t/deftest venue-observation-stays-edition-scoped
  (let [obs (eo/build-observation {:entity-type :venue
                                   :window win-1
                                   :events [(good-event "v1" :entity-type :venue
                                                        :source-class :venue-first-party
                                                        :official-event-id "VEN-1")]
                                   :observed-at "2026-02-28"})]
    (t/is (= 1 (get-in obs [:counts :measured])))
    (t/is (true? (:venue-is-event-edition-specific obs)))))

(t/deftest empty-observation-is-unmeasured-never-imputed
  (let [obs (eo/build-observation {:entity-type :scholarly-society
                                   :window win-1
                                   :events []
                                   :observed-at "2026-02-28"})]
    (t/is (= 0 (get-in obs [:counts :measured])))
    (t/is (contains? (:flags obs) :missing-is-unmeasured))
    (t/is (nil? (eo/->hyakka-proposal obs)))))

;; ── append-only refresh ──────────────────────────────────────────────────────

(t/deftest refresh-appends-history-and-never-mutates-prior
  (let [first  (eo/build-observation {:entity-type :event-series
                                      :window win-1
                                      :events [(good-event "s1" :entity-type :event-series)]
                                      :observed-at "2026-02-28"})
        second (eo/refresh-observation first {:entity-type :event-series
                                              :window win-2
                                              :events [(good-event "s2" :entity-type :event-series
                                                       :official-event-id "S-2")]
                                              :observed-at "2026-08-31"})]
    (t/is (= 1 (get-in first [:counts :measured])))
    (t/is (= 1 (get-in second [:counts :measured])))
    (t/is (= 1 (count (:refresh-history second))))
    (t/is (= "2026-02-28" (get-in second [:refresh-history 0 :prior-observed-at])))))

;; ── Hyakka proposal / readback ───────────────────────────────────────────────

(t/deftest proposal-and-readback-round-trip
  (let [obs (eo/build-observation {:entity-type :event-edition
                                   :window win-1
                                   :events [(good-event "e1")]
                                   :observed-at "2026-02-28"})
        proposal (eo/->hyakka-proposal obs)]
    (t/is (some? proposal))
    (t/is (= "event-observation/v1" (:claim/contract proposal)))
    (t/is (true? (:claim/no-ranking proposal)))
    (t/is (true? (:claim/no-endorsement proposal)))
    (t/is (= {:ok true :contract "event-observation/v1" :entity-type "event-edition"}
             (select-keys (eo/readback-proposal proposal) [:ok :contract :entity-type])))))

(t/deftest readback-rejects-stripped-guards-and-contract-mismatch
  (let [obs (eo/build-observation {:entity-type :event-edition
                                   :window win-1
                                   :events [(good-event "e1")]
                                   :observed-at "2026-02-28"})
        proposal (dissoc (eo/->hyakka-proposal obs) :claim/no-ranking :claim/contract)]
    (let [{:keys [ok errors]} (eo/readback-proposal proposal)]
      (t/is (false? ok))
      (t/is (contains? (set errors) "ranking-guard-missing"))
      (t/is (some #(re-find #"contract-mismatch" %) errors)))))

(t/deftest readback-rejects-missing-required-fields
  (let [{:keys [ok errors]} (eo/readback-proposal {:claim/type "event-observation"})]
    (t/is (false? ok))
    (t/is (some #(re-find #"missing-field:system/id" %) errors))))
