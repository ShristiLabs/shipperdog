/** ABAC policy surface client (S14-04 backend, docs/authorization.md):
 * the organization-grain attribute-policy store — versioned
 * deny-override refinement rules over the typed condition tree (the
 * shared expression format the conditional approvals and release
 * policies use: JSON, validated at write, never a string expression
 * language) — and the immutable version history every create and
 * replace appends. Field shapes mirror the authorization module's
 * served views exactly. */
import { apiFetch, pageFetch, type Page } from "./client";

/** The typed condition tree: one node kind at a time (the backend's
 * AbacCondition parse is the single authority — this type renders
 * what the server stores, never a client-side grammar). */
export type AbacConditionNode = {
  allOf?: AbacConditionNode[];
  anyOf?: AbacConditionNode[];
  subjectOrgRole?: string;
  subjectPlatformAdmin?: boolean;
  subjectLinkedTester?: boolean;
  applicationName?: string;
  applicationPlatform?: string;
  environmentName?: string;
  ringCode?: string;
  securityStatus?: string;
};

/** One policy read back: the current standing with the condition and
 * its human summary. */
export interface AbacPolicy {
  id: string;
  organizationId: string;
  name: string;
  version: number;
  effect: "DENY" | "REQUIRE";
  action: "LIST_APPLICATIONS" | "READ_ARTIFACT" | "DOWNLOAD" | "PROMOTE";
  applicationId: string | null;
  environmentId: string | null;
  ring: string | null;
  condition: AbacConditionNode;
  conditionSummary: string;
  position: number;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

/** The policy create/replace body: the policy's meaning end to end. */
export interface AbacPolicyInput {
  organizationId: string;
  name: string;
  effect: "DENY" | "REQUIRE";
  action: "LIST_APPLICATIONS" | "READ_ARTIFACT" | "DOWNLOAD" | "PROMOTE";
  applicationId?: string | null;
  environmentId?: string | null;
  ring?: string | null;
  condition: AbacConditionNode;
  position: number;
  enabled: boolean;
}

/** One immutable history row: the complete authored facts with the
 * author and the moment (rows survive the policy's deletion). */
export interface AbacPolicyVersion {
  id: string;
  policyId: string;
  name: string;
  version: number;
  effect: string;
  action: string;
  applicationId: string | null;
  environmentId: string | null;
  ring: string | null;
  condition: AbacConditionNode;
  conditionSummary: string;
  position: number;
  enabled: boolean;
  authoredBy: string;
  createdAt: string;
}

/** The organization's policies in the defined evaluation order (one
 * capped walk collects the whole set — the store is small). */
export async function listAbacPolicies(organizationId: string): Promise<AbacPolicy[]> {
  const policies: AbacPolicy[] = [];
  let cursor: string | undefined;
  let pages = 0;
  do {
    const page = await pageFetch<AbacPolicy>("/abac-policies", { organizationId, limit: 100, cursor });
    policies.push(...page.items);
    cursor = page.nextCursor;
    pages += 1;
  } while (cursor !== undefined && pages < 20);
  return policies;
}

/** The one-item permission probe (the nav section's concealment
 * check — the sibling probes' shape, never the whole store). */
export function probeAbacPolicies(organizationId: string): Promise<Page<AbacPolicy>> {
  return pageFetch<AbacPolicy>("/abac-policies", { organizationId, limit: 1 });
}

export function getAbacPolicy(organizationId: string, policyId: string): Promise<AbacPolicy> {
  return apiFetch<AbacPolicy>(`/abac-policies/${policyId}?organizationId=${encodeURIComponent(organizationId)}`);
}

export async function listAbacPolicyVersions(
  organizationId: string,
  policyId: string,
): Promise<AbacPolicyVersion[]> {
  const versions: AbacPolicyVersion[] = [];
  let cursor: string | undefined;
  let pages = 0;
  do {
    const page = await pageFetch<AbacPolicyVersion>(`/abac-policies/${policyId}/versions`, {
      organizationId,
      limit: 100,
      cursor,
    });
    versions.push(...page.items);
    cursor = page.nextCursor;
    pages += 1;
  } while (cursor !== undefined && pages < 20);
  return versions;
}

export function createAbacPolicy(input: AbacPolicyInput): Promise<AbacPolicy> {
  return apiFetch<AbacPolicy>("/abac-policies", { method: "POST", body: input });
}

export function updateAbacPolicy(
  organizationId: string,
  policyId: string,
  input: AbacPolicyInput,
): Promise<AbacPolicy> {
  return apiFetch<AbacPolicy>(`/abac-policies/${policyId}?organizationId=${encodeURIComponent(organizationId)}`, {
    method: "PUT",
    body: input,
  });
}

export function deleteAbacPolicy(organizationId: string, policyId: string): Promise<void> {
  return apiFetch<void>(`/abac-policies/${policyId}?organizationId=${encodeURIComponent(organizationId)}`, {
    method: "DELETE",
  });
}
