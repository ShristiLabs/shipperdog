/** Distribution adapter-framework client (S13-01 backend,
 * docs/distribution.md): the channel catalog (the registered adapters
 * and their capabilities), the credential-delegation chain's bindings
 * (bind at any level, inherit downward, first-defined-wins), and one
 * release's distribution records (adapter-driven lifecycle with the
 * external ids stores reconcile by). Secret material enters exactly
 * once through the binding write; every answer carries the opaque
 * reference, never material. */
import { apiFetch, pageFetch } from "./client";

/** One registered channel adapter as the catalog serves it. */
export interface Channel {
  channelId: string;
  displayName: string;
  description: string;
  capabilities: string[];
  credentialProviders: string[];
}

/** One store-account binding of the delegation chain (references only). */
export interface StoreAccount {
  id: string;
  level: "organization" | "application" | "environment";
  organizationId: string;
  applicationId: string | null;
  environmentId: string | null;
  provider: string;
  name: string;
  metadata: string | null;
  secretReference: string;
  createdAt: string;
  updatedAt: string;
}

/** The binding write's body: the level's parents, the provider, the
 * label, and the material -- which no answer ever echoes. */
export interface StoreAccountInput {
  organizationId: string;
  applicationId?: string | null;
  environmentId?: string | null;
  provider: string;
  name: string;
  metadata?: Record<string, string>;
  secretMaterial: string;
}

/** One distribution record of a release (the adapter's report plus
 * the framework's sandbox bookkeeping; since S13-02 the provider's own
 * lifecycle word -- Apple's beta-review states -- rides beside the
 * status). */
