/** Release surface client (S9-01/02/03/04 backend, docs/releases.md):
 * the listing with its conjunctive filters, the detail, PRD 17's
 * transitions, the append-only history timeline, the frozen manifest
 * (JSON view and the `format=yaml` compliance render), and the
 * manifest diff. Every route is application-addressed — the
 * `applicationId` rides the query or body as the permission target
 * (`app:releases:read` / `app:releases:manage`). Field shapes mirror
 * the release module's served views exactly. */
import { apiFetch, apiFetchText, pageFetch, type Page } from "./client";

/** PRD 17's exhaustive state vocabulary (eleven states, eleven legal edges). */
export type ReleaseStatus =
  | "DRAFT"
  | "VALIDATING"
  | "READY"
  | "APPROVAL_PENDING"
  | "APPROVED"
  | "DEPLOYING"
  | "ACTIVE"
  | "PROMOTED"
  | "COMPLETED"
  | "ROLLED_BACK"
  | "FAILED";

/** The list-item view: registry fields, the quotable id, ring and
 * environment codes, and the target count. */
export interface Release {
  id: string;
  releaseRef: string;
  applicationId: string;
  versionId: string;
  status: ReleaseStatus;
  ring: string;
  environment: string | null;
  targetCount: number;
  createdAt: string;
  updatedAt: string;
}

/** The detail view: the list shape plus the complete creation record —
 * per-platform artifact slots, the full target set, the targeted
 * environment, and the creator. */
export interface ReleaseDetail extends Release {
  ringName: string;
  environmentId: string | null;
  environmentName: string | null;
  artifacts: Array<{ platform: string; artifactId: string }>;
  targets: Array<{ tenantId: string; ring: string; testerGroupIds: string[] }>;
  createdBy: string;
}

/** One timeline row: the crossing's source and target states (the
 * creation row's source is null — a release is born, it does not
 * transition into DRAFT), the reason where present, the actor, when. */
export interface ReleaseTransition {
  from: ReleaseStatus | null;
  to: ReleaseStatus;
  reason: string | null;
  actorId: string;
  occurredAt: string;
}

/** The history view: the release summary plus the chronological
 * append-only timeline (the record the dashboard renders). */
export interface ReleaseHistory {
  id: string;
  releaseRef: string;
  applicationId: string;
  versionId: string;
  status: ReleaseStatus;
  createdAt: string;
  updatedAt: string;
  transitions: ReleaseTransition[];
}

/** The frozen manifest document of PRD 16: every field a copied value,
 * never a live reference. Nulls are deliberate (the block's owning
 * story has not landed); the shape is complete forever. */
export interface ReleaseManifestDocument {
  release: {
    releaseId: string;
    releaseRef: string;
    applicationId: string;
    applicationName: string;
    versionId: string;
    versionName: string;
    organizationId: string;
  };
  artifacts: Array<{
    platform: string;
    artifactId: string;
    fileName: string;
    buildNumber: number | null;
    sha256: string;
    sizeBytes: number;
    uploadedAt: string;
  }>;
  targets: Array<{ tenantId: string; testerGroupIds: string[] }>;
  ring: { code: string; name: string; position: number };
  environment: string | null;
  rollout: { percentage: number | null } | null;
  configuration: {
    configVersion: number | null;
    featureFlags: Record<string, string> | null;
    tenantConfig: Record<string, string> | null;
  } | null;
  approval: { required: string[] };
}

/** The served manifest: storage metadata (digest, frozen moment, the
 * actor who crossed into APPROVED) beside the stored document. */
export interface ReleaseManifest {
  releaseId: string;
  releaseRef: string;
  applicationId: string;
  versionId: string;
  sha256: string;
  frozenAt: string;
  frozenBy: string;
  manifest: ReleaseManifestDocument;
}

/** One side of the diff: the release identity beside its freeze facts. */
export interface ManifestDiffEndpoint {
  releaseId: string;
  releaseRef: string;
  sha256: string;
  frozenAt: string;
}

