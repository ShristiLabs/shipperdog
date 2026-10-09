/**
 * The feedback submission contract (issue #46, S11-01, FR-027, PRD
 * 8.13): the one POST the portal's "Report issue" surface sends — the
 * SAME contract the S15-01 in-app SDK will POST from inside a shipped
 * build (one API, two clients), so the field set is exactly what a
 * tester types or chooses: the report type, the free-text
 * description, optional reported device/OS, and optional
 * attachments. Every identity field (tester, submitting tenant) and
 * the whole release context resolve SERVER-side from the session and
 * the application/version/tenant link the portal screen already
 * addresses — the client cannot spoof what it never sends (AC2).
 */
import { apiFetch } from "./client";

/** PRD 8.13's six report types, the API's stable string vocabulary (AC1). */
export const FEEDBACK_TYPES = ["BUG", "FEEDBACK", "CRASH", "FEATURE_REQUEST", "UX_ISSUE", "SECURITY_ISSUE"] as const;

export type FeedbackType = (typeof FEEDBACK_TYPES)[number];

/** The tester-facing labels of the six types (select options and success copy). */
export const FEEDBACK_TYPE_LABELS: Record<FeedbackType, string> = {
  BUG: "Bug",
  FEEDBACK: "Feedback",
  CRASH: "Crash",
  FEATURE_REQUEST: "Feature request",
  UX_ISSUE: "UX issue",
  SECURITY_ISSUE: "Security issue",
};

/** The attachment classes the server accepts (AC3): extension-resolved, never a client declaration. */
export const FEEDBACK_ATTACHMENT_EXTENSIONS = [".png", ".jpg", ".jpeg", ".mp4", ".txt", ".log"] as const;

/** The server's per-report file-count bound (the configured default; the server's 422 is the authority). */
export const FEEDBACK_MAX_ATTACHMENTS = 5;

/** The stored report the 201 answer returns: the server-attached context in full. */
export interface FeedbackReport {
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
  status: string;
  description: string;
  createdAt: string;
  attachments: { id: string; fileName: string; category: string; sizeBytes: number }[];
}

/** The submission's screen context: the link the portal page already holds. */
export interface FeedbackContext {
  tenantId: string;
  applicationId: string;
  versionId: string;
  artifactId?: string;
}

/** The tester-typed fields (AC1): nothing here is an identity the server resolves. */
export interface FeedbackSubmission {
  type: FeedbackType;
  description: string;
  deviceModel?: string;
  os?: string;
}

/**
 * Submits one report: multipart (the artifact upload route's shape)
 * because attachments ride the same request. Snake_case field names
 * match the server contract exactly; the artifact link is included
 * only when the tester named the build they were looking at.
 */
export async function submitFeedback(
  context: FeedbackContext,
  submission: FeedbackSubmission,
  files: File[],
): Promise<FeedbackReport> {
  const form = new FormData();
  form.set("application_id", context.applicationId);
  form.set("version_id", context.versionId);
  form.set("tenant_id", context.tenantId);
  if (context.artifactId) {
    form.set("artifact_id", context.artifactId);
  }
  form.set("type", submission.type);
  form.set("description", submission.description);
  if (submission.deviceModel) {
    form.set("device_model", submission.deviceModel);
  }
  if (submission.os) {
    form.set("os", submission.os);
  }
  for (const file of files) {
    form.append("attachments", file, file.name);
  }
  return apiFetch<FeedbackReport>("/feedback", { method: "POST", form });
}

/**
 * The reported OS the portal prefills (PRD 8.13's OS attribute as
 * free text): derived from the browser's user agent — the closest
 * honest default a portal has (the in-app SDK will report the real
 * one). Always editable: the tester knows their OS better than the
 * UA string does.
 */
export function deriveOsFromUserAgent(userAgent: string): string {
  if (/iPhone|iPad|iPod/i.test(userAgent)) {
    const matched = userAgent.match(/OS (\d+[_\d]*)/i);
    return matched?.[1] ? `iOS ${matched[1].replace(/_/g, ".")}` : "iOS";
  }
  if (/Android/i.test(userAgent)) {
    const matched = userAgent.match(/Android ([\d.]+)/i);
    return matched?.[1] ? `Android ${matched[1]}` : "Android";
  }
  return "";
}