export interface DistributionRecord {
  id: string;
  releaseId: string;
  artifactId: string;
  channel: string;
  status: "PENDING" | "DISTRIBUTING" | "DISTRIBUTED" | "FAILED";
  externalId: string | null;
  failureCode: string | null;
  failureDetail: string | null;
  providerStatus: string | null;
  providerStatusAt: string | null;
  attempts: number;
  lastAttemptAt: string | null;
  distributedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

/** The channel catalog: what is registered and what it can do. */
export function listChannels(organizationId: string): Promise<{ channels: Channel[] }> {
  return apiFetch(`/distribution/channels?organizationId=${organizationId}`);
}

/** The organization's bindings across the chain's levels. */
export function listStoreAccounts(
  organizationId: string,
  params: { limit?: number; cursor?: string } = {},
): Promise<{ items: StoreAccount[]; nextCursor?: string }> {
  return pageFetch<StoreAccount>("/distribution/credentials", { organizationId, ...params });
}

/** Binds (or rotates) one provider's account at a level; material is
 * stored once through the secret store and never echoed. */
export function bindStoreAccount(input: StoreAccountInput): Promise<StoreAccount> {
  return apiFetch("/distribution/credentials", { method: "PUT", body: input });
}

/** Removes one binding; the scope falls back to the next level's row. */
export function deleteStoreAccount(organizationId: string, credentialId: string): Promise<void> {
  return apiFetch(`/distribution/credentials/${credentialId}?organizationId=${organizationId}`, {
    method: "DELETE",
  });
}

/** The resolution probe: whether the binding's reference resolves --
 * never what it resolves to. */
export function verifyStoreAccount(
  organizationId: string,
  credentialId: string,
): Promise<{ resolved: boolean }> {
  return apiFetch(`/distribution/credentials/${credentialId}/verify?organizationId=${organizationId}`, {
    method: "POST",
  });
}

/** One release's distribution records (reconciliation view). */
export function listDistributionRecords(
  organizationId: string,
  releaseId: string,
): Promise<{ items: DistributionRecord[]; nextCursor?: string }> {
  return pageFetch<DistributionRecord>("/distribution/records", {
    organizationId,
    releaseId,
    limit: 100,
  });
}

/** One record's refresh from its provider (S13-02, AC4): the adapter's
 * status observation lands on the record -- the beta-review answer a
 * Release Manager refreshes from App Store Connect. */
export function refreshDistributionRecord(
  organizationId: string,
  recordId: string,
): Promise<DistributionRecord> {
  return apiFetch(
    `/distribution/records/${recordId}/refresh?organizationId=${organizationId}`,
    {
      method: "POST",
    },
  );
}

/** One tester-group link of the TestFlight mapping surface (S13-02, AC2). */
export interface TestFlightGroupLink {
  id: string;
  organizationId: string;
  tenantId: string;
  groupId: string;
  groupName: string;
  channel: string;
  externalGroupId: string;
  externalGroupName: string;
  lastSyncedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

/** The sync's verdict (AC5): what was pushed and the drift reported
 * (provider-side facts ShipperDog does not own -- reported, never
 * removed). */
export interface TestFlightSyncView {
  link: TestFlightGroupLink;
  pushedEmails: string[];
  driftTesterEmails: string[];
  driftBuildIds: string[];
}

/** The organization's TestFlight group links. */
export function listTestFlightGroups(
  organizationId: string,
): Promise<{ items: TestFlightGroupLink[]; nextCursor?: string }> {
  return pageFetch<TestFlightGroupLink>("/distribution/testflight/groups", {
    organizationId,
    limit: 100,
  });
}

/** Links a tester group to an ASC beta group: an existing same-named
 * group wins; createOnDemand mints one; the link lands with its first
 * idempotent membership push. */
export function linkTestFlightGroup(input: {
  organizationId: string;
  applicationId: string;
  groupId: string;
  createOnDemand: boolean;
}): Promise<TestFlightGroupLink> {
  return apiFetch("/distribution/testflight/groups", {
    method: "PUT",
    body: input,
  });
}

/** The idempotent re-sync: pushes membership, reports drift. */
export function syncTestFlightGroup(
  organizationId: string,
  linkId: string,
): Promise<TestFlightSyncView> {
  return apiFetch(
    `/distribution/testflight/groups/${linkId}/sync?organizationId=${organizationId}`,
    {
      method: "POST",
    },
  );
}

/** Removes one link (the ShipperDog side only; the ASC group stays). */
export function unlinkTestFlightGroup(
  organizationId: string,
  linkId: string,
): Promise<void> {
  return apiFetch(
    `/distribution/testflight/groups/${linkId}?organizationId=${organizationId}`,
    {
      method: "DELETE",
    },
  );
}

/** One authored App Store listing locale (S13-03, AC1): the metadata
 * the release manifest freezes at APPROVED and the submission sends --
 * the manifest is the immutable copy of what was sent, these rows the
 * authoring surface. */
export interface AppStoreMetadata {
  id: string;
  organizationId: string;
  applicationId: string;
  channel: string;
  locale: string;
  description: string;
  keywords: string | null;
  screenshotRefs: string[];
  releaseNotes: string | null;
  createdAt: string;
  updatedAt: string;
}

/** The release's phased-release curve (S13-03, AC3): the provider's
 * curve state, the curve's current day of Apple's fixed 7-day
 * availability curve, and the day's percentage. */
export interface PhasedReleaseView {
  versionId: string;
  versionState: string | null;
  phasedReleaseState: string | null;
  currentDay: number;
  totalDays: number;
  percentage: number;
  observedAt: string;
}

/** The organization's authored App Store listing metadata. */
export function listAppStoreMetadata(
  organizationId: string,
): Promise<{ items: AppStoreMetadata[]; nextCursor?: string }> {
  return pageFetch<AppStoreMetadata>("/distribution/appstore/metadata", {
    organizationId,
    limit: 100,
  });
}

/** Authors (or rotates) one locale's listing metadata: a second write
 * to the same locale replaces the row whole. */
export function putAppStoreMetadata(input: {
  organizationId: string;
  applicationId: string;
  locale: string;
  description: string;
  keywords?: string | null;
  screenshotRefs?: string[];
  releaseNotes?: string | null;
}): Promise<AppStoreMetadata> {
  return apiFetch("/distribution/appstore/metadata", { method: "PUT", body: input });
}

/** Removes one authored locale (the authoring surface; a frozen
 * manifest block keeps its copy). */
export function deleteAppStoreMetadata(organizationId: string, metadataId: string): Promise<void> {
  return apiFetch(`/distribution/appstore/metadata/${metadataId}?organizationId=${organizationId}`, {
    method: "DELETE",
  });
}

/** The release's phased-release curve for the dashboard. */
export function getPhasedRelease(
  organizationId: string,
  releaseId: string,
): Promise<PhasedReleaseView> {
  return apiFetch(
    `/distribution/appstore/releases/${releaseId}/phased-release?organizationId=${organizationId}`,
  );
}

/** One release action on the curve: pause, resume, or halt (halt
 * completes the curve immediately -- the documented rollback posture
 * of the production channel). */
export function controlPhasedRelease(
  organizationId: string,
  releaseId: string,
  action: "PAUSE" | "RESUME" | "HALT",
): Promise<PhasedReleaseView> {
  return apiFetch(
    `/distribution/appstore/releases/${releaseId}/phased-release?organizationId=${organizationId}`,
    { method: "POST", body: { action } },
  );
}

/** One tester-group link of the Play mapping surface (S13-04, AC2): the
 * provider-side id is the TRACK NAME the group's email list syncs to. */
export interface PlayGroupLink {
  id: string;
  organizationId: string;
  tenantId: string;
  applicationId: string;
  groupId: string;
  groupName: string;
  channel: string;
  externalGroupId: string;
  externalGroupName: string;
  lastSyncedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

/** The Play sync's verdict (AC4): the emails added and the emails
 * removed -- BOTH reflect on the track's list, the Play contract. */
export interface PlaySyncView {
  link: PlayGroupLink;
  addedEmails: string[];
  removedEmails: string[];
}

/** The organization's Play group links. */
export function listPlayGroups(
  organizationId: string,
): Promise<{ items: PlayGroupLink[]; nextCursor?: string }> {
  return pageFetch<PlayGroupLink>("/distribution/play/groups", {
    organizationId,
    limit: 100,
  });
}

/** Links a tester group to a Play testing track: the write validates the
 * app carries the track and refuses the production track. */
export function linkPlayGroup(input: {
  organizationId: string;
  applicationId: string;
  groupId: string;
  track: string;
}): Promise<PlayGroupLink> {
  return apiFetch("/distribution/play/groups", {
    method: "PUT",
    body: input,
  });
}

/** The tester-list sync: the track's email list becomes exactly the
 * linked groups' members (adds AND removes both reflect). */
export function syncPlayGroup(organizationId: string, linkId: string): Promise<PlaySyncView> {
  return apiFetch(`/distribution/play/groups/${linkId}/sync?organizationId=${organizationId}`, {
    method: "POST",
  });
}

/** Removes one link (the ShipperDog side only; the Play track stays). */
export function unlinkPlayGroup(organizationId: string, linkId: string): Promise<void> {
  return apiFetch(`/distribution/play/groups/${linkId}?organizationId=${organizationId}`, {
    method: "DELETE",
  });
}

/** One authored Play release-notes locale (S13-04, AC2): the what's-new
 * the release manifest freezes at APPROVED and the track release sends. */
export interface PlayReleaseNotes {
  id: string;
  organizationId: string;
  applicationId: string;
  channel: string;
  locale: string;
  notes: string;
  createdAt: string;
  updatedAt: string;
}

/** The app's Play track model (AC2/AC5): every track with its releases,
 * Google's own status words verbatim. */
export interface PlayTrackView {
  track: string;
  releases: Array<{
    name: string | null;
    versionCodes: number[];
    releaseNotes: Array<{ language: string | null; text: string | null }>;
    status: string | null;
  }>;
}

/** The organization's authored Play release notes. */
export function listPlayReleaseNotes(
  organizationId: string,
): Promise<{ items: PlayReleaseNotes[]; nextCursor?: string }> {
  return pageFetch<PlayReleaseNotes>("/distribution/play/release-notes", {
    organizationId,
    limit: 100,
  });
}

/** Authors (or rotates) one locale's release notes: a second write to
 * the same locale replaces the row whole. */
export function putPlayReleaseNotes(input: {
  organizationId: string;
  applicationId: string;
  locale: string;
  notes: string;
}): Promise<PlayReleaseNotes> {
  return apiFetch("/distribution/play/release-notes", { method: "PUT", body: input });
}

/** Removes one authored locale (the authoring surface; a frozen manifest
 * block keeps its copy). */
export function deletePlayReleaseNotes(organizationId: string, notesId: string): Promise<void> {
  return apiFetch(`/distribution/play/release-notes/${notesId}?organizationId=${organizationId}`, {
    method: "DELETE",
  });
}

/** The app's Play tracks with their releases (the listing console read). */
export function listPlayTracks(
  organizationId: string,
  applicationId: string,
): Promise<{ tracks: PlayTrackView[] }> {
  return apiFetch(
    `/distribution/play/tracks?organizationId=${organizationId}&applicationId=${applicationId}`,
  );
}
