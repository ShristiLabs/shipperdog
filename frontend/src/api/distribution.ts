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
 * the framework's sandbox bookkeeping). */
export interface DistributionRecord {
  id: string;
  releaseId: string;
  artifactId: string;
  channel: string;
  status: "PENDING" | "DISTRIBUTING" | "DISTRIBUTED" | "FAILED";
  externalId: string | null;
  failureCode: string | null;
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
