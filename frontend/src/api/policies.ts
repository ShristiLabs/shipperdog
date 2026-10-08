/** Release-policy surface client (S10-06 backend, docs/releases.md):
 * the organization-grain declarative rule store (standing BLOCK /
 * HOLD_FOR_REVIEW / ALLOW rules over the typed condition tree — the
 * S10-02 conditional-predicate format: JSON, validated at write),
 * the skip-relaxation settings (the promotion seam's widening knob),
 * and the release-addressed evaluation surfaces: the dry-run (the
 * same evaluation the promote act runs, nothing written), the hold
 * ledger, and the privileged acknowledge. Field shapes mirror the
 * release module's served views exactly. */
import { apiFetch, pageFetch, type Page } from "./client";

/** The typed condition tree: one node kind at a time (the backend's
 * PolicyCondition parse is the single authority — this type renders
 * what the server stores, never a client-side grammar). */
export type PolicyConditionNode = {
  allOf?: PolicyConditionNode[];
  anyOf?: PolicyConditionNode[];
  metric?: string;
  operator?: string;
  value?: number;
  gate?: string;
  status?: string;
  approvalBlocked?: boolean;
  artifactPlatform?: string;
};

/** One rule read back: the scope and its reference, the action, the
 * condition with its human summary, and the defined order. */
export interface PolicyRule {
  id: string;
  organizationId: string;
  name: string;
  scope: "ORGANIZATION" | "APPLICATION" | "RING" | "ENVIRONMENT";
  applicationId: string | null;
  ring: string | null;
  environmentId: string | null;
  action: "BLOCK" | "HOLD_FOR_REVIEW" | "ALLOW";
  condition: PolicyConditionNode;
  conditionSummary: string;
  position: number;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

/** The rule create/replace body: the rule's meaning end to end. */
export interface PolicyRuleInput {
  organizationId: string;
  name: string;
  scope: "ORGANIZATION" | "APPLICATION" | "RING" | "ENVIRONMENT";
  applicationId?: string | null;
  ring?: string | null;
  environmentId?: string | null;
  action: "BLOCK" | "HOLD_FOR_REVIEW" | "ALLOW";
  condition: PolicyConditionNode;
  position: number;
  enabled: boolean;
}

/** One rule's evaluated standing: what fired, what did not, what
 * failed closed on missing data, what a standing acknowledge
 * suppressed. */
export interface PolicyRuleOutcome {
  ruleId: string;
  name: string;
  action: string;
  outcome: "NOT_MET" | "FIRED" | "NO_DATA" | "ACKNOWLEDGED";
  detail: string;
  holdId: string | null;
}

/** The dry-run answer: the ring the next hop would enter, the
 * verdict, and every scoped rule's outcome. */
export interface PolicyDryRun {
  releaseId: string;
  releaseRef: string;
  ring: string;
  verdict: "ALLOWED" | "BLOCKED" | "HELD";
  rules: PolicyRuleOutcome[];
}

/** One hold of the ledger: the rule, the ring it guards, and the
 * acknowledge record when the decision was made. */
export interface PolicyHold {
  id: string;
  ruleId: string;
  ruleName: string | null;
  ring: string;
  status: "PENDING" | "ACKNOWLEDGED";
  evidence: Record<string, unknown> | null;
  acknowledgedBy: string | null;
  acknowledgedAt: string | null;
  acknowledgeReason: string | null;
  createdAt: string;
}

/** The organization's rules in the defined evaluation order (one
 * capped walk collects the whole set — the store is small). */
export async function listPolicyRules(organizationId: string): Promise<PolicyRule[]> {
  const rules: PolicyRule[] = [];
  let cursor: string | undefined;
  let pages = 0;
  do {
    const page = await pageFetch<PolicyRule>("/release-policies", { organizationId, limit: 100, cursor });
    rules.push(...page.items);
    cursor = page.nextCursor;
    pages += 1;
  } while (cursor !== undefined && pages < 20);
  return rules;
}

/** The one-item permission probe (the nav section's concealment
 * check — the sibling probes' shape, never the whole store). */
export function probePolicyRules(organizationId: string): Promise<Page<PolicyRule>> {
  return pageFetch<PolicyRule>("/release-policies", { organizationId, limit: 1 });
}

export function getPolicyRule(organizationId: string, ruleId: string): Promise<PolicyRule> {
  return apiFetch<PolicyRule>(`/release-policies/${ruleId}?organizationId=${encodeURIComponent(organizationId)}`);
}

export function createPolicyRule(input: PolicyRuleInput): Promise<PolicyRule> {
  return apiFetch<PolicyRule>("/release-policies", { method: "POST", body: input });
}

export function updatePolicyRule(organizationId: string, ruleId: string, input: PolicyRuleInput): Promise<PolicyRule> {
  return apiFetch<PolicyRule>(`/release-policies/${ruleId}?organizationId=${encodeURIComponent(organizationId)}`, {
    method: "PUT",
    body: input,
  });
}

export function deletePolicyRule(organizationId: string, ruleId: string): Promise<void> {
  return apiFetch<void>(`/release-policies/${ruleId}?organizationId=${encodeURIComponent(organizationId)}`, {
    method: "DELETE",
  });
}

/** The skip-relaxation standing: the named rings a promotion may
 * carry beyond the next (absent settings answer the empty set). */
export function getSkipAllowances(organizationId: string): Promise<{ organizationId: string; rings: string[] }> {
  return apiFetch<{ organizationId: string; rings: string[] }>(
    `/release-policies/skip-allowances?organizationId=${encodeURIComponent(organizationId)}`,
  );
}

export function replaceSkipAllowances(organizationId: string, rings: string[]): Promise<{ rings: string[] }> {
  return apiFetch<{ rings: string[] }>("/release-policies/skip-allowances", {
    method: "PUT",
    body: { organizationId, rings },
  });
}

/** The on-demand dry-run: the same evaluation the promote act runs,
 * answered as a read (nothing written — no log row, no hold). */
export function dryRunPolicy(applicationId: string, releaseId: string): Promise<PolicyDryRun> {
  return apiFetch<PolicyDryRun>(`/releases/${releaseId}/policy-dry-run?applicationId=${encodeURIComponent(applicationId)}`);
}

/** The release's hold ledger: the pending demands and the
 * acknowledged history. */
export function listPolicyHolds(applicationId: string, releaseId: string): Promise<{ holds: PolicyHold[] }> {
  return apiFetch<{ holds: PolicyHold[] }>(
    `/releases/${releaseId}/policy-holds?applicationId=${encodeURIComponent(applicationId)}`,
  );
}

/** The explicit privileged acknowledge: the mandatory reason rides
 * the body; the audited decision stands for later hops into the same
 * ring. */
export function acknowledgePolicyHold(
  applicationId: string,
  releaseId: string,
  holdId: string,
  reason: string,
): Promise<PolicyHold> {
  return apiFetch<PolicyHold>(`/releases/${releaseId}/policy-holds/${holdId}/acknowledge`, {
    method: "POST",
    body: { applicationId, reason },
  });
}
