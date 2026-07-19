/**
 * kenkyusha kotoba — discipline + frontier + hypothesis + evidence registries
 * + coverage. AT PDS records (no RW). The research-knowledge chain FK-validates
 * down: frontier→discipline, hypothesis→frontier, evidence→hypothesis. Public
 * academic-research data only.
 */

import type { Etzhayyim } from "@etzhayyim/sdk";
import {
  DISCIPLINE_COLLECTION,
  EVIDENCE_COLLECTION,
  FRONTIER_COLLECTION,
  FRONTIER_STATUSES,
  HYPOTHESIS_COLLECTION,
  HYPOTHESIS_STATUSES,
  SOURCE_TYPES,
  STANCES,
  disciplineDidFor,
  disciplineRkey,
  evidenceDidFor,
  evidenceRkey,
  frontierDidFor,
  frontierRkey,
  hypothesisDidFor,
  hypothesisRkey,
  type AddEvidenceInput,
  type AddEvidenceOutput,
  type CoverageInput,
  type CoverageOutput,
  type DefineDisciplineInput,
  type DefineDisciplineOutput,
  type DisciplineRecord,
  type DisciplineView,
  type EvidenceRecord,
  type EvidenceView,
  type FrontierRecord,
  type FrontierView,
  type GetDisciplineInput,
  type GetDisciplineOutput,
  type HypothesisRecord,
  type HypothesisView,
  type ListDisciplinesInput,
  type ListDisciplinesOutput,
  type ListEvidenceInput,
  type ListEvidenceOutput,
  type ListFrontiersInput,
  type ListFrontiersOutput,
  type ListHypothesesInput,
  type ListHypothesesOutput,
  type OpenFrontierInput,
  type OpenFrontierOutput,
  type ProposeHypothesisInput,
  type ProposeHypothesisOutput,
  type SetFrontierStatusInput,
  type SetFrontierStatusOutput,
  type SetHypothesisStatusInput,
  type SetHypothesisStatusOutput,
} from "./types.js";

const PAGE_LIMIT = 100;
const DEFAULT_MAX_SCAN = 10_000;

async function exists(e: Etzhayyim, collection: string, rkey: string): Promise<boolean> {
  const resp = await e.read({ collection, rkey }).catch(() => ({ records: [] }));
  return Boolean(resp.records[0]?.value);
}

async function scanAll<T>(e: Etzhayyim, collection: string, maxScan: number, onRow: (v: T) => void): Promise<number> {
  let cursor: string | undefined;
  let scanned = 0;
  while (scanned < maxScan) {
    const page = await e.read<T>({ collection, cursor, limit: PAGE_LIMIT });
    for (const r of page.records) {
      if (scanned >= maxScan) break;
      onRow(r.value);
      scanned += 1;
    }
    if (scanned >= maxScan || !page.cursor || page.records.length < PAGE_LIMIT) break;
    cursor = page.cursor;
  }
  return scanned;
}

// ─── Discipline ─────────────────────────────────────────────────────

export async function defineDiscipline(e: Etzhayyim, input: DefineDisciplineInput): Promise<DefineDisciplineOutput> {
  if (!input.disciplineId || !input.name) return { status: "rejected", error: "missingRequiredFields" };
  if (input.parentDisciplineId && !(await exists(e, DISCIPLINE_COLLECTION, disciplineRkey(input.parentDisciplineId)))) {
    return { status: "parentNotFound", error: `parentNotFound:${input.parentDisciplineId}` };
  }
  const rkey = disciplineRkey(input.disciplineId);
  const existing = await e.read<DisciplineRecord>({ collection: DISCIPLINE_COLLECTION, rkey }).catch(() => ({ records: [] }));
  if (existing.records[0]?.value) {
    return { status: "alreadyExists", disciplineUri: existing.records[0].uri, did: existing.records[0].value.did, disciplineId: input.disciplineId };
  }
  const did = disciplineDidFor(input.disciplineId);
  const record: DisciplineRecord = {
    did,
    disciplineId: input.disciplineId,
    name: input.name,
    field: input.field,
    parentDisciplineId: input.parentDisciplineId,
    createdAt: new Date().toISOString(),
  };
  const receipt = await e.write({ collection: DISCIPLINE_COLLECTION, record: record as unknown as Record<string, unknown>, rkey });
  return { status: "defined", disciplineUri: receipt.uri, did, disciplineId: input.disciplineId };
}

