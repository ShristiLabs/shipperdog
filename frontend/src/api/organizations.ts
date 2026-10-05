/** Organization API surface the console consumes (docs/organizations.md):
 * the signed-in user's own organizations and, for the tenant member
 * picker, an organization's member roster. */
import { pageFetch, type Page } from "./client";

export type OrgRole =
  | "ORG_ADMIN"
  | "ORG_MEMBER"
  | "APP_OWNER"
  | "DEVELOPER"
  | "QA_ENGINEER"
  | "RELEASE_MANAGER"
  | "SECURITY_REVIEWER"
  | "COMPLIANCE_REVIEWER"
  | "VIEWER";

export interface Organization {
  id: string;
  name: string;
  status: string;
  externalId: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface OrgMember {
  userId: string;
  email: string;
  fullName: string;
  role: OrgRole;
  createdAt: string;
}

/** Organizations the caller can see (members see their own). */
export function listMyOrganizations(
  params: { limit?: number; cursor?: string } = {},
): Promise<Page<Organization>> {
  return pageFetch<Organization>("/organizations", params);
}

export function listOrganizationMembers(
  organizationId: string,
  params: { limit?: number; cursor?: string } = {},
): Promise<Page<OrgMember>> {
  return pageFetch<OrgMember>(`/organizations/${organizationId}/members`, params);
}
