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