export async function getDiscipline(e: Etzhayyim, input: GetDisciplineInput): Promise<GetDisciplineOutput> {
  if (!input.disciplineId) return { error: "invalidDisciplineId" };
  const resp = await e.read<DisciplineRecord>({ collection: DISCIPLINE_COLLECTION, rkey: disciplineRkey(input.disciplineId) }).catch(() => ({ records: [] }));
  const r = resp.records[0];
  if (!r) return { error: "notFound" };
  return { discipline: { ...r.value, disciplineUri: r.uri } };
}

export async function listDisciplines(e: Etzhayyim, input: ListDisciplinesInput = {}): Promise<ListDisciplinesOutput> {
  const limit = Math.min(input.limit ?? 50, 200);
  const resp = await e.read<DisciplineRecord>({ collection: DISCIPLINE_COLLECTION, cursor: input.cursor, limit });
  const items: DisciplineView[] = resp.records
    .filter((r) => {
      const v = r.value;
      if (input.field && v.field !== input.field) return false;
      if (input.parentDisciplineId && v.parentDisciplineId !== input.parentDisciplineId) return false;
      return true;
    })
    .map((r) => ({ ...r.value, disciplineUri: r.uri }));
  return { items, cursor: resp.cursor, total: items.length };
}

// ─── Frontier ───────────────────────────────────────────────────────

export async function openFrontier(e: Etzhayyim, input: OpenFrontierInput): Promise<OpenFrontierOutput> {
  if (!input.frontierId || !input.disciplineId || !input.title) return { status: "rejected", error: "missingRequiredFields" };
  if (!(await exists(e, DISCIPLINE_COLLECTION, disciplineRkey(input.disciplineId)))) {
    return { status: "disciplineNotFound", error: `disciplineNotFound:${input.disciplineId}` };
  }
  const rkey = frontierRkey(input.frontierId);
  const existing = await e.read<FrontierRecord>({ collection: FRONTIER_COLLECTION, rkey }).catch(() => ({ records: [] }));
  if (existing.records[0]?.value) {
    return { status: "alreadyExists", frontierUri: existing.records[0].uri, did: existing.records[0].value.did, frontierId: input.frontierId };
  }
  const did = frontierDidFor(input.frontierId);
  const record: FrontierRecord = {
    did,
    frontierId: input.frontierId,
    disciplineId: input.disciplineId,
    title: input.title,
    description: input.description,
    status: "open",
    createdAt: new Date().toISOString(),
  };
  const receipt = await e.write({ collection: FRONTIER_COLLECTION, record: record as unknown as Record<string, unknown>, rkey });
  return { status: "opened", frontierUri: receipt.uri, did, frontierId: input.frontierId };
}

export async function setFrontierStatus(e: Etzhayyim, input: SetFrontierStatusInput): Promise<SetFrontierStatusOutput> {
  if (!input.frontierId || !FRONTIER_STATUSES.has(input.status)) return { status: "rejected", error: "invalidStatus" };
  const rkey = frontierRkey(input.frontierId);
  const resp = await e.read<FrontierRecord>({ collection: FRONTIER_COLLECTION, rkey }).catch(() => ({ records: [] }));
  const frontier = resp.records[0]?.value;
  if (!frontier) return { status: "notFound", error: "frontierNotFound" };
  if (frontier.status === "resolved") return { status: "rejected", error: "frontierResolved" };
  await e.write({ collection: FRONTIER_COLLECTION, record: { ...frontier, status: input.status } as unknown as Record<string, unknown>, rkey });
  return { status: "updated", frontierId: input.frontierId, newStatus: input.status };
}

