/** Tenant API (docs/tenants.md). Field rules mirror the server: name
 * 1-200 chars, externalId 1-100 chars; roles and statuses are
 * UPPER_SNAKE enums. */
import { apiFetch, pageFetch, type Page } from "./client";

export type TenantStatus = "ACTIVE" | "DEACTIVATED";
export type TenantRole = "TENANT_ADMIN" | "TENANT_TESTER";
export type MemberStatus = "ACTIVE" | "SUSPENDED";

export const TENANT_ROLES: readonly TenantRole[] = ["TENANT_ADMIN", "TENANT_TESTER"];

export interface Tenant {
  id: string;
  organizationId: string;
  name: string;
  externalId: string;
  status: TenantStatus;
  createdAt: string;
  updatedAt: string;
}

export interface TenantMember {
  userId: string;
  email: string;
  fullName: string;
  role: TenantRole;
  status: MemberStatus;
  createdAt: string;
}

export interface ListTenantsParams {
  organizationId: string;
  status?: TenantStatus;
  limit?: number;
  cursor?: string;
}

export function listTenants(params: ListTenantsParams): Promise<Page<Tenant>> {
  return pageFetch<Tenant>("/tenants", { ...params });
}

export function getTenant(id: string): Promise<Tenant> {
  return apiFetch<Tenant>(`/tenants/${id}`);
}

export function createTenant(input: {
  organizationId: string;
  name: string;
  externalId: string;
}): Promise<Tenant> {
  return apiFetch<Tenant>("/tenants", { method: "POST", body: input });
}

/** Partial update: undefined fields are left unchanged server-side. */
export function updateTenant(
  id: string,
  input: { name?: string; externalId?: string; status?: TenantStatus },
): Promise<Tenant> {
  return apiFetch<Tenant>(`/tenants/${id}`, { method: "PUT", body: input });
}

export function listTenantMembers(
  id: string,
  params: { limit?: number; cursor?: string } = {},
): Promise<Page<TenantMember>> {
  return pageFetch<TenantMember>(`/tenants/${id}/members`, params);
}

export function addTenantMember(
  tenantId: string,
  input: { userId: string; role: TenantRole },
): Promise<TenantMember> {
  return apiFetch<TenantMember>(`/tenants/${tenantId}/members`, {
    method: "POST",
    body: input,
  });
}

export function changeTenantMemberRole(
  tenantId: string,
  userId: string,
  role: TenantRole,
): Promise<TenantMember> {
  return apiFetch<TenantMember>(`/tenants/${tenantId}/members/${userId}/role`, {
    method: "PUT",
    body: { role },
  });
}

export function changeTenantMemberStatus(
  tenantId: string,
  userId: string,
  status: MemberStatus,
): Promise<TenantMember> {
  return apiFetch<TenantMember>(`/tenants/${tenantId}/members/${userId}/status`, {
    method: "PUT",
    body: { status },
  });
}

export function removeTenantMember(tenantId: string, userId: string): Promise<void> {
  return apiFetch<void>(`/tenants/${tenantId}/members/${userId}`, { method: "DELETE" });
}
