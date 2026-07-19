/**
 * kenkyusha kotoba — research-knowledge record types.
 *
 * Per ADR-2606011400. kenkyusha is an AI researcher actor: it detects unresolved
 * research frontiers from academic knowledge graphs (bunken/isbn/issn/hanrei/
 * intel), generates hypotheses, and collects evidence. This package models the
 * research-knowledge chain:
 *   discipline → frontier → hypothesis → evidence
 * Registry on AT PDS records (replaces RW). ADR-2605172000 kotoba.
 *
 * AXIS NOTE (ADR-2605172400): axis-clean — public academic-research knowledge
 * (disciplines / frontiers / hypotheses / literature evidence). No personal PII,
 * no settlement, no fulfillment liability. The Murakumo LLM frontier-detection /
 * hypothesis-generation compute is SEPARATE (stays etzhayyim, like aima's AI-compute).
 *
 * Identity hierarchy:
 *   did:web:kenkyusha.etzhayyim.com                            — controller
 *   did:web:kenkyusha.etzhayyim.com:discipline:{disciplineId}  — a discipline
 *   did:web:kenkyusha.etzhayyim.com:frontier:{frontierId}      — a research frontier
 *   did:web:kenkyusha.etzhayyim.com:hypo:{hypothesisId}        — a hypothesis
 *   did:web:kenkyusha.etzhayyim.com:evidence:{evidenceId}      — an evidence item
 */

export const KENKYUSHA_DID_PREFIX = "did:web:kenkyusha.etzhayyim.com:" as const;

export const DISCIPLINE_COLLECTION = "com.etzhayyim.apps.kenkyusha.discipline";
export const FRONTIER_COLLECTION = "com.etzhayyim.apps.kenkyusha.frontier";
export const HYPOTHESIS_COLLECTION = "com.etzhayyim.apps.kenkyusha.hypothesis";
export const EVIDENCE_COLLECTION = "com.etzhayyim.apps.kenkyusha.evidence";

// ─── Discipline ─────────────────────────────────────────────────────

export interface DisciplineRecord {
  did: string;
  disciplineId: string;
  name: string;
  field?: string;
  parentDisciplineId?: string;
  createdAt: string;
}
export interface DisciplineView extends DisciplineRecord {
  disciplineUri: string;
}
export interface DefineDisciplineInput {
  disciplineId: string;
  name: string;
  field?: string;
  parentDisciplineId?: string;
}
export interface DefineDisciplineOutput {
  status: "defined" | "alreadyExists" | "rejected" | "parentNotFound";
  disciplineUri?: string;
  did?: string;
  disciplineId?: string;
  error?: string;
}
export interface GetDisciplineInput {
  disciplineId: string;
}
export interface GetDisciplineOutput {
  discipline?: DisciplineView;
  error?: string;
}
export interface ListDisciplinesInput {
  field?: string;
  parentDisciplineId?: string;
  limit?: number;
  cursor?: string;
}
export interface ListDisciplinesOutput {
  items: DisciplineView[];
  cursor?: string;
  total: number;
}

// ─── Frontier ───────────────────────────────────────────────────────

export type FrontierStatus = "open" | "active" | "resolved";

export interface FrontierRecord {
  did: string;
  frontierId: string;
  /** FK → discipline disciplineId. */
  disciplineId: string;
  title: string;
  description?: string;
  status: FrontierStatus;
  createdAt: string;
}
export interface FrontierView extends FrontierRecord {
  frontierUri: string;
}
export interface OpenFrontierInput {
  frontierId: string;
  disciplineId: string;
  title: string;
  description?: string;
}
export interface OpenFrontierOutput {
  status: "opened" | "alreadyExists" | "rejected" | "disciplineNotFound";
  frontierUri?: string;
  did?: string;
  frontierId?: string;
  error?: string;
}
export interface SetFrontierStatusInput {
  frontierId: string;
  status: FrontierStatus;
}
export interface SetFrontierStatusOutput {
  status: "updated" | "notFound" | "rejected";
  frontierId?: string;
  newStatus?: FrontierStatus;
  error?: string;
}
export interface ListFrontiersInput {
  disciplineId?: string;
  status?: FrontierStatus;
  limit?: number;
  cursor?: string;
}
export interface ListFrontiersOutput {
  items: FrontierView[];
  cursor?: string;
  total: number;
}

// ─── Hypothesis ─────────────────────────────────────────────────────

export type HypothesisStatus = "proposed" | "testing" | "supported" | "refuted";

