/** Console feedback surface (S11-01/S11-02, FR-027): the
 * release-scoped reads — the dashboard's Feedback block rollup and the
 * triage queue's drill-down list — and the triage actions (severity
 * classification, assignment, the enforced lifecycle crossings). The
 * server owns every rule: the state machine's legal edges, the
 * mandatory closing note, the security-issue restricted visibility,
 * and the open-critical-bugs metric this surface feeds. */
import { apiFetch, pageFetch, type Page } from "./client";

export type FeedbackType =
  | "BUG"
  | "FEEDBACK"
  | "CRASH"
  | "FEATURE_REQUEST"
  | "UX_ISSUE"
  | "SECURITY_ISSUE";

/** The triage lifecycle (S11-02): OPEN is the born state, TRIAGED the
 * under-review state, RESOLVED/REJECTED the answers, DISMISSED the
 * historical value whose rows re-open through TRIAGED. */
export type FeedbackStatus = "OPEN" | "TRIAGED" | "RESOLVED" | "REJECTED" | "DISMISSED";

export type FeedbackSeverity = "CRITICAL" | "MAJOR" | "MINOR";

/** One report's scan row (the triage queue's list item). */
export interface FeedbackSummary {
  id: string;
  releaseId: string;
  submitterTenantId: string;
  testerId: string;
  type: FeedbackType;
  status: FeedbackStatus;
  severity: FeedbackSeverity | null;
  assigneeUserId: string | null;
  createdAt: string;
}

/** One attachment's metadata (the bytes stream through the download route). */
export interface FeedbackAttachment {
  id: string;
  fileName: string;
  category: "SCREENSHOT" | "VIDEO" | "LOG";
  sizeBytes: number;
}

/** One report in full: the server-attached submission context beside
 * the triage state (severity, assignee, resolution note). */
export interface FeedbackItem {
  id: string;
  releaseId: string;
  releaseRef: string;
  applicationId: string;
  versionId: string;
  artifactId: string | null;
  organizationId: string;
  submitterTenantId: string;
  testerId: string;
  deviceModel: string | null;
  os: string | null;
  type: FeedbackType;
  status: FeedbackStatus;
  severity: FeedbackSeverity | null;
  assigneeUserId: string | null;
  resolutionNote: string | null;
  description: string;
  createdAt: string;
  updatedAt: string;
  attachments: FeedbackAttachment[];
}

/** The dashboard block's counts (PRD 32's "14 reports . 1 critical"):
 * folds by severity (UNTRIAGED for the not-yet-classified) and by
 * status, beside the open-critical count the gate metric answers —
 * each over exactly the rows the caller can see. */
export interface FeedbackRollup {
  releaseId: string;
  total: number;
  openCritical: number;
  bySeverity: Record<string, number>;
  byStatus: Record<string, number>;
}

export function listReleaseFeedback(
  applicationId: string,
  releaseId: string,
  filters: {
    type?: FeedbackType;
    status?: FeedbackStatus;
    severity?: FeedbackSeverity;
    tenantId?: string;
    limit?: number;
    cursor?: string;
  } = {},
): Promise<Page<FeedbackSummary>> {
  return pageFetch<FeedbackSummary>(`/releases/${releaseId}/feedback`, {
    applicationId,
    ...filters,
  });
}

export function getFeedbackRollup(applicationId: string, releaseId: string): Promise<FeedbackRollup> {
  return apiFetch<FeedbackRollup>(
    `/releases/${releaseId}/feedback/rollup?applicationId=${encodeURIComponent(applicationId)}`,
  );
}

export function getFeedbackItem(
  applicationId: string,
  releaseId: string,
  feedbackId: string,
): Promise<FeedbackItem> {
  return apiFetch<FeedbackItem>(
    `/releases/${releaseId}/feedback/${feedbackId}?applicationId=${encodeURIComponent(applicationId)}`,
  );
}

/** Classifies the report (a closed report refuses until re-opened; the
 * crossing to CRITICAL is the fact the gate metric counts). */
export function setFeedbackSeverity(
  applicationId: string,
  releaseId: string,
  feedbackId: string,
  severity: FeedbackSeverity,
): Promise<FeedbackItem> {
  return apiFetch<FeedbackItem>(
    `/releases/${releaseId}/feedback/${feedbackId}/severity?applicationId=${encodeURIComponent(applicationId)}`,
    { method: "POST", body: { severity } },
  );
}

/** Assigns the member who works the report (null clears; the candidate
 * must belong to the owning organization). */
export function assignFeedback(
  applicationId: string,
  releaseId: string,
  feedbackId: string,
  assigneeUserId: string | null,
): Promise<FeedbackItem> {
  return apiFetch<FeedbackItem>(
    `/releases/${releaseId}/feedback/${feedbackId}/assignee?applicationId=${encodeURIComponent(applicationId)}`,
    { method: "POST", body: { assigneeUserId } },
  );
}

/** Moves the report through its lifecycle: OPEN -> TRIAGED ->
 * RESOLVED/REJECTED, every closed state re-openable to TRIAGED; the
 * note is mandatory on the closing crossings. */
export function transitionFeedback(
  applicationId: string,
  releaseId: string,
  feedbackId: string,
  to: FeedbackStatus,
  note?: string,
): Promise<FeedbackItem> {
  return apiFetch<FeedbackItem>(
    `/releases/${releaseId}/feedback/${feedbackId}/transition?applicationId=${encodeURIComponent(applicationId)}`,
    { method: "POST", body: { to, note: note ?? null } },
  );
}

/** The attachment's streaming URL (authorized readers; the security
 * issue's restricted visibility conceals for everyone else). */
export function feedbackAttachmentUrl(
  applicationId: string,
  releaseId: string,
  feedbackId: string,
  attachmentId: string,
): string {
  return (
    `/api/v1/releases/${releaseId}/feedback/${feedbackId}/attachments/${attachmentId}` +
    `?applicationId=${encodeURIComponent(applicationId)}`
  );
}
