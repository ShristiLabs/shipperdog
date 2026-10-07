/**
 * Client-side mirrors of the release domain's stable vocabulary
 * (docs/releases.md; PRD 17 — the API remains the authority): the
 * eleven-state vocabulary with display labels and chip colors, the
 * eleven legal transition edges offered as lifecycle actions (an
 * illegal attempt still answers the server's precise 409), and the
 * edges whose reason is mandatory. The backend enforces all of this
 * regardless; these mirrors only decide what renders.
 */
import type { ReleaseStatus } from "../api/releases";

export const RELEASE_STATUSES: readonly ReleaseStatus[] = [
  "DRAFT",
  "VALIDATING",
  "READY",
  "APPROVAL_PENDING",
  "APPROVED",
  "DEPLOYING",
  "ACTIVE",
  "PROMOTED",
  "COMPLETED",
  "ROLLED_BACK",
  "FAILED",
];

export const RELEASE_STATUS_LABELS: Record<ReleaseStatus, string> = {
  DRAFT: "Draft",
  VALIDATING: "Validating",
  READY: "Ready",
  APPROVAL_PENDING: "Approval pending",
  APPROVED: "Approved",
  DEPLOYING: "Deploying",
  ACTIVE: "Active",
  PROMOTED: "Promoted",
  COMPLETED: "Completed",
  ROLLED_BACK: "Rolled back",
  FAILED: "Failed",
};

/** MUI chip colors per state: in-flight info, pending-approval and
 * rollback warning, live/successful success, failure error, draft the
 * neutral default. */
export const RELEASE_STATUS_COLORS: Record<
  ReleaseStatus,
  "default" | "info" | "success" | "warning" | "error"
> = {
  DRAFT: "default",
  VALIDATING: "info",
  READY: "info",
  APPROVAL_PENDING: "warning",
  APPROVED: "info",
  DEPLOYING: "info",
  ACTIVE: "success",
  PROMOTED: "success",
  COMPLETED: "success",
  ROLLED_BACK: "warning",
  FAILED: "error",
};

/** PRD 17's eleven legal edges, exhaustively. FAILED, ROLLED_BACK, and
 * COMPLETED are terminal: no exits, no re-entry, no self-loops. */
const TRANSITIONS: Record<ReleaseStatus, readonly ReleaseStatus[]> = {
  DRAFT: ["VALIDATING"],
  VALIDATING: ["READY", "FAILED"],
  READY: ["APPROVAL_PENDING"],
  APPROVAL_PENDING: ["APPROVED"],
  APPROVED: ["DEPLOYING"],
  DEPLOYING: ["ACTIVE", "FAILED"],
  ACTIVE: ["PROMOTED", "ROLLED_BACK"],
  PROMOTED: ["COMPLETED"],
  COMPLETED: [],
  ROLLED_BACK: [],
  FAILED: [],
};

export function legalTransitions(status: ReleaseStatus): readonly ReleaseStatus[] {
  return TRANSITIONS[status];
}

/** The two edges whose reason is mandatory (a release that died or was
 * pulled explains itself — 422 RELEASE_TRANSITION_REASON_REQUIRED is
 * the server's mirror of this rule). */
export function transitionRequiresReason(to: ReleaseStatus): boolean {
  return to === "FAILED" || to === "ROLLED_BACK";
}