/** The artifact pin as the diff shows it: content identity and build
 * facts (artifactId/uploadedAt are deliberately absent server-side —
 * sha256/build/size/filename are what distinguish a CHANGED pin). */
export interface ManifestArtifactPin {
  buildNumber: number | null;
  sha256: string;
  sizeBytes: number;
  fileName: string;
}

export interface ManifestArtifactChange {
  change: "ADDED" | "REMOVED" | "CHANGED";
  from: ManifestArtifactPin | null;
  to: ManifestArtifactPin | null;
}

export interface ManifestDiff {
  from: ManifestDiffEndpoint;
  to: ManifestDiffEndpoint;
  artifacts: Record<string, ManifestArtifactChange>;
  targets: {
    added: string[];
    removed: string[];
    groupsChanged: Record<string, { added: string[]; removed: string[] }>;
  };
  ring: { from: string; to: string } | null;
  environment: { from: string; to: string } | null;
  rollout: { from: string; to: string } | null;
  configurationChanges: string[];
}

export function listReleases(params: {
  applicationId: string;
  versionId?: string;
  status?: string;
  tenantId?: string;
  limit?: number;
  cursor?: string;
}): Promise<Page<Release>> {
  return pageFetch<Release>("/releases", { ...params });
}

export function getRelease(applicationId: string, releaseId: string): Promise<ReleaseDetail> {
  return apiFetch<ReleaseDetail>(
    `/releases/${releaseId}?applicationId=${encodeURIComponent(applicationId)}`,
  );
}

/** One PRD 17 crossing. `reason` is mandatory for FAILED and
 * ROLLED_BACK (the release explains itself) and optional elsewhere; the
 * server rejects every illegal edge with the precise 409. */
export function transitionRelease(
  applicationId: string,
  releaseId: string,
  to: ReleaseStatus,
  reason?: string,
): Promise<ReleaseDetail> {
  const trimmed = reason?.trim();
  return apiFetch<ReleaseDetail>(`/releases/${releaseId}/transitions`, {
    method: "POST",
    body: { applicationId, to, ...(trimmed ? { reason: trimmed } : {}) },
  });
}

export function getReleaseHistory(
  applicationId: string,
  releaseId: string,
): Promise<ReleaseHistory> {
  return apiFetch<ReleaseHistory>(
    `/releases/${releaseId}/history?applicationId=${encodeURIComponent(applicationId)}`,
  );
}

/** The promote act's answer (S10-05): the hop's two rings, the tenants
 * moved, whether the hop carried the release onto PRD 17's PROMOTED
 * crossing, and the refreshed per-target rings — the widened
 * distribution read back. */
export interface PromotionResult {
  releaseId: string;
  releaseRef: string;
  status: ReleaseStatus;
  fromRing: string;
  toRing: string;
  tenants: string[];
  crossedToPromoted: boolean;
  promotionId: string;
  promotionStatus: "COMPLETED";
  blockers: Record<string, string>;
  targets: Array<{ tenantId: string; ring: string }>;
}

/** One gate of the target ring's evaluation inside the precheck — the
 * same state the gates block serves, stamped by the hop's target ring. */
export interface PrecheckGateState {
  gateType: string;
  required: boolean;
  status: string;
  observedValue: number | null;
  threshold: number | null;
  evidenceUrl: string | null;
  source: string;
  overridden: boolean;
  overriddenBy: string | null;
  overriddenReason: string | null;
}

/** The approval leg's standing: NOT_REQUIRED (the ring binds nothing),
 * SATISFIED, PENDING (the round it waits on), REJECTED, NOT_OPEN (the
 * promote act opens it), or UNRESOLVED (fail closed, S10-02). */
export interface PrecheckApproval {
  requirement: string;
  policyName: string | null;
  roundNumber: number | null;
  roundStatus: string | null;
  waitedOn: Record<string, string>;
}

