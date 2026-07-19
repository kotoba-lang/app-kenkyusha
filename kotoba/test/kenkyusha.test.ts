import { describe, it, expect, beforeEach } from "vitest";
import { MockEtzhayyim } from "@etzhayyim/sdk-mock";
import {
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
} from "../src/index.js";

describe("kenkyusha kotoba", () => {
  let e: any;
  beforeEach(() => {
    e = new MockEtzhayyim({ did: "did:web:kenkyusha.etzhayyim.com" });
  });

  describe("discipline + frontier", () => {
    it("defines disciplines (parent FK), opens frontiers (FK→discipline), advances status", async () => {
      expect((await defineDiscipline(e, { disciplineId: "PHYS", name: "Physics", field: "natural-science" })).status).toBe("defined");
      expect((await defineDiscipline(e, { disciplineId: "QM", name: "Quantum Mechanics", parentDisciplineId: "PHYS" })).status).toBe("defined");
      expect((await defineDiscipline(e, { disciplineId: "X", name: "x", parentDisciplineId: "GHOST" })).status).toBe("parentNotFound");
      expect((await getDiscipline(e, { disciplineId: "QM" })).discipline?.parentDisciplineId).toBe("PHYS");
      expect((await listDisciplines(e, { parentDisciplineId: "PHYS" })).total).toBe(1);
      expect((await openFrontier(e, { frontierId: "F-1", disciplineId: "QM", title: "Measurement problem" })).status).toBe("opened");
      expect((await openFrontier(e, { frontierId: "F-X", disciplineId: "GHOST", title: "x" })).status).toBe("disciplineNotFound");
      expect((await setFrontierStatus(e, { frontierId: "F-1", status: "active" })).newStatus).toBe("active");
      expect((await setFrontierStatus(e, { frontierId: "F-1", status: "resolved" })).newStatus).toBe("resolved");
      expect((await setFrontierStatus(e, { frontierId: "F-1", status: "open" })).status).toBe("rejected"); // resolved terminal
      expect((await listFrontiers(e, { disciplineId: "QM", status: "resolved" })).total).toBe(1);
    });
  });

  describe("hypothesis + evidence chain", () => {
    beforeEach(async () => {
      await defineDiscipline(e, { disciplineId: "QM", name: "Quantum Mechanics" });
      await openFrontier(e, { frontierId: "F-1", disciplineId: "QM", title: "Measurement problem" });
    });
    it("proposes hypotheses (FK→frontier), advances; adds evidence (FK→hypothesis)", async () => {
      expect((await proposeHypothesis(e, { hypothesisId: "H-1", frontierId: "F-1", statement: "Decoherence resolves measurement" })).status).toBe("proposed");
      expect((await proposeHypothesis(e, { hypothesisId: "H-X", frontierId: "GHOST", statement: "x" })).status).toBe("frontierNotFound");
      expect((await setHypothesisStatus(e, { hypothesisId: "H-1", status: "testing" })).newStatus).toBe("testing");
      expect((await addEvidence(e, { evidenceId: "EV-1", hypothesisId: "H-1", sourceType: "bunken", sourceRef: "at://...", stance: "supporting" })).status).toBe("added");
      expect((await addEvidence(e, { evidenceId: "EV-2", hypothesisId: "H-1", sourceType: "issn", sourceRef: "1234-5678", stance: "contradicting" })).status).toBe("added");
      expect((await addEvidence(e, { evidenceId: "EV-X", hypothesisId: "GHOST", sourceType: "issn", sourceRef: "x", stance: "neutral" })).status).toBe("hypothesisNotFound");
      expect((await addEvidence(e, { evidenceId: "EV-Y", hypothesisId: "H-1", sourceType: "arxiv" as any, sourceRef: "x", stance: "neutral" })).status).toBe("rejected");
      expect((await listEvidence(e, { hypothesisId: "H-1", stance: "supporting" })).total).toBe(1);
      expect((await setHypothesisStatus(e, { hypothesisId: "H-1", status: "supported" })).newStatus).toBe("supported");
      expect((await setHypothesisStatus(e, { hypothesisId: "H-1", status: "refuted" })).status).toBe("rejected"); // terminal
    });
    it("coverage rolls up the four collections", async () => {
      await proposeHypothesis(e, { hypothesisId: "H-1", frontierId: "F-1", statement: "x" });
      await addEvidence(e, { evidenceId: "EV-1", hypothesisId: "H-1", sourceType: "bunken", sourceRef: "ref", stance: "supporting" });
      const cov = await coverage(e);
      expect(cov.disciplineCount).toBe(1);
      expect(cov.frontierCount).toBe(1);
      expect(cov.hypothesisCount).toBe(1);
      expect(cov.evidenceCount).toBe(1);
      expect(cov.frontiersByStatus?.open).toBe(1);
      expect(cov.hypothesesByStatus?.proposed).toBe(1);
    });
  });
});
