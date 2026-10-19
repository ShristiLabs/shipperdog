/** OTA console API (S16-15, issue #177; ADR-0010 workstreams 3-11):
 * the bundle authoring chain (create -> direct-to-storage upload ->
 * finalize+sign), the exactly-once sdc1. app tokens, the release's
 * staged rollout control, the OTA dashboard reads (funnel, bundle
 * health, alert feed), and the alert-rule store. Every shape mirrors
 * docs/openapi/baseline.yaml verbatim — the backend surfaces are
 * frozen; this module only types and routes them. Every route is
 * application- or tenant-addressed for permissions, mirroring the
 * CLI's ota surface (S16-14). */
import { ApiError } from "./problem";
import { apiFetch, pageFetch, type Page } from "./client";

// ---------------------------------------------------------------------------
// Bundles (S16-03)
// ---------------------------------------------------------------------------

/** The authoring draft a create answers: the pre-allocated bundle id
 * (the bytes ARE the bundle), its dedicated versions row, and the
 * direct-to-storage upload grant with its expiry. */
export interface OtaDraft {
  id: string;
  applicationId: string;
  versionId: string;
  bundleVersion: string;
  platformTag: string;
  forced: boolean;
  minAppVersion: string | null;
  status: string;
  upload: {
    artifactId: string;
    uploadUrl: string;
    expiresAt: string;
  };
}

export interface CreateOtaBundleInput {
  applicationId: string;
  bundleVersion: string;
  platformTag: string;
  fileName: string;
  forced: boolean;
  minAppVersion?: string;
  sizeHint?: number;
}

/** The SIGNED bundle row (nothing is listable before finalize lands
 * it): the manifest facts — sha256, sizeBytes, platformTag — the
 * Ed25519 signature and its keyId, and the release linkage once a
 * rollout carries it. */