export async function listFrontiers(e: Etzhayyim, input: ListFrontiersInput = {}): Promise<ListFrontiersOutput> {
  const limit = Math.min(input.limit ?? 50, 200);
  const resp = await e.read<FrontierRecord>({ collection: FRONTIER_COLLECTION, cursor: input.cursor, limit });
  const items: FrontierView[] = resp.records
    .filter((r) => {
      const v = r.value;
      if (input.disciplineId && v.disciplineId !== input.disciplineId) return false;
      if (input.status && v.status !== input.status) return false;
      return true;
    })
    .map((r) => ({ ...r.value, frontierUri: r.uri }));
  return { items, cursor: resp.cursor, total: items.length };
}

// ─── Hypothesis ─────────────────────────────────────────────────────

export async function proposeHypothesis(e: Etzhayyim, input: ProposeHypothesisInput): Promise<ProposeHypothesisOutput> {
  if (!input.hypothesisId || !input.frontierId || !input.statement) return { status: "rejected", error: "missingRequiredFields" };
  if (!(await exists(e, FRONTIER_COLLECTION, frontierRkey(input.frontierId)))) {
    return { status: "frontierNotFound", error: `frontierNotFound:${input.frontierId}` };
  }
  const rkey = hypothesisRkey(input.hypothesisId);
  const existing = await e.read<HypothesisRecord>({ collection: HYPOTHESIS_COLLECTION, rkey }).catch(() => ({ records: [] }));
  if (existing.records[0]?.value) {
    return { status: "alreadyExists", hypothesisUri: existing.records[0].uri, did: existing.records[0].value.did, hypothesisId: input.hypothesisId };
  }
  const did = hypothesisDidFor(input.hypothesisId);
  const record: HypothesisRecord = {
    did,
    hypothesisId: input.hypothesisId,
    frontierId: input.frontierId,
    statement: input.statement,
    status: "proposed",
    createdAt: new Date().toISOString(),
  };
  const receipt = await e.write({ collection: HYPOTHESIS_COLLECTION, record: record as unknown as Record<string, unknown>, rkey });
  return { status: "proposed", hypothesisUri: receipt.uri, did, hypothesisId: input.hypothesisId };
}

export async function setHypothesisStatus(e: Etzhayyim, input: SetHypothesisStatusInput): Promise<SetHypothesisStatusOutput> {
  if (!input.hypothesisId || !HYPOTHESIS_STATUSES.has(input.status)) return { status: "rejected", error: "invalidStatus" };
  const rkey = hypothesisRkey(input.hypothesisId);
  const resp = await e.read<HypothesisRecord>({ collection: HYPOTHESIS_COLLECTION, rkey }).catch(() => ({ records: [] }));
  const hypothesis = resp.records[0]?.value;
  if (!hypothesis) return { status: "notFound", error: "hypothesisNotFound" };
  if (hypothesis.status === "supported" || hypothesis.status === "refuted") {
    return { status: "rejected", error: `hypothesisTerminal:${hypothesis.status}` };
  }
  await e.write({ collection: HYPOTHESIS_COLLECTION, record: { ...hypothesis, status: input.status } as unknown as Record<string, unknown>, rkey });
  return { status: "updated", hypothesisId: input.hypothesisId, newStatus: input.status };
}

export async function listHypotheses(e: Etzhayyim, input: ListHypothesesInput = {}): Promise<ListHypothesesOutput> {
  const limit = Math.min(input.limit ?? 50, 200);
  const resp = await e.read<HypothesisRecord>({ collection: HYPOTHESIS_COLLECTION, cursor: input.cursor, limit });
  const items: HypothesisView[] = resp.records
    .filter((r) => {
      const v = r.value;
      if (input.frontierId && v.frontierId !== input.frontierId) return false;
      if (input.status && v.status !== input.status) return false;
      return true;
    })
    .map((r) => ({ ...r.value, hypothesisUri: r.uri }));
  return { items, cursor: resp.cursor, total: items.length };
}

// ─── Evidence ───────────────────────────────────────────────────────

