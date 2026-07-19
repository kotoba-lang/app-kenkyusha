> **DEPRECATED**: Actor migrated to `20-actors/kenkyusha/actor-manifest.jsonld` (T1 MCP-Compose). This project wasm/*/src/app.ts is retained as T3 fallback only.

# etzhayyim-project-kenkyusha

**kenkyusha.etzhayyim.com** — AI 研究者 Actor。既存学術分野の DID knowledge graph から未解明 research frontier を特定し、仮説生成・検証・論文走査を自律実行する。

## Domain Model

### Core Concept

既存 knowledge source (bunken / isbn / issn / hanrei / intel) の citation graph + content gap を Murakumo LLM で分析し、**Research Frontier** (未解明領域) を自動検出。各 Frontier に AI 研究者 Actor を割り当て、仮説生成 → 文献エビデンス収集 → 検証 → social post の shinka cycle を回す。

### Academic Discipline Taxonomy

UNESCO ISCED-F 2013 (Fields of Education and Training) を分類骨格として採用:

| Level | 桁数 | 数 | 例 |
|---|---|---|---|
| Broad field | 2桁 | 11 | 05 Natural sciences, 06 ICT |
| Narrow field | 3桁 | 29 | 051 Biological sciences, 061 ICT |
| Detailed field | 4桁 | 80+ | 0511 Biology, 0612 Database/network |

各 Detailed field = 1 discipline DID。Broad/Narrow は graph edge で階層表現。

### Research Frontier Detection

Frontier = 以下のいずれかで検出:

1. **Citation Gap**: bunken の被引用 cluster で引用先が存在しない領域
2. **Temporal Decay**: 高被引用だが近年 (5年) 新規論文がない分野
3. **Cross-Discipline Void**: 2 discipline 間の intersection で文献密度が低い領域
4. **Legal-Science Gap**: hanrei で科学的根拠が争点だが学術的 consensus がない領域
5. **LLM Uncertainty**: Murakumo LLM が特定質問に高 entropy で回答する領域

## Architecture

- **Runtime**: T1 MCP-Compose (PDS Shared Executor)
- **Primary DID**: `did:web:kenkyusha.etzhayyim.com`
- **nanoid**: `kk8r3n5v`
- **UI**: `yoro` (Protocol Canvas feed)
- **Classification**: `internal`
- **Worker deploy**: 不要 (graph MERGE のみ)
- **Build**: 不要 (宣言的 manifest)

### T1 MCP-Compose 設計根拠

全パイプラインが 12 MCP primitives で表現可能:
- `graph.query` — SQL read (citation gap 検出, evidence 検索)
- `graph.write` — SQL write (frontier/hypothesis/evidence MERGE)
- `agent.chat` — Murakumo LLM (仮説生成, evidence 評価, ISCED-F 分類)
- `browser.fetch` — site.etzhayyim.com gateway (外部学術 DB)
- `derive:social` — PDS commit pipeline (social post 自動導出)
- `agent.invoke` — cross-actor (bunken/hanrei/isbn への cross-app query)
- `graph.vectorSearch` — semantic frontier discovery

Custom code 不要 → T1。`$-variable interpolation` で step 間データ受け渡し。

### etzhayyim mitama による命の吹き込み

```bash
etzhayyim mitama -dir 60-apps/etzhayyim-project-kenkyusha
# = POST /xrpc/com.etzhayyim.actor.registerManifest (actor-manifest.jsonld → graph MERGE)
# → PDS Shared Executor が cron / subscribeRepos で自動実行開始
# → Worker deploy 不要、wrangler 不要、build 不要
```

## Multi-DID Structure

| DID pattern | 用途 | 数 |
|---|---|---|
| `did:web:kenkyusha.etzhayyim.com` | Primary actor | 1 |
| `did:web:kenkyusha.etzhayyim.com:discipline:{isced4}` | ISCED-F Detailed field | ~80 |
| `did:web:kenkyusha.etzhayyim.com:frontier:{djb2Hash}` | Research frontier | N (動的生成) |
| `did:web:kenkyusha.etzhayyim.com:researcher:{cohortHash}` | Researcher cohort | N (動的生成) |

Total path-based DIDs: ~80 (discipline) + N (frontier + researcher cohort)

## Cohort Design (3 types)

### 1. Discipline Cohort — `"d"` prefix

学術分野の特性による cohort。ISCED-F detailed field ごとに 1 DID。

Dimensions (6):
- `broadField`: ISCED-F 2桁 (00-99)
- `narrowField`: ISCED-F 3桁 (000-999)
- `detailedField`: ISCED-F 4桁 (0000-9999)
- `paradigm`: `theoretical` | `experimental` | `computational` | `clinical` | `applied` | `mixed`
- `maturity`: `nascent` | `emerging` | `established` | `mature` | `declining`
- `interdisciplinarity`: `mono` | `multi` | `inter` | `trans`

### 2. Frontier Cohort — `"r"` prefix (research)

未解明領域の cohort。動的に生成。

Dimensions (5):
- `detectionMethod`: `citationGap` | `temporalDecay` | `crossDisciplineVoid` | `legalScienceGap` | `llmUncertainty`
- `primaryDiscipline`: ISCED-F 4桁
- `urgency`: `critical` | `high` | `medium` | `low`
- `evidenceLevel`: `none` | `anecdotal` | `observational` | `experimental` | `metaAnalysis`
- `consensusLevel`: `none` | `disputed` | `emerging` | `partial` | `strong`

### 3. Researcher Cohort — `"k"` prefix (kenkyusha)

AI 研究者 actor の行動特性 cohort。

Dimensions (5):
- `specialization`: ISCED-F 4桁
- `approach`: `theorist` | `experimentalist` | `computationalist` | `reviewer` | `synthesizer`
- `productivity`: `prolific` | `steady` | `focused` | `dormant`
- `collaboration`: `solo` | `smallTeam` | `largeCommunity` | `interdisciplinary`
- `impactTier`: `foundational` | `applied` | `incremental` | `survey`

## Graph Schema

### Nodes

```sql
(:KenkyushaDiscipline {
  id, did, isced4, iscedBroad, iscedNarrow,
  nameEn, nameJa, paradigm, maturity, interdisciplinarity,
  cohortHash, dimensionsJson,
  publicationCount, citationCount, frontierCount,
  orgId, userId, actorId, createdAt
})

(:KenkyushaFrontier {
  id, did, title, description,
  detectionMethod, primaryDiscipline, secondaryDisciplines,
  urgency, evidenceLevel, consensusLevel,
  cohortHash, dimensionsJson,
  hypothesisCount, evidenceCount, status,
  detectedAt, lastAnalyzedAt,
  orgId, userId, actorId, createdAt
})

(:KenkyushaHypothesis {
  id, frontierId, statement, rationale,
  supportingEvidence, contradictingEvidence,
  confidenceScore, llmModel,
  status, -- proposed | investigating | supported | refuted | inconclusive
  orgId, userId, actorId, createdAt
})

(:KenkyushaEvidence {
  id, frontierId, hypothesisId,
  sourceType, -- bunken | hanrei | isbn | issn | intel | external
  sourceDid, sourceUri,
  relevanceScore, evidenceType, -- supports | contradicts | neutral
  extractedClaim, llmModel,
  orgId, userId, actorId, createdAt
})

(:KenkyushaResearcherCohort {
  cohortHash, did, dimensionsJson,
  count, firstSeen, lastSeen,
  orgId, userId, actorId, createdAt
})
```

### Edges

```sql
(:KenkyushaDiscipline)-[:PARENT_OF]->(:KenkyushaDiscipline)        -- ISCED hierarchy
(:KenkyushaDiscipline)-[:INTERSECTS]->(:KenkyushaDiscipline)       -- cross-discipline link
(:KenkyushaFrontier)-[:BELONGS_TO]->(:KenkyushaDiscipline)         -- primary discipline
(:KenkyushaFrontier)-[:SPANS]->(:KenkyushaDiscipline)              -- secondary disciplines
(:KenkyushaHypothesis)-[:ADDRESSES]->(:KenkyushaFrontier)          -- hypothesis → frontier
(:KenkyushaEvidence)-[:SUPPORTS]->(:KenkyushaHypothesis)           -- evidence link
(:KenkyushaEvidence)-[:CONTRADICTS]->(:KenkyushaHypothesis)        -- evidence link
(:KenkyushaEvidence)-[:CITES]->(:Bunken)                           -- cross-app: literature
(:KenkyushaEvidence)-[:REFERENCES]->(:CaseRecord)                  -- cross-app: hanrei
(:KenkyushaFrontier)-[:DERIVED_FROM]->(:Bunken)                    -- citation gap source
(:KenkyushaResearcherCohort)-[:SPECIALIZES_IN]->(:KenkyushaDiscipline)
```

## XRPC NSIDs

### Commands (write)

| NSID | 説明 |
|---|---|
| `com.etzhayyim.apps.kenkyusha.seedDisciplines` | ISCED-F taxonomy seed (初回) |
| `com.etzhayyim.apps.kenkyusha.detectFrontiers` | bunken citation gap 分析 → frontier 生成 |
| `com.etzhayyim.apps.kenkyusha.generateHypothesis` | frontier に対する仮説生成 (Murakumo LLM) |
| `com.etzhayyim.apps.kenkyusha.collectEvidence` | bunken/hanrei/isbn から evidence 収集 |
| `com.etzhayyim.apps.kenkyusha.evaluateHypothesis` | evidence に基づく仮説評価 |
| `com.etzhayyim.apps.kenkyusha.registerDids` | discipline / frontier DID 登録 (chunked) |

### Queries (read)

| NSID | 説明 |
|---|---|
| `com.etzhayyim.apps.kenkyusha.listFrontiers` | frontier 一覧 (discipline / urgency filter) |
| `com.etzhayyim.apps.kenkyusha.getFrontier` | frontier 詳細 + hypotheses + evidence |
| `com.etzhayyim.apps.kenkyusha.listDisciplines` | ISCED-F discipline 一覧 |
| `com.etzhayyim.apps.kenkyusha.searchEvidence` | evidence 横断検索 |
| `com.etzhayyim.apps.kenkyusha.stats` | 全体統計 |
| `com.etzhayyim.apps.kenkyusha.coverageMap` | discipline × frontier coverage matrix |

## Cross-App Dependencies (Follow Sources)

| Source | 用途 |
|---|---|
| bunken.etzhayyim.com | Citation graph, 文献メタデータ, content gap 検出 |
| isbn.etzhayyim.com | 書籍識別, fulltext (PD), subject classification |
| issn.etzhayyim.com | 学術雑誌識別, linking |
| hanrei.etzhayyim.com | 法的根拠の科学的争点抽出 |
| intel.etzhayyim.com | OSINT/TECHINT 学術情報補完 |
| natural-person.etzhayyim.com | 研究者 cohort → natural person mapping |
| society6.etzhayyim.com | Kyu/Dan rank (研究者 well-becoming) |
| dojo.etzhayyim.com | Research readiness drill |

## Pipeline Schedule (T1 cron + subscribeRepos)

| Pipeline | Trigger | Primitives | 目的 |
|---|---|---|---|
| Citation Gap Detection | cron `0 0/6 * * *` | graph.query → agent.chat → graph.write → derive:social | bunken 被引用 gap から frontier 検出 |
| Hypothesis Generation | cron `0 3/6 * * *` | graph.query → agent.chat → graph.write → derive:social | stale frontier に仮説生成 |
| Evidence Evaluation | cron `0 1/6 * * *` | graph.query ×3 → agent.chat → graph.write → derive:social | bunken/hanrei evidence で仮説評価 |
| Cross-Discipline Void | cron `0 5/6 * * *` | graph.query → agent.chat → graph.write → derive:social | 学際空白地帯の frontier 検出 |
| Bunken Reactive | subscribeRepos `bunken.bunken` | graph.query → agent.chat → graph.write | 新規文献 → 既存 frontier の evidence 追加 |
| ISBN/ISSN Classify | subscribeRepos `isbn.book`, `issn.serial` | agent.chat → graph.write | 新規出版物の ISCED-F 分類 |
| Stats Query | xrpc `kenkyusha.stats` | graph.query ×5 | 統計 (discipline/frontier/hypothesis/evidence/coverage) |
| Seed Disciplines | xrpc `kenkyusha.seedDisciplines` | graph.query → agent.chat → graph.write → derive:social | ISCED-F taxonomy 初回 seed |

全 pipeline は PDS Shared Executor が `executePipeline()` で sequential 実行。OCEL 2.0 event log で全 step 追跡可能。

## Coverage Target

| Metric | Target |
|---|---|
| ISCED-F Detailed fields | 80+ disciplines covered |
| Frontiers per discipline | >= 3 active |
| Hypotheses per frontier | >= 2 |
| Evidence per hypothesis | >= 5 |
| Coverage η | → 1.0 (全 discipline に active frontier) |

## Files

```
60-apps/etzhayyim-project-kenkyusha/
├── CLAUDE.md                    ← this file
├── actor-manifest.jsonld        ← T1 MCP-Compose manifest (SSoT, graph MERGE で deploy)
└── wasm/etzhayyim-wasm-kenkyusha-kk8r3n5v/
    ├── src/app.ts               ← T3 fallback (T1 で不足する場合のみ)
    ├── wit/world.wit            ← WIT contract (design-time)
    ├── kotodama.jsonld          ← legacy config (T3 用)
    ├── package.json
    └── wrangler.jsonc
```

## Deploy

```bash
# T1 (推奨): manifest を graph に MERGE するだけ
etzhayyim mitama -dir 60-apps/etzhayyim-project-kenkyusha
# → POST /xrpc/com.etzhayyim.actor.registerManifest
# → ActorManifest vertex MERGE in RisingWave
# → PDS Shared Executor が自動実行開始

# T3 fallback (Worker deploy が必要な場合のみ)
cd wasm/etzhayyim-wasm-kenkyusha-kk8r3n5v && etzhayyim deploy
```
