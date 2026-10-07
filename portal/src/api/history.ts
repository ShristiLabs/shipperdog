/**
 * The tester's version history (issue #37, S8-03, FR-013): the
 * decision API's eligibility listing — the authorized version set the
 * application screen renders, server-computed like the catalog (PRD
 * 26: never a client-side filter), newest first by the version sort
 * key, each entry with its status badge facts and its per-platform
 * installable slots (build number, size, upload date). INV-006 rides
 * the server's grant semantics: ARCHIVED and REVOKED versions stay
 * listed while a live grant covers them, and grant revocation drops a
 * version from view on the next read (deny-by-default).
 */
import { apiFetch } from "./client";

/** The application facts the history header renders (deep links cannot know them from the card). */
export interface HistoryApplication {
  applicationId: string;
  name: string;
  status: string;
}

/** One platform row of a history entry: the newest artifact a download would serve. */
export interface HistoryPlatformSlot {
  platform: "IOS" | "ANDROID";
  artifactId: string;
  buildNumber: number | null;
  sizeBytes: number;
  uploadedAt: string;
}

/** One authorized version of the application. */
export interface HistoryEntry {
  versionId: string;
  versionName: string;
  status: string;
  expiresAt: string | null;
  platforms: HistoryPlatformSlot[];
}

/** One cursor page of the version history. */
export interface HistoryPage {
  tenantId: string;
  applicationId: string;
  application: HistoryApplication;
  effectiveAccess: boolean;
  resolvedAt: string;
  entries: HistoryEntry[];
  next_cursor?: string;
}

/** The history page's fixed default (the story's technical note: testers rarely scroll past 20). */
export const HISTORY_PAGE_SIZE = 20;

const HISTORY_STALE_TIME_MS = 30_000;
export { HISTORY_STALE_TIME_MS };

/** Fetches one cursor page of the caller's authorized versions of the application. */
export async function fetchHistoryPage(
  tenantId: string,
  applicationId: string,
  options: { limit?: number; cursor?: string } = {},
): Promise<HistoryPage> {
  const params = new URLSearchParams({ tenantId, applicationId });
  params.set("limit", String(options.limit ?? HISTORY_PAGE_SIZE));
  if (options.cursor) params.set("cursor", options.cursor);
  return apiFetch<HistoryPage>(`/authorization/decisions/eligibility?${params.toString()}`);
}

/**
 * The deep-link probe (AC5): resolves exactly one version when it is
 * in the caller's authorized set, and throws the problem envelope's
 * 404 otherwise — unknown, foreign, and unauthorized ids are the same
 * opaque miss (§12), so the version page can render its 404 without
 * learning why.
 */
export async function fetchVersionEntry(
  tenantId: string,
  applicationId: string,
  versionId: string,
): Promise<HistoryPage> {
  const params = new URLSearchParams({ tenantId, applicationId, versionId });
  return apiFetch<HistoryPage>(`/authorization/decisions/eligibility?${params.toString()}`);
}