export interface OtaBundle {
  id: string;
  applicationId: string;
  versionId: string;
  artifactId: string;
  releaseId: string | null;
  bundleVersion: string;
  platformTag: string;
  sha256: string;
  sizeBytes: number;
  signature: string;
  keyId: string;
  forced: boolean;
  minAppVersion: string | null;
  status: string;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

export interface FinalizeResult {
  created: boolean;
  bundle: OtaBundle;
}

/** One designated POST per wizard submission: the fresh key means an
 * intra-submission retry replays the stored outcome instead of
 * double-creating (the interceptor's contract; a missing key simply
 * passes through, so the key is belt-and-braces like the CLI's). */
function idempotencyKey(): string {
  return crypto.randomUUID();
}

export function createOtaBundle(input: CreateOtaBundleInput): Promise<OtaDraft> {
  return apiFetch<OtaDraft>("/ota/bundles", {
    method: "POST",
    body: input,
    headers: { "Idempotency-Key": idempotencyKey() },
  });
}

/** Streams the bundle bytes to the granted session URL. The URL is
 * storage-side (presigned / emulator): requested as-is, octet-stream,
 * and deliberately WITHOUT the API bearer header — embedding auth
 * there is the server's job (the CLI PutToStorage doctrine). The
 * File's known size rides as Content-Length so storage rejects
 * truncation rather than storing it silently. */
export async function putOtaBundleBytes(uploadUrl: string, file: File): Promise<void> {
  const response = await fetch(uploadUrl, {
    method: "PUT",
    headers: { "Content-Type": "application/octet-stream" },
    body: file,
  });
  if (!response.ok) {
    throw new ApiError({ status: response.status, title: `Upload failed (${response.status})` });
  }
}

/** Lands the manifest and the signature after the bytes reached
 * storage; created=false means the answer replayed an already-SIGNED
 * row (the state is the answer). */
export function finalizeOtaBundle(bundleId: string, versionId: string): Promise<FinalizeResult> {
  return apiFetch<FinalizeResult>(`/ota/bundles/${bundleId}/finalize`, {
    method: "POST",
    body: { versionId },
    headers: { "Idempotency-Key": idempotencyKey() },
  });
}

export function getOtaBundle(applicationId: string, bundleId: string): Promise<OtaBundle> {
  return apiFetch<OtaBundle>(`/ota/bundles/${bundleId}?applicationId=${applicationId}`);
}

export function listOtaBundles(
  applicationId: string,
  params: { status?: string; limit?: number; cursor?: string } = {},
): Promise<Page<OtaBundle>> {
  return pageFetch<OtaBundle>("/ota/bundles", { applicationId, ...params });
}

/** The author's withdrawal of a SIGNED bundle (REVOKED; the device
 * check then answers no-update). */
export function revokeOtaBundle(bundleId: string, applicationId: string): Promise<OtaBundle> {
  return apiFetch<OtaBundle>(`/ota/bundles/${bundleId}/revoke`, {
    method: "POST",
    body: { applicationId },
    headers: { "Idempotency-Key": idempotencyKey() },
  });
}

/** The application's ACTIVE signing key — the public half plus its
 * keyId, copyable before the SDK ships (D8). Before the first
 * finalized bundle there is no key (generation is a finalize-time
 * act) and this read answers the honest 404. */
export interface OtaSigningKey {
  applicationId: string;
  keyId: string;
  publicKey: string;
  algorithm: string;
  createdAt: string;
}

export function getOtaSigningKey(applicationId: string): Promise<OtaSigningKey> {
  return apiFetch<OtaSigningKey>(`/ota/signing-keys?applicationId=${applicationId}`);
}

// ---------------------------------------------------------------------------
// App tokens (S16-04) — the sdc1. device identifier
// ---------------------------------------------------------------------------

export interface OtaAppToken {
  id: string;
  applicationId: string;
  name: string;
  createdBy: string;
  revoked: boolean;
  revokedAt: string | null;
}

/** The issue answer: the registry row plus the value, which the
 * server stores only as a SHA-256 — the value shows exactly once,
 * here, never again. */
export interface IssuedOtaAppToken {
  token: OtaAppToken;
  value: string;
}

export function issueOtaAppToken(applicationId: string, name: string): Promise<IssuedOtaAppToken> {
  return apiFetch<IssuedOtaAppToken>("/ota/app-tokens", {
    method: "POST",
    body: { applicationId, name },
    headers: { "Idempotency-Key": idempotencyKey() },
  });
}

export function listOtaAppTokens(
  applicationId: string,
  params: { limit?: number; cursor?: string } = {},
): Promise<Page<OtaAppToken>> {
  return pageFetch<OtaAppToken>("/ota/app-tokens", { applicationId, ...params });
}

export function revokeOtaAppToken(tokenId: string, applicationId: string): Promise<OtaAppToken> {
  return apiFetch<OtaAppToken>(`/ota/app-tokens/${tokenId}/revoke`, {
    method: "POST",
    body: { applicationId },
    headers: { "Idempotency-Key": idempotencyKey() },
  });
}

// ---------------------------------------------------------------------------
// Rollouts (S16-06) — staged OTA distribution control
// ---------------------------------------------------------------------------

export interface OtaRolloutStage {
  position: number;
  basisPoints: number;
  note: string | null;
  status: string;
  reachedAt: string | null;
}

export interface OtaRolloutEvent {
  action: string;
  stagePosition: number;
  detail: string | null;
  actorId: string;
  occurredAt: string;
}

/** The plan view: mode and standing, the cumulative basis-points
 * ladder, and the append-only timeline. currentStage is the number of
 * reached stages (the plan stands at that rung). */
export interface OtaRolloutView {
  releaseId: string;
  applicationId: string;
  mode: "AUTOMATIC" | "CHECKPOINT";
  status: "RUNNING" | "PAUSED" | "HALTED" | "COMPLETE";
  currentStage: number;
  planSalt: string;
  stages: OtaRolloutStage[];
  timeline: OtaRolloutEvent[];
}

export function getOtaRollout(applicationId: string, releaseId: string): Promise<OtaRolloutView> {
  return apiFetch<OtaRolloutView>(`/ota/rollouts/${releaseId}?applicationId=${applicationId}`);
}

export type OtaRolloutAction = "advance" | "halt" | "pause" | "resume";

/** One operator control action. Concurrent control on one plan
 * answers the optimistic-lock 409 (ROLLOUT_CONFLICT), never a lost
 * update; halt's reason is mandatory (the audited stop, D10). */
export function controlOtaRollout(
  releaseId: string,
  action: OtaRolloutAction,
  input: { applicationId: string; reason?: string },
): Promise<OtaRolloutView> {
  return apiFetch<OtaRolloutView>(`/ota/rollouts/${releaseId}/${action}`, {
    method: "POST",
    body: { applicationId: input.applicationId, reason: input.reason },
    headers: { "Idempotency-Key": idempotencyKey() },
  });
}

// ---------------------------------------------------------------------------
// Dashboard reads (S16-10 / S16-11) — tenant-addressed
// ---------------------------------------------------------------------------

/** The device funnel's stage counts (checks -> seen -> downloads ->
 * applied, with the failed and rolled-back exits beside them). */
export interface OtaFunnelStages {
  checks: number;
  seen: number;
  downloads: number;
  applied: number;
  failed: number;
  rolledBack: number;
}

export interface OtaFailureReason {
  reason: string;
  count: number;
}

export interface OtaAdoptionPoint {
  day: string;
  seenDevices: number;
  appliedDevices: number;
  /** appliedDevices / seenDevices as a 0..1 fraction. */
  adoptionRate: number;
}

export interface OtaFunnelView {
  bundleId: string;
  bundleVersion: string;
  applicationId: string;
  releaseId: string;
  releaseRef: string;
  stages: OtaFunnelStages;
  failures: OtaFailureReason[];
  adoption: OtaAdoptionPoint[];
}

export function getOtaFunnel(
  tenantId: string,
  bundleId: string,
  ring?: string,
): Promise<OtaFunnelView> {
  return apiFetch<OtaFunnelView>(
    `/tenants/${tenantId}/dashboard/ota/bundles/${bundleId}/funnel${ring ? `?ring=${encodeURIComponent(ring)}` : ""}`,
  );
}

/** One side of the crash-rate comparison; crashFreeRate is the
 * 0..1 fraction (1 - crashSessions/sessions), null while the release
 * holds no sessions (the explicit NO_DATA). */
export interface OtaBundleHealthSide {
  bundleId: string;
  bundleVersion: string;
  releaseId: string;
  releaseRef: string;
  sessions: number;
  crashSessions: number;
  crashFreeRate: number | null;
}

export interface OtaBundleHealthView {
  newBundle: OtaBundleHealthSide;
  currentBundle: OtaBundleHealthSide | null;
  crashFreeRateDelta: number | null;
}

export function getOtaBundleHealth(tenantId: string, bundleId: string): Promise<OtaBundleHealthView> {
  return apiFetch<OtaBundleHealthView>(`/tenants/${tenantId}/dashboard/ota/bundles/${bundleId}/health`);
}

export interface OtaAlertFiring {
  id: string;
  ruleId: string;
  applicationId: string;
  otaBundleId: string;
  metric: string;
  comparator: string;
  threshold: number;
  severity: "INFO" | "WARNING" | "CRITICAL";
  observedValue: number;
  detail: string;
  firedAt: string;
  resolvedAt: string | null;
}

/** The tenant's OTA alert answer: severity counts (the badges) beside
 * the standing firings and the recent resolution history. */
export interface OtaAlertFeedView {
  tenantId: string;
  generatedAt: string;
  counts: { critical: number; warning: number; info: number };
  standing: OtaAlertFiring[];
  recent: OtaAlertFiring[];
}

export function getOtaAlerts(tenantId: string): Promise<OtaAlertFeedView> {
  return apiFetch<OtaAlertFeedView>(`/tenants/${tenantId}/dashboard/ota/alerts`);
}

// ---------------------------------------------------------------------------
// Alert rules (S16-11) — the per-application rule store
// ---------------------------------------------------------------------------

export type OtaAlertMetric =
  | "OTA_CHECKS"
  | "OTA_SEEN"
  | "OTA_DOWNLOADS"
  | "OTA_APPLIED"
  | "OTA_FAILURES"
  | "OTA_ROLLBACKS"
  | "OTA_APPLY_FAILURE_RATE"
  | "OTA_CRASH_FREE_RATE";

export type OtaAlertComparator = "GT" | "GTE" | "LT" | "LTE";
export type OtaAlertSeverity = "INFO" | "WARNING" | "CRITICAL";

export interface OtaAlertRuleInput {
  applicationId: string;
  otaBundleId?: string;
  metric: OtaAlertMetric;
  comparator: OtaAlertComparator;
  threshold: number;
  windowMinutes: number;
  severity: OtaAlertSeverity;
}

export interface OtaAlertRule {
  id: string;
  applicationId: string;
  otaBundleId: string | null;
  metric: OtaAlertMetric;
  comparator: OtaAlertComparator;
  threshold: number;
  windowMinutes: number;
  severity: OtaAlertSeverity;
  createdAt: string;
  updatedAt: string;
  standingFiring: {
    id: string;
    otaBundleId: string;
    observedValue: number;
    detail: string;
    firedAt: string;
    resolvedAt: string | null;
  } | null;
}

export function listOtaAlertRules(
  applicationId: string,
  params: { limit?: number; cursor?: string } = {},
): Promise<Page<OtaAlertRule>> {
  return pageFetch<OtaAlertRule>("/ota/alert-rules", { applicationId, ...params });
}

export function createOtaAlertRule(input: OtaAlertRuleInput): Promise<OtaAlertRule> {
  return apiFetch<OtaAlertRule>("/ota/alert-rules", { method: "POST", body: input });
}

export function updateOtaAlertRule(ruleId: string, input: OtaAlertRuleInput): Promise<OtaAlertRule> {
  return apiFetch<OtaAlertRule>(`/ota/alert-rules/${ruleId}`, { method: "PUT", body: input });
}

export function deleteOtaAlertRule(ruleId: string, applicationId: string): Promise<void> {
  return apiFetch<void>(`/ota/alert-rules/${ruleId}?applicationId=${applicationId}`, {
    method: "DELETE",
  });
}
