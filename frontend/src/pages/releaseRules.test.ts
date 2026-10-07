/** The release domain's client-side vocabulary mirrors (issue #59):
 * PRD 17's eleven states, eleven legal edges, terminality, and the two
 * mandatory-reason edges. The server remains the authority — these
 * tests pin the mirrors to the documented machine so drift is caught
 * here first. */
import { describe, expect, it } from "vitest";
import {
  RELEASE_STATUSES,
  RELEASE_STATUS_COLORS,
  RELEASE_STATUS_LABELS,
  legalTransitions,
  transitionRequiresReason,
} from "./releaseRules";

describe("releaseRules — PRD 17 state machine mirror", () => {
  it("covers all eleven states with labels and chip colors", () => {
    expect(RELEASE_STATUSES).toHaveLength(11);
    for (const status of RELEASE_STATUSES) {
      expect(RELEASE_STATUS_LABELS[status]).toBeTruthy();
      expect(RELEASE_STATUS_COLORS[status]).toBeTruthy();
    }
  });

  it("pins the eleven legal edges", () => {
    expect(legalTransitions("DRAFT")).toEqual(["VALIDATING"]);
    expect(legalTransitions("VALIDATING")).toEqual(["READY", "FAILED"]);
    expect(legalTransitions("READY")).toEqual(["APPROVAL_PENDING"]);
    expect(legalTransitions("APPROVAL_PENDING")).toEqual(["APPROVED"]);
    expect(legalTransitions("APPROVED")).toEqual(["DEPLOYING"]);
    expect(legalTransitions("DEPLOYING")).toEqual(["ACTIVE", "FAILED"]);
    expect(legalTransitions("ACTIVE")).toEqual(["PROMOTED", "ROLLED_BACK"]);
    expect(legalTransitions("PROMOTED")).toEqual(["COMPLETED"]);
  });

  it("treats FAILED, ROLLED_BACK, and COMPLETED as terminal", () => {
    expect(legalTransitions("FAILED")).toEqual([]);
    expect(legalTransitions("ROLLED_BACK")).toEqual([]);
    expect(legalTransitions("COMPLETED")).toEqual([]);
  });

  it("counts exactly eleven edges across the machine", () => {
    const edges = RELEASE_STATUSES.flatMap((status) => legalTransitions(status));
    expect(edges).toHaveLength(11);
  });

  it("requires a reason exactly on FAILED and ROLLED_BACK", () => {
    for (const status of RELEASE_STATUSES) {
      expect(transitionRequiresReason(status)).toBe(
        status === "FAILED" || status === "ROLLED_BACK",
      );
    }
  });
});
