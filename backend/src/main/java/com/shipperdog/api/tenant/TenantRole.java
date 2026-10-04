package com.shipperdog.api.tenant;

/**
 * Tenant-scoped roles (issue #5, PRD 7.7/7.8). TENANT_ADMIN administers
 * the tenant's users, testers, and application access; TENANT_TESTER is
 * the end tester of the tester portal (view authorized applications,
 * download, provide feedback). The permission matrix entries live in the
 * authorization module's {@code RolePermissions}. Per-tenant role
 * overrides beyond these two values are out of scope (issue #5).
 */
public enum TenantRole {
    TENANT_ADMIN,
    TENANT_TESTER
}
