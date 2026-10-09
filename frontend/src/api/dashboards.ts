/** Dashboard API (docs/dashboards.md, S11-05): the organization and
 * tenant landing views of PRD 29 — read-only aggregates the console
 * home screens render and auto-refresh. */
import { apiFetch } from "./client";

/** The release-health derivation's vocabulary (the server's rule,
 * mirrored for rendering only): BLOCKED outranks TESTING outranks
 * HEALTHY; INACTIVE is no release in flight. */
export type ApplicationHealth = "HEALTHY" | "TESTING" | "BLOCKED" | "INACTIVE";

export interface OrgDashboardCounts {
  applications: number;
  tenants: number;
  activeReleases: number;
  pendingApprovals: number;
  testing: number;
  production: number;
}

export interface ApplicationHealthRow {
  applicationId: string;
  name: string;
  health: ApplicationHealth;
  activeReleases: number;
  testing: number;
  production: number;
  blockedFindings: number;
}

/** One pending approval step with its deep-link context and the
 * caller's decidable flag (the server computes both). */
export interface ApprovalStep {
  releaseId: string;
  releaseRef: string;
  applicationId: string;
  applicationName: string;
  approvalType: string;
  roundNumber: number;
  scopeTenant: string | null;
  decidableByCaller: boolean;
}

export interface FailedGate {
  releaseId: string;
  releaseRef: string;
  applicationId: string;
  applicationName: string;
  gateType: string;
}

export interface PendingActions {
  approvals: ApprovalStep[];
  reviews: ApprovalStep[];
  failedGates: FailedGate[];
}

export interface OrgDashboard {
  organizationId: string;
  generatedAt: string;
  counts: OrgDashboardCounts;
  applications: ApplicationHealthRow[];
  pendingActions: PendingActions;
}

export function getOrgDashboard(organizationId: string): Promise<OrgDashboard> {
  return apiFetch<OrgDashboard>(`/organizations/${organizationId}/dashboard`);
}

export interface TenantDashboardCounts {
  applications: number;
  availableVersions: number;
  activeTesters: number;
  pendingReleases: number;
}

/** One in-flight release targeting the tenant, with the
 * tenant-facing ring label ("testing" until the target sits at the
 * production ring). */
export interface PendingRelease {
  releaseId: string;
  releaseRef: string;
  applicationId: string;
  applicationName: string;
  status: string;
  ring: string | null;
}

export interface TenantDashboard {
  tenantId: string;
  generatedAt: string;
  counts: TenantDashboardCounts;
  pendingReleases: PendingRelease[];
}

export function getTenantDashboard(tenantId: string): Promise<TenantDashboard> {
  return apiFetch<TenantDashboard>(`/tenants/${tenantId}/dashboard`);
}
