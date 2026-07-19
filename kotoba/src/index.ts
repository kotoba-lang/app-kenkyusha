/**
 * kenkyusha kotoba — barrel.
 *
 * Per ADR-2606011400. The kenkyusha research-knowledge chain on the etzhayyim
 * substrate (AT PDS records; no RW).
 *
 *   discipline : defineDiscipline (optional parent FK) / getDiscipline / listDisciplines
 *   frontier   : openFrontier (FK→discipline) / setFrontierStatus / listFrontiers
 *   hypothesis : proposeHypothesis (FK→frontier) / setHypothesisStatus / listHypotheses
 *   evidence   : addEvidence (FK→hypothesis) / listEvidence
 *   coverage
 *
 * Public academic-research knowledge; the LLM frontier/hypothesis compute is etzhayyim.
 */

export * from "./types.js";
export {
  defineDiscipline,
  getDiscipline,
  listDisciplines,
  openFrontier,
  setFrontierStatus,
  listFrontiers,
  proposeHypothesis,
  setHypothesisStatus,
  listHypotheses,
  addEvidence,
  listEvidence,
  coverage,
} from "./registry.js";