export async function addEvidence(e: Etzhayyim, input: AddEvidenceInput): Promise<AddEvidenceOutput> {
  if (!input.evidenceId || !input.hypothesisId || !input.sourceRef) return { status: "rejected", error: "missingRequiredFields" };
  if (!SOURCE_TYPES.has(input.sourceType)) return { status: "rejected", error: "invalidSourceType" };
  if (!STANCES.has(input.stance)) return { status: "rejected", error: "invalidStance" };
  if (!(await exists(e, HYPOTHESIS_COLLECTION, hypothesisRkey(input.hypothesisId)))) {
    return { status: "hypothesisNotFound", error: `hypothesisNotFound:${input.hypothesisId}` };
  }
  const rkey = evidenceRkey(input.evidenceId);
  const existing = await e.read<EvidenceRecord>({ collection: EVIDENCE_COLLECTION, rkey }).catch(() => ({ records: [] }));
  if (existing.records[0]?.value) {
    return { status: "alreadyExists", evidenceUri: existing.records[0].uri, did: existing.records[0].value.did, evidenceId: input.evidenceId };
  }
  const did = evidenceDidFor(input.evidenceId);
  const record: EvidenceRecord = {
    did,
    evidenceId: input.evidenceId,
    hypothesisId: input.hypothesisId,
    sourceType: input.sourceType,
    sourceRef: input.sourceRef,
    stance: input.stance,
    note: input.note,
    createdAt: new Date().toISOString(),
  };
  const receipt = await e.write({ collection: EVIDENCE_COLLECTION, record: record as unknown as Record<string, unknown>, rkey });
  return { status: "added", evidenceUri: receipt.uri, did, evidenceId: input.evidenceId };
}

export async function listEvidence(e: Etzhayyim, input: ListEvidenceInput = {}): Promise<ListEvidenceOutput> {
  const limit = Math.min(input.limit ?? 50, 200);
  const resp = await e.read<EvidenceRecord>({ collection: EVIDENCE_COLLECTION, cursor: input.cursor, limit });
  const items: EvidenceView[] = resp.records
    .filter((r) => {
      const v = r.value;
      if (input.hypothesisId && v.hypothesisId !== input.hypothesisId) return false;
      if (input.sourceType && v.sourceType !== input.sourceType) return false;
      if (input.stance && v.stance !== input.stance) return false;
      return true;
    })
    .map((r) => ({ ...r.value, evidenceUri: r.uri }));
  return { items, cursor: resp.cursor, total: items.length };
}

// ─── Coverage ───────────────────────────────────────────────────────

export async function coverage(e: Etzhayyim, input: CoverageInput = {}): Promise<CoverageOutput> {
  const maxScan = Math.min(input.maxScan ?? DEFAULT_MAX_SCAN, DEFAULT_MAX_SCAN);
  const disciplineCount = await scanAll<DisciplineRecord>(e, DISCIPLINE_COLLECTION, maxScan, () => {});
  const frontiersByStatus: Record<string, number> = {};
  const frontierCount = await scanAll<FrontierRecord>(e, FRONTIER_COLLECTION, maxScan, (v) => {
    frontiersByStatus[v.status] = (frontiersByStatus[v.status] ?? 0) + 1;
  });
  const hypothesesByStatus: Record<string, number> = {};
  const hypothesisCount = await scanAll<HypothesisRecord>(e, HYPOTHESIS_COLLECTION, maxScan, (v) => {
    hypothesesByStatus[v.status] = (hypothesesByStatus[v.status] ?? 0) + 1;
  });
  const evidenceCount = await scanAll<EvidenceRecord>(e, EVIDENCE_COLLECTION, maxScan, () => {});
  return {
    disciplineCount,
    frontierCount,
    hypothesisCount,
    evidenceCount,
    frontiersByStatus,
    hypothesesByStatus,
    truncated: disciplineCount >= maxScan || frontierCount >= maxScan || hypothesisCount >= maxScan || evidenceCount >= maxScan,
  };
}
