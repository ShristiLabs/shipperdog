/** Audit-stream-surface client (S14-05 backend, docs/siem.md): the
 * org-scoped SIEM export configuration (HTTPS collectors and bucket
 * batches — the org admin's routing of the immutable audit trail
 * off-platform), the delivery ledger with its visible dead-letter and
 * replay, the bucket batch history, and the test action. Secrets are
 * references only: create and update accept the reference strings,
 * no view ever carries material. */
import { apiFetch, pageFetch } from "./client";

/** The export's transport leg: the signed real-time POST, or the
 * periodic bucket batch. */
export type AuditStreamKind = "HTTPS" | "BUCKET";

/** The HTTPS leg's payload shape (the frozen envelope's wrapper). */
export type AuditStreamFormat = "SHIPPERDOG_JSON" | "SPLUNK_HEC";

/** One export as the API serves it — the reference strings included
 * (they are the configuration), the resolved material never. */
export interface AuditStreamExport {
  id: string;
  organizationId: string;
  kind: AuditStreamKind;
  format: AuditStreamFormat | null;
  eventTypes: string[];
  destinationUrl: string | null;
  bucketUri: string | null;
  signingSecretRef: string;
  authHeaderName: string | null;
  authSecretRef: string | null;
  enabled: boolean;
  watermark: string | null;
  batchSeq: number;
  batchesWritten: number;
  eventsExported: number;
  createdAt: string;
  updatedAt: string;
}

export interface AuditStreamCreateInput {
  kind: AuditStreamKind;
  format?: AuditStreamFormat;
  eventTypes?: string[];
  destinationUrl?: string;
  bucketUri?: string;
  signingSecretRef: string;
  authHeaderName?: string;
  authSecretRef?: string;
  initialWatermark?: string;
}

export interface AuditStreamUpdateInput {
  format?: AuditStreamFormat;
  eventTypes?: string[];
  destinationUrl?: string;
  bucketUri?: string;
  signingSecretRef?: string;
  authHeaderName?: string;
  authSecretRef?: string;
  enabled?: boolean;
}

/** One HTTPS delivery-ledger row: the dead-letter surface the log renders. */
export interface AuditStreamDelivery {
  id: string;
  eventId: string;
  exportId: string;
  format: AuditStreamFormat;
  status: "SCHEDULED" | "DELIVERED" | "FAILED";
  attempts: number;
  lastError: string | null;
  deliveredAt: string | null;
  createdAt: string;
}

/** One bucket batch row: the completeness ledger (seq, window, count, digest). */
export interface AuditStreamBatch {
  id: string;
  seq: number;
  watermarkFrom: string | null;
  watermarkTo: string;
  eventCount: number;
  batchSha256: string;
  objectUri: string;
  manifestUri: string;
  createdAt: string;
}

const BASE = (organizationId: string) =>
  `/organizations/${encodeURIComponent(organizationId)}/audit-stream-exports`;

/** The organization's exports, newest first, one capped walk. */
export async function fetchAuditStreamExports(organizationId: string): Promise<AuditStreamExport[]> {
  const exports: AuditStreamExport[] = [];
  let cursor: string | undefined;
  let pages = 0;
  do {
    const page = await pageFetch<AuditStreamExport>(BASE(organizationId), { limit: 100, cursor });
    exports.push(...page.items);
    cursor = page.nextCursor;
    pages += 1;
  } while (cursor !== undefined && pages < 20);
  return exports;
}

export function getAuditStreamExport(
  organizationId: string,
  exportId: string,
): Promise<AuditStreamExport> {
  return apiFetch<AuditStreamExport>(`${BASE(organizationId)}/${exportId}`);
}

/** The nav probe (the rings pattern): one-item listing — org:audit-stream:read
 * holders (organization administrators alone, AC5) pass, everyone else is
 * conceal-denied and the section hides. */
export function getAuditStreamProbe(organizationId: string): Promise<{ items: AuditStreamExport[] }> {
  return apiFetch<{ items: AuditStreamExport[] }>(`${BASE(organizationId)}?limit=1`);
}

export function createAuditStreamExport(
  organizationId: string,
  input: AuditStreamCreateInput,
): Promise<AuditStreamExport> {
  return apiFetch<AuditStreamExport>(BASE(organizationId), { method: "POST", body: input });
}

export function updateAuditStreamExport(
  organizationId: string,
  exportId: string,
  input: AuditStreamUpdateInput,
): Promise<AuditStreamExport> {
  return apiFetch<AuditStreamExport>(`${BASE(organizationId)}/${exportId}`, {
    method: "PATCH",
    body: input,
  });
}

export function deleteAuditStreamExport(organizationId: string, exportId: string): Promise<void> {
  return apiFetch<void>(`${BASE(organizationId)}/${exportId}`, { method: "DELETE" });
}

/** The export's deliveries, newest first, optionally narrowed by status. */
export async function fetchAuditStreamDeliveries(
  organizationId: string,
  exportId: string,
  status?: AuditStreamDelivery["status"],
): Promise<AuditStreamDelivery[]> {
  const deliveries: AuditStreamDelivery[] = [];
  let cursor: string | undefined;
  let pages = 0;
  const params: Record<string, string> = { limit: "100" };
  if (status) params.status = status;
  do {
    const query = { ...params, ...(cursor ? { cursor } : {}) };
    const page = await pageFetch<AuditStreamDelivery>(
      `${BASE(organizationId)}/${exportId}/deliveries`,
      query,
    );
    deliveries.push(...page.items);
    cursor = page.nextCursor;
    pages += 1;
  } while (cursor !== undefined && pages < 20);
  return deliveries;
}

/** The bucket export's batches, newest sequence first. */
export async function fetchAuditStreamBatches(
  organizationId: string,
  exportId: string,
): Promise<AuditStreamBatch[]> {
  const batches: AuditStreamBatch[] = [];
  let cursor: string | undefined;
  let pages = 0;
  do {
    const query: Record<string, string> = { limit: "100", ...(cursor ? { cursor } : {}) };
    const page = await pageFetch<AuditStreamBatch>(`${BASE(organizationId)}/${exportId}/batches`, query);
    batches.push(...page.items);
    cursor = page.nextCursor;
    pages += 1;
  } while (cursor !== undefined && pages < 20);
  return batches;
}

/** The replay action's outcome: how many dead-letter rows returned to
 * the retry schedule (AC3's operator answer). */
export function replayAuditStreamDeadLetter(
  organizationId: string,
  exportId: string,
): Promise<{ exportId: string; replayed: number }> {
  return apiFetch<{ exportId: string; replayed: number }>(`${BASE(organizationId)}/${exportId}/replay`, {
    method: "POST",
    body: {},
  });
}

/** The test action's outcome: the synchronous attempt through the real
 * pipeline — delivered honestly, or the error the integrator works. */
export interface AuditStreamTestResult {
  delivered: boolean;
  kind: AuditStreamKind;
  error: string | null;
  sentAt: string;
}

export function testAuditStreamExport(
  organizationId: string,
  exportId: string,
): Promise<AuditStreamTestResult> {
  return apiFetch<AuditStreamTestResult>(`${BASE(organizationId)}/${exportId}/test`, {
    method: "POST",
    body: {},
  });
}