export interface HypothesisRecord {
  did: string;
  hypothesisId: string;
  /** FK → frontier frontierId. */
  frontierId: string;
  statement: string;
  status: HypothesisStatus;
  createdAt: string;
}
export interface HypothesisView extends HypothesisRecord {
  hypothesisUri: string;
}
export interface ProposeHypothesisInput {
  hypothesisId: string;
  frontierId: string;
  statement: string;
}
export interface ProposeHypothesisOutput {
  status: "proposed" | "alreadyExists" | "rejected" | "frontierNotFound";
  hypothesisUri?: string;
  did?: string;
  hypothesisId?: string;
  error?: string;
}
export interface SetHypothesisStatusInput {
  hypothesisId: string;
  status: HypothesisStatus;
}
export interface SetHypothesisStatusOutput {
  status: "updated" | "notFound" | "rejected";
  hypothesisId?: string;
  newStatus?: HypothesisStatus;
  error?: string;
}
export interface ListHypothesesInput {
  frontierId?: string;
  status?: HypothesisStatus;
  limit?: number;
  cursor?: string;
}
export interface ListHypothesesOutput {
  items: HypothesisView[];
  cursor?: string;
  total: number;
}

// ─── Evidence ───────────────────────────────────────────────────────

export type SourceType = "bunken" | "isbn" | "issn" | "hanrei" | "intel" | "other";
export type Stance = "supporting" | "contradicting" | "neutral";

export interface EvidenceRecord {
  did: string;
  evidenceId: string;
  /** FK → hypothesis hypothesisId. */
  hypothesisId: string;
  sourceType: SourceType;
  /** Citation / DID / URL of the source. */
  sourceRef: string;
  stance: Stance;
  note?: string;
  createdAt: string;
}
export interface EvidenceView extends EvidenceRecord {
  evidenceUri: string;
}
export interface AddEvidenceInput {
  evidenceId: string;
  hypothesisId: string;
  sourceType: SourceType;
  sourceRef: string;
  stance: Stance;
  note?: string;
}
export interface AddEvidenceOutput {
  status: "added" | "alreadyExists" | "rejected" | "hypothesisNotFound";
  evidenceUri?: string;
  did?: string;
  evidenceId?: string;
  error?: string;
}
export interface ListEvidenceInput {
  hypothesisId?: string;
  sourceType?: SourceType;
  stance?: Stance;
  limit?: number;
  cursor?: string;
}
export interface ListEvidenceOutput {
  items: EvidenceView[];
  cursor?: string;
  total: number;
}

// ─── Coverage ───────────────────────────────────────────────────────

export interface CoverageInput {
  maxScan?: number;
}
export interface CoverageOutput {
  disciplineCount?: number;
  frontierCount?: number;
  hypothesisCount?: number;
  evidenceCount?: number;
  frontiersByStatus?: Record<string, number>;
  hypothesesByStatus?: Record<string, number>;
  truncated?: boolean;
  error?: string;
}

// ─── Validation + helpers ───────────────────────────────────────────

export const FRONTIER_STATUSES: ReadonlySet<string> = new Set(["open", "active", "resolved"]);
export const HYPOTHESIS_STATUSES: ReadonlySet<string> = new Set(["proposed", "testing", "supported", "refuted"]);
export const SOURCE_TYPES: ReadonlySet<string> = new Set(["bunken", "isbn", "issn", "hanrei", "intel", "other"]);
export const STANCES: ReadonlySet<string> = new Set(["supporting", "contradicting", "neutral"]);

export function disciplineDidFor(id: string): string {
  return `${KENKYUSHA_DID_PREFIX}discipline:${id.toLowerCase()}`;
}
export function disciplineRkey(id: string): string {
  return `discipline-${id.toLowerCase()}`;
}
export function frontierDidFor(id: string): string {
  return `${KENKYUSHA_DID_PREFIX}frontier:${id.toLowerCase()}`;
}
export function frontierRkey(id: string): string {
  return `frontier-${id.toLowerCase()}`;
}
export function hypothesisDidFor(id: string): string {
  return `${KENKYUSHA_DID_PREFIX}hypo:${id.toLowerCase()}`;
}
export function hypothesisRkey(id: string): string {
  return `hypo-${id.toLowerCase()}`;
}
export function evidenceDidFor(id: string): string {
  return `${KENKYUSHA_DID_PREFIX}evidence:${id.toLowerCase()}`;
}
export function evidenceRkey(id: string): string {
  return `evidence-${id.toLowerCase()}`;
}
