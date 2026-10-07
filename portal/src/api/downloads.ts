/**
 * Tester downloads (issue #38, S8-04, FR-014): the artifact module's
 * issuance route — the engine's download decision IS the authority
 * (no console permission gates the tester persona), and an ALLOW
 * answers the routed channel and behavior (the server-resolved
 * guidance this screen renders from, never re-derived client-side),
 * the short-lived signed URL, and its expiry. The storage key never
 * appears: signed URLs are the binary's only egress. A denial renders
 * the route's concealed 404 problem — opaque by design (INV-005), so
 * the UI surfaces it exactly like any other problem envelope.
 */
import { apiFetch } from "./client";

/** The behavior the channel routing table resolved for this download. */
export type DownloadBehavior =
  /** Android direct: open the URL after the install-unknown-apps consent guidance. */
  | "OPEN_SIGNED_URL"
  /** iOS direct: ad-hoc/enterprise signing note, then the URL. */
  | "OPEN_SIGNED_URL_ADHOC"
  /** TestFlight placeholder row (V1 routes nothing here; M2/M3 channels arrive). */
  | "DEEP_LINK_TESTFLIGHT";

/** One ALLOWED download issuance (S8-04): the routed URL and its facts. */
export interface DownloadIssuance {
  artifactId: string;
  versionId: string;
  platform: "IOS" | "ANDROID";
  channel: "DIRECT" | "TESTFLIGHT";
  behavior: DownloadBehavior;
  /** Emulator deployments answer an API-relative path; production an absolute URL. */
  url: string;
  /** ISO-8601 instant the URL stops working. */
  expiresAt: string;
  fileName: string | null;
  sizeBytes: number;
}

/**
 * Requests one download issuance for the artifact a history slot
 * named (the slot's artifactId beside its versionId — the addressing
 * the decision already ran against server-side).
 */
export async function requestDownload(artifactId: string, versionId: string): Promise<DownloadIssuance> {
  const params = new URLSearchParams({ version_id: versionId });
  return apiFetch<DownloadIssuance>(`/artifacts/${artifactId}/download?${params.toString()}`);
}
