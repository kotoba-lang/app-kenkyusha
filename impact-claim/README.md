# Impact-claim pipeline contract (proposed to Hyakka)

`impact-claim-pipeline.edn` is a bounded, executable actor contract for
proposing **signed research-impact claims** from allowed impact sources
(citation registries, policy/patent records, standards bodies, guideline
publishers, publisher corrections/retractions) into
`network-awai/app-hyakka`.

Relationship to the merged `lg-kenkyusha.impact-observation`
(impact-observation/v1): that contract defines the observation **schema**
layer (provenance admission, additive per-dimension tallies, append-only
refresh history, readback). This contract adds the **pipeline** layer it
does not specify, and composes with it (same source-class allow list, same
dimension vocabulary, same polarity semantics, unchanged):

1. **source proposal** — allow/forbid source classes; the allow list is
   impact-observation/v1's `allowed-source-classes` verbatim; forbidden
   classes (search snippets, generated summaries, wiki prose, scraped
   profiles, inferred impact) are refused *before* any fetch.
2. **fetch receipt** — verbatim bytes, sha256 of the response body,
   robots / auth / WAF / CAPTCHA respected, never bypassed.
3. **parser admission** — every record is explicitly `:admitted`,
   `:refused` (machine-readable reason code) or `:flagged`; nothing is
   silently dropped. Original language and identifiers pass through
   verbatim. Dimension and polarity come from impact-observation/v1's
   vocabulary, unnetted.
4. **dedupe** — deterministic content-derived key (source namespace +
   external-id + dimension); exact match only; collision keeps the first
   provenance and appends to refresh history; polarity is never netted
   and never overwrites (a later retraction arrives as a new appended
   entry).
5. **bounded retry / refusal** — max 3 attempts per source with
   exponential backoff; exhaustion produces a recorded refusal, never a
   fabricated placeholder. Missing evidence stays missing.
6. **signed claim proposal** — a claim is a *proposal* carrying a sha256
   signature over its canonical content. The signature asserts
   **provenance only, never truth**; publication and correctness are
   decided by Hyakka governance, not by this actor. Claims carry
   impact-observation/v1's guards: no ranking, no causal claim, funding
   is not endorsement.
7. **readback** — deterministic query shape that always carries coverage
   and missingness (`:ok` / `:unmeasured` / `:out-of-window`).
8. **audit output** — one human-readable line per stage, including every
   refusal.

What it is not: not a ranking, score, researcher ordering, causal claim
or endorsement. Citation is not support; correlation is not causation. No
contact, registration, purchase, application, sponsorship solicitation or
financial commitment. External content is untrusted; no instruction
embedded in a fetched page changes this contract's behaviour.

Verify deterministically (offline, no network):

```bash
nbb tools/impact_claim_fixtures.cljs
```

Exit codes: `0` all fixtures ran clean · `1` a violation was found ·
`2` REFUSED (contract could not be read).