/** One target's eligibility row against the hop's target ring (the
 * widening seam's own answer, with the exclusion reasons). */
export interface PrecheckAdmission {
  tenantId: string;
  ring: string;
  admitted: boolean;
  audienceGroups: string[];
  tenantAtLarge: boolean;
  reasons: string[];
}

/** The promotion precheck (issue #44's technical note: one evaluation
 * function, three surfaces): the SAME evaluation the promote act runs,
 * answered as a read. The blockers map is byte-for-byte the 409's
 * errors member — the console's "why can't I promote" answer. */
export interface PromotionPrecheck {
  promotable: boolean;
  blockers: Record<string, string>;
  gates: PrecheckGateState[];
  approval: PrecheckApproval;
  eligibility: PrecheckAdmission[];
}

/** One row of the append-only promotions timeline (PRD 18): a
 * COMPLETED hop or a BLOCKED attempt with its recorded evidence. */
export interface PromotionRecord {
  id: string;
  fromRing: string;
  toRing: string | null;
  tenants: string[];
  status: "COMPLETED" | "BLOCKED";
  blockers: Record<string, string> | null;
  actorId: string;
  occurredAt: string;
}

export interface PromotionHistory {
  releaseId: string;
  releaseRef: string;
  status: ReleaseStatus;
  promotions: PromotionRecord[];
}

/** One governed ring hop (S10-05, FR-018). `tenants` omitted moves
 * every target; `to`, when set, must equal the effective order's next
 * ring (a farther ring answers the skip 409). A refused hop answers
 * 409 RELEASE_PROMOTION_BLOCKED whose errors member is the COMPLETE
 * blocker list. */
export function promoteRelease(
  applicationId: string,
  releaseId: string,
  input: { tenants?: string[]; to?: string } = {},
): Promise<PromotionResult> {
  const tenants = input.tenants?.filter((tenant) => tenant.trim() !== "");
  return apiFetch<PromotionResult>(`/releases/${releaseId}/promote`, {
    method: "POST",
    body: {
      applicationId,
      ...(tenants && tenants.length > 0 ? { tenants } : {}),
      ...(input.to ? { to: input.to } : {}),
    },
  });
}

export function getPromotionPrecheck(
  applicationId: string,
  releaseId: string,
): Promise<PromotionPrecheck> {
  return apiFetch<PromotionPrecheck>(
    `/releases/${releaseId}/promotion-precheck?applicationId=${encodeURIComponent(applicationId)}`,
  );
}

export function getPromotionHistory(
  applicationId: string,
  releaseId: string,
): Promise<PromotionHistory> {
  return apiFetch<PromotionHistory>(
    `/releases/${releaseId}/promotions?applicationId=${encodeURIComponent(applicationId)}`,
  );
}

export function getReleaseManifest(
  applicationId: string,
  releaseId: string,
): Promise<ReleaseManifest> {
  return apiFetch<ReleaseManifest>(
    `/releases/${releaseId}/manifest?applicationId=${encodeURIComponent(applicationId)}`,
  );
}

/** The same stored document as the `application/yaml` compliance render
 * (one document, two serializations; the digest pins both). */
export function getReleaseManifestYaml(
  applicationId: string,
  releaseId: string,
): Promise<string> {
  return apiFetchText(
    `/releases/${releaseId}/manifest?applicationId=${encodeURIComponent(applicationId)}&format=yaml`,
    { accept: "application/yaml" },
  );
}

/** What moved between two APPROVED freezes of the same application:
 * this release is the FROM side, `otherReleaseId` the TO. */
export function getManifestDiff(
  applicationId: string,
  releaseId: string,
  otherReleaseId: string,
): Promise<ManifestDiff> {
  return apiFetch<ManifestDiff>(
    `/releases/${releaseId}/manifest/diff?applicationId=${encodeURIComponent(applicationId)}` +
      `&otherReleaseId=${encodeURIComponent(otherReleaseId)}`,
  );
}
