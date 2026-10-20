# app-kenkyusha（仮説・エビデンス）— itonami（dapp）上の分散型設計

状態: 第 1 スライス実装（2026-10-20）。正本はこの文書。
追跡: Linear JUN-128（親 JUN-116）。

## 位置づけ

app-kenkyusha は **AI 研究者 Actor** —— citation graph + content gap から
Research Frontier（未解明領域）を検出し、仮説生成 → エビデンス収集 → 検証 →
social post の shinka サイクルを回す（UNESCO ISCED-F 2013 の分類骨格）。

## このスライス: 仮説・エビデンス判定コア

| 層 | 実体 | 状態 |
|---|---|---|
| 判定コア guest | `aiueos/kenkyusha_evidence.cljk` — frontier-status-ok（open/active/resolved）/ hypothesis-status-ok（proposed/testing/supported/refuted）/ **terminal-status（supported / refuted は終端 = これ以上遷移しない）** / transition-judgment（終端拒否 = hypothesisTerminal）/ evidence-admission（**SourceType 6 / Stance 3 の閉じた集合** + 仮説の存在必須） | **実装済み・実測** |
| 記述子 | `aiueos/kenkyusha-evidence.edn` | **実装済み** |
| parity | `kotoba/test/kenkyusha/evidence_parity_test.cljk` — 正本（types.ts / registry.ts）の閉じた集合・終端規則・拒否名と一致 | **実装済み・実測** |

実測: `amu test aiueos/kenkyusha_evidence.cljk` → **18/18**（jvm-kir / js / wasm）。
parity → 2 tests / 21 assertions / 0 failures。

## 正本の正確な規則（guest はこれに追従）

- **HypothesisStatus**: proposed → testing → supported / refuted。
  **supported / refuted は終端**（`hypothesisTerminal:{status}` で拒否）。
- **SourceType** = bunken / isbn / issn / hanrei / intel / other（知識源の
  閉じた集合）。
- **Stance** = supporting / contradicting / neutral —— **エビデンスの立場は
  3 つだけ**（証拠の方向を記録し、それ以外は作らない）。
- エビデンスは仮説に結び付く（hypothesisNotFound は拒否）。
- 拒否の順序: 必須 → sourceType → stance → 仮説の有無。

## 調査で見つかった正本内の不一致（要 repo 側の判断）

- types.ts の HypothesisStatus は **4 値**（proposed / testing / supported /
  refuted）だが、AGENTS.md の graph schema 註は **5 値**
  （proposed / **investigating** / supported / refuted / **inconclusive**）。
  guest は**実行可能な正本（types.ts + registry.ts の終端規則）に追従**。
  testing vs investigating、inconclusive の有無は repo 側の判断待ち。

## 未実装境界

- Frontier 検出（citation gap 分析）、仮説の評価規則（supporting/contradicting
  の集計から supported/refuted へ）は research_loop（lg-clj）側。
- ISCED-F コード体系の判定（分類骨格 — nist-csf のコード判定と同型の候補）。
- funding / impact / event 各 observation（lg-clj）の判定。
- mon（DNA）化、aiueos session 載せ（fleet 共通）。
