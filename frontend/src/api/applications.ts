/** Application registry API (S3-01/02/03 surfaces; docs/tenants.md
 * Applications section). Field rules mirror the server: name 1-200,
 * description max 2000, per-platform reverse-DNS identifiers, and the
 * TENANT/ORGANIZATION ownership pairing of PRD 14. */
import { apiFetch, pageFetch, type Page } from "./client";

export type ApplicationPlatform = "IOS" | "ANDROID";
export type ApplicationStatus = "ACTIVE" | "ARCHIVED";
export type OwnershipScope = "TENANT" | "ORGANIZATION";

export const APPLICATION_PLATFORMS: readonly ApplicationPlatform[] = ["IOS", "ANDROID"];

export interface Application {
  id: string;
  organizationId: string;
  /** null exactly when ownershipScope is ORGANIZATION (PRD 14 pairing). */
  tenantId: string | null;
  ownershipScope: OwnershipScope;
  name: string;
  description: string | null;
  platforms: ApplicationPlatform[];
  bundleId: string | null;
  packageName: string | null;
  status: ApplicationStatus;
  createdAt: string;
  updatedAt: string;
}

/** A tenant authorization on a shared application (S3-03). The listing
 * resolves only ACTIVE grants; revocation history lives in the audit
 * trail, not this surface. grantedBy/grantedAt are raw UUID/instant —
 * no user-name port exists yet (issue #15 follow-up). */
export interface ApplicationAuthorization {
  applicationId: string;
  tenantId: string;
  status: "ACTIVE" | "REVOKED";
  grantedBy: string;
  grantedAt: string;
  revokedBy: string | null;
  revokedAt: string | null;
}

export interface CreateApplicationInput {
  ownershipScope: OwnershipScope;
  /** The future owner tenant (TENANT scope). */
  tenantId?: string;
  /** The owning organization (ORGANIZATION scope). */
  organizationId?: string;
  name: string;
  description?: string;
  platforms: ApplicationPlatform[];
  bundleId?: string;
  packageName?: string;
}

/** Partial update: absent fields are unchanged server-side; an empty
 * description clears it; a null identifier retires it with its
 * platform; ownershipScope/tenantId carry the audited scope transition
 * and are sent only when the scope actually changes. */
export interface UpdateApplicationInput {
  name?: string;
  description?: string;
  platforms?: ApplicationPlatform[];
  bundleId?: string | null;
  packageName?: string | null;
  status?: ApplicationStatus;
  ownershipScope?: OwnershipScope;
  tenantId?: string;
}

/** Effective-visibility listing for a tenant (S3-03 AC4): the tenant's
 * owned applications plus the shared applications it is authorized
 * for. There is no unscoped GET /applications collection. */
export function listTenantApplications(
  tenantId: string,
  params: { limit?: number; cursor?: string } = {},
): Promise<Page<Application>> {
  return pageFetch<Application>(`/tenants/${tenantId}/applications`, params);
}

export function getApplication(id: string): Promise<Application> {
  return apiFetch<Application>(`/applications/${id}`);
}

/** Organization-wide administration listing (S3-04): every application
 * of the organization in both ownership scopes — the console's
 * reachability surface for a shared application before any tenant is
 * authorized (the effective-visibility listings hide such rows by
 * construction). */
export function listApplications(params: {
  organizationId: string;
  limit?: number;
  cursor?: string;
}): Promise<Page<Application>> {
  return pageFetch<Application>("/applications", { ...params });
}

export function createApplication(input: CreateApplicationInput): Promise<Application> {
  return apiFetch<Application>("/applications", { method: "POST", body: input });
}

export function updateApplication(id: string, input: UpdateApplicationInput): Promise<Application> {
  return apiFetch<Application>(`/applications/${id}`, { method: "PATCH", body: input });
}

export function listAuthorizedTenants(
  applicationId: string,
  params: { limit?: number; cursor?: string } = {},
): Promise<Page<ApplicationAuthorization>> {
  return pageFetch<ApplicationAuthorization>(`/applications/${applicationId}/tenants`, params);
}

/** Authorize a tenant for a shared application. The POST is idempotent
 * by state; the optional reason (1-500 characters) is captured on the
 * audit event, never stored on the row. */
export function authorizeTenant(
  applicationId: string,
  tenantId: string,
  reason?: string,
): Promise<ApplicationAuthorization> {
  const trimmed = reason?.trim();
  return apiFetch<ApplicationAuthorization>(`/applications/${applicationId}/tenants/${tenantId}`, {
    method: "POST",
    body: trimmed ? { reason: trimmed } : undefined,
  });
}

/** De-authorize (the soft, auditable flip) with the same optional
 * reason semantics as the grant. */
export function deauthorizeTenant(
  applicationId: string,
  tenantId: string,
  reason?: string,
): Promise<void> {
  const trimmed = reason?.trim();
  return apiFetch<void>(`/applications/${applicationId}/tenants/${tenantId}`, {
    method: "DELETE",
    body: trimmed ? { reason: trimmed } : undefined,
  });
}
