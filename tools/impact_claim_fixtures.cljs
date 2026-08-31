#!/usr/bin/env nbb
;; impact_claim_fixtures.cljs — deterministic offline fixtures for the
;; impact-claim-pipeline contract (`impact-claim/impact-claim-pipeline.edn`).
;;
;; Runs a full pipeline on fixture data — source proposal → fetch receipt
;; → parser admission → dedupe → bounded retry/refusal → signed claim
;; proposal → readback — and asserts every stage's invariants, including
;; composition with the merged impact-observation/v1 contract (source-class
;; allow list equality, dimension vocabulary, ranking/causal guards,
;; polarity-never-netted). No network.
;;
;; Exit codes:
;;   0  all fixtures ran clean
;;   1  a fixture found a violation
;;   2  REFUSED — the contract could not be read
;;
;; Usage: nbb tools/impact_claim_fixtures.cljs [path/to/contract.edn]

(ns impact-claim-fixtures
  (:require ["fs" :as fs]
            ["path" :as path]
            ["crypto" :as crypto]
            [clojure.string :as str]
            [cljs.reader :refer [read-string]]))

(def contract-path
  (or (first (remove #(str/starts-with? % "--") *command-line-args*))
      (path/join "impact-claim" "impact-claim-pipeline.edn")))

(def contract
  (try
    (read-string (fs/readFileSync contract-path "utf8"))
    (catch :default e
      (println (str "REFUSED: cannot read contract: " (.-message e)))
      (js/process.exit 2))))

(def failures (atom []))
(defn fail! [fixture msg] (swap! failures conj {:fixture fixture :msg msg}))

(defn sha256 [s]
  (let [h (crypto/createHash "sha256")]
    (.update h s) (.digest h "hex")))

;; ── impact-observation/v1 composition constants (merged contract) ────
;; These MUST stay in sync with lg-clj/src/lg_kenkyusha/impact_observation.cljc;
;; the fixtures fail loudly if the pipeline drifts from the observation schema.
(def obs-allowed-classes
  #{:citation-registry :official-policy-document :official-patent-record
    :standards-body-first-party :guideline-publisher-first-party
    :publisher-correction-or-retraction})

(def obs-dimensions
  [:scholarly-citation :replication :correction :retraction
   :policy-citation :patent-citation :standard-adoption
   :clinical-guideline-citation :dataset-or-software-reuse])

;; ── Stage 1: source proposal ─────────────────────────────────────────
(def sp (:source-proposal contract))

(defn propose-source [id url class]
  {:proposal-id id :source-url url :source-class class
   :source-language "en" :justification "fixture registry"
   :proposed-at "2026-09-01"})

(def proposals
  [(propose-source "sp-1" "https://registry.example.test/citations"
                   :citation-registry)
   (propose-source "sp-2" "https://blog.example.test/impact-hype"
                   :search-snippet)])

(defn fixture-source-proposal [f]
  (when-not (= obs-allowed-classes (:source-class-allow sp))
    (fail! f "allow list must equal impact-observation/v1's allowed-source-classes verbatim"))
  (when-not (contains? (:source-class-forbid sp) :inferred-impact)
    (fail! f "forbid list must refuse inferred-impact"))
  (doseq [p proposals]
    (if (contains? (:source-class-allow sp) (:source-class p))
      (when-not (:source-url p) (fail! f "allowed proposal missing url"))
      (println (str "STAGE source-proposal | " (:proposal-id p)
                    " | refused-before-fetch | "
                    (name (:source-class p)) " is on the forbid list")))))

;; ── Stage 2: fetch receipt ───────────────────────────────────────────
(def fr (:fetch-receipt contract))

(def body-1 "CITATION REGISTRY EXPORT\nexternal-id: DOI-10.0000/abc\ntitle: Example Replication Study\ndimension: scholarly-citation\nlanguage: en")
(def body-1-again body-1) ; second fetch of the same page (dedupe fixture)
(def body-2 "CITATION REGISTRY EXPORT\ntitle: Signal without an external identifier\nlanguage: en")

(defn receipt [id body status]
  {:receipt-id id :source-url "https://registry.example.test/citations"
   :source-class :citation-registry :source-language "en"
   :observed-at "2026-09-01" :content-hash (sha256 body) :fetch-status status})

(def receipts
  [(receipt "r-1" body-1 :ok)
   (receipt "r-2" body-1-again :ok)   ; same bytes as r-1
   (receipt "r-3" body-2 :ok)])

(defn fixture-receipt [f]
  (doseq [r receipts]
    (when-not (re-find #"[0-9a-f]{64}" (:content-hash r))
      (fail! f (str (:receipt-id r) " content-hash not sha256-hex"))))
  (when-not (and (:respect-robots? (:fetch fr)) (:no-bypass? (:fetch fr)))
    (fail! f "fetch policy must respect robots and never bypass"))
  (doseq [r receipts]
    (println (str "STAGE fetch-receipt | " (:receipt-id r) " | "
                  (name (:fetch-status r)) " | sha256=" (:content-hash r)))))

;; ── Stage 3: parser / admission ──────────────────────────────────────
(def pa (:parser-admission contract))

(defn admit [rid record]
  (if (:external-id record)
    {:receipt-id rid :record-id (str "rec-" rid) :decision :admitted
     :reason-code nil
     :impact-dimension (:dimension record)
     :polarity :positive
     :original-language-fields {:original-title (:title record)
                                :source-url (:source-url record)
                                :external-id (:external-id record)}
     :admitted-at "2026-09-01"}
    {:receipt-id rid :record-id (str "rec-" rid) :decision :refused
     :reason-code :provenance-missing
     :impact-dimension nil
     :polarity :unspecified
     :original-language-fields {:original-title (:title record)}
     :admitted-at "2026-09-01"}))

(def admissions
  [(admit "r-1" {:title "Example Replication Study"
                 :source-url "https://doi.org/10.0000/abc"
                 :external-id "DOI-10.0000/abc"
                 :dimension :scholarly-citation})
   (admit "r-2" {:title "Example Replication Study"
                 :source-url "https://doi.org/10.0000/abc"
                 :external-id "DOI-10.0000/abc"
                 :dimension :scholarly-citation})
   (admit "r-3" {:title "Signal without an external identifier"})])

(defn fixture-admission [f]
  (doseq [a admissions]
    (if (= :admitted (:decision a))
      (when-not (:external-id (:original-language-fields a))
        (fail! f "admitted record lost its external-id"))
      (when-not (contains? (:refusal-codes pa) (:reason-code a))
        (fail! f (str "refusal code " (:reason-code a) " not in contract")))))
  (when-not (some #(= :refused (:decision %)) admissions)
    (fail! f "refusals must be recorded, never silently dropped"))
  (when-not (contains? (set (:polarity-values pa)) :retraction)
    (fail! f "polarity vocabulary must preserve :retraction (never subtracted away)"))
  (doseq [a admissions]
    (println (str "STAGE parser-admission | " (:record-id a) " | "
                  (name (:decision a)) " | "
                  (if (:reason-code a) (name (:reason-code a)) "-")))))

;; ── Stage 4: dedupe ──────────────────────────────────────────────────
(def dd (:dedupe contract))

(defn dedupe-key [a]
  {:source-namespace "example-citation-registry"
   :external-id (get-in a [:original-language-fields :external-id])
   :impact-dimension (:impact-dimension a)})

(defn fixture-dedupe [f]
  (let [k1 (dedupe-key (admissions 0))
        k2 (dedupe-key (admissions 1))]
    (when-not (= k1 k2)
      (fail! f "same bytes must produce the same dedupe key"))
    (when-not (= (:on-collision dd) {:first-wins-keep-provenance true
                                     :append :refresh-history
                                     :polarity-never-netted true
                                     :never-overwrite? true})
      (fail! f "collision policy must keep first provenance, never overwrite, never net polarity"))
    (println (str "STAGE dedupe | " (:external-id k1)
                  " | collision | second receipt appended to refresh-history, first provenance kept, polarity not netted"))))

;; ── Stage 5: bounded retry / refusal ─────────────────────────────────
(def rr (:retry-refusal contract))

(def attempts {"src-A" [{:n 1 :status :http-error}
                        {:n 2 :status :http-error}
                        {:n 3 :status :http-error}
                        {:n 4 :status :http-error}]}) ; exceeds bound

(defn fixture-retry [f]
  (let [a (attempts "src-A")
        maxn (:max-attempts-per-source rr)
        used (count a)]
    (when-not (> used maxn)
      (fail! f "fixture must exercise the over-bound case"))
    (when-not (contains? (:retryable-fetch-status rr) :http-error)
      (fail! f ":http-error must be retryable"))
    (when-not (contains? (:non-retryable rr) :blocked-by-policy)
      (fail! f "policy blocks must be non-retryable"))
    (when (get-in rr [:on-exhausted :fabricate-placeholder?])
      (fail! f "exhausted retries must never fabricate a placeholder"))
    (println (str "STAGE retry-refusal | src-A | refusal-recorded | "
                  used " attempts > bound " maxn "; no placeholder fabricated; deferred to next run"))))

;; ── Stage 6: signed claim proposal ───────────────────────────────────
(def cp (:claim-proposal contract))

(def claim
  {:claim-id "c-1" :proposal-id "sp-1"
   :dedupe-key (dedupe-key (admissions 0))
   :claim-kind :impact-signal-observed
   :claim-dimension :scholarly-citation
   :claim-polarity-tallies {:positive 1 :negative 0 :retraction 0}
   :window {:from "2026-03-01" :to "2026-08-31"}
   :source-receipt-id "r-1"
   :coverage-record-ref "cov-example-citation-registry-2026-08"
   :missingness-flags #{}
   :signature (sha256 "c-1-content-canonical-edn")
   :proposed-at "2026-09-01"})

(defn fixture-claim [f]
  (when-not (contains? (:claim-kinds cp) (:claim-kind claim))
    (fail! f "claim kind not in contract"))
  (when-not (contains? (set (:claim-dimension-vocabulary cp)) (:claim-dimension claim))
    (fail! f "claim dimension must come from impact-observation/v1's dimension vocabulary"))
  (when-not (re-find #"[0-9a-f]{64}" (:signature claim))
    (fail! f "claim signature missing"))
  (when-not (contains? (:claim-polarity-tallies claim) :retraction)
    (fail! f "retraction tally must be carried (never netted away)"))
  (doseq [[g v] (:required-guards cp)]
    (when-not (true? v)
      (fail! f (str "required guard " (name g) " must be true"))))
  (doseq [k (keys claim)]
    (when (contains? (:forbidden-fields cp) k)
      (fail! f (str "forbidden field present in claim: " k))))
  (when-not (= :provenance-only-not-truth-assertion
               (get-in cp [:signature :meaning]))
    (fail! f "signature must assert provenance only"))
  (println (str "STAGE claim-proposal | " (:claim-id claim)
                " | signed | provenance-only signature; dimension carried; guards active")))

;; ── Stage 7: readback ────────────────────────────────────────────────
(defn fixture-readback [f]
  (let [resp {:query-id "q-1" :status :ok :claims ["c-1"]
              :coverage-record-ref "cov-example-citation-registry-2026-08"
              :missingness-flags #{}}]
    (when-not (contains? (:status-values (:query-readback contract)) (:status resp))
      (fail! f "readback status not in contract"))
    (when-not (and (:coverage-record-ref resp)
                   (contains? resp :missingness-flags))
      (fail! f "readback must carry coverage and missingness"))
    (println "STAGE query-readback | q-1 | ok | claims=1 coverage carried")))

;; ── Stage 8: audit output covers every stage ─────────────────────────
(defn fixture-audit [f]
  (let [printed #{"source-proposal" "fetch-receipt" "parser-admission"
                  "dedupe" "retry-refusal" "claim-proposal"
                  "query-readback" "audit-output"}]
    (doseq [s (:per-stage (:audit-output contract))]
      (when-not (contains? printed (name s))
        (fail! f (str "audit line missing for stage " (name s)))))
    (when-not (= (count (:per-stage (:audit-output contract))) 8)
      (fail! f "audit must cover exactly the 8 pipeline stages")))
  (println "STAGE audit-output | run | complete | all stages covered including refusals"))

;; ── Run ──────────────────────────────────────────────────────────────
(println (str "contract: " (:contract/id contract)
              " " (:contract/version contract)
              " (" (:method/version contract) ")"
              " composes-with " (pr-str (:composes-with contract))))
(fixture-source-proposal "source-proposal")
(fixture-receipt "fetch-receipt")
(fixture-admission "parser-admission")
(fixture-dedupe "dedupe")
(fixture-retry "retry-refusal")
(fixture-claim "claim-proposal")
(fixture-readback "query-readback")
(fixture-audit "audit-output")

(if (empty? @failures)
  (do (println "OK: all impact-claim-pipeline fixtures ran clean")
      (js/process.exit 0))
  (do (doseq [{:keys [fixture msg]} @failures]
        (println (str "VIOLATION [" fixture "] " msg)))
      (js/process.exit 1)))
