/** Quality-gate surface client (S10-03 backend, docs/releases.md): the
 * release-addressed gate routes the dashboard renders — the §32 block's
 * per-gate states with evidence links, the external CI submission
 * surface, and the governed override. Field shapes mirror the gate
 * module's served views exactly: `status` is the gate-state vocabulary
 * (PASSED/FAILED/NO_DATA/PENDING — NO_DATA is the metric-backed
 * fail-closed state, rendered distinctly from FAILED), `threshold` is
 * the definition's expected result for the "Coverage 87% (threshold
 * 80%)" render, and the history is the append-only trail behind
 * "latest wins". */
import { apiFetch } from "./client";

/** The expected-result shape a gate type reports (the built-in
 * catalog's schema; integration-defined kinds default to PASS_FAIL). */
export type GateSchema = "PASS_FAIL" | "NUMERIC_MIN" | "COUNT_MAX";

/** One gate of the release's §32 block. `status` is null only for an
 * expected gate nothing has reported or instantiated yet. */
export interface ReleaseGate {
  gateType: string;
  required: boolean;
  attached: boolean;
  schema: GateSchema;
  metricBacked: boolean;
  status: "PENDING" | "PASSED" | "FAILED" | "NO_DATA" | null;
  observedValue: number | null;
  threshold: number | null;
  evidenceUrl: string | null;
  source: "SUBMITTED" | "METRIC" | null;
  submittedBy: string | null;
  evaluatedAt: string | null;
  overridden: boolean;
  overriddenBy: string | null;
  overriddenReason: string | null;
  overriddenAt: string | null;
  history: GateResultRecord[];
}

/** One recorded result of the append-only history (newest first). */
export interface GateResultRecord {
  outcome: "PASSED" | "FAILED" | "NO_DATA";
  observedValue: number | null;
  evidenceUrl: string | null;
  submittedBy: string | null;
  source: "SUBMITTED" | "METRIC";
  recordedAt: string;
}

/** The release's whole gate block: the release summary beside the
 * per-gate states. */
export interface ReleaseGates {
  releaseId: string;
  releaseRef: string;
  status: string;
  ring: string;
  gates: ReleaseGate[];
}

/** Reads the release's gate block (app:releases:read). */
export function getReleaseGates(applicationId: string, releaseId: string): Promise<ReleaseGates> {
  return apiFetch<ReleaseGates>(
    `/releases/${releaseId}/gates?applicationId=${encodeURIComponent(applicationId)}`,
  );
}

/** Submits one external gate result (app:releases:manage; FR-022's
 * "external systems may submit"): pass or fail with mandatory
 * evidence; `observedValue` is required for threshold/ceiling gates
 * and refused for pass/fail ones. Latest wins with history. */
export function submitGateResult(
  applicationId: string,
  releaseId: string,
  gateType: string,
  input: { status: "pass" | "fail"; evidenceUrl: string; observedValue?: number },
): Promise<ReleaseGate> {
  return apiFetch<ReleaseGate>(`/releases/${releaseId}/gates/${gateType}/result`, {
    method: "POST",
    body: { applicationId, ...input },
  });
}

/** Overrides a blocking gate (app:releases:gate-override): only a
 * FAILED or NO_DATA gate, exactly once, with the mandatory reason. */
export function overrideGate(
  applicationId: string,
  releaseId: string,
  gateType: string,
  reason: string,
): Promise<ReleaseGate> {
  return apiFetch<ReleaseGate>(`/releases/${releaseId}/gates/${gateType}/override`, {
    method: "POST",
    body: { applicationId, reason },
  });
}
