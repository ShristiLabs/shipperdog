package com.shipperdog.api.tenant;

import java.util.UUID;

/**
 * Marks a JPA entity as tenant-scoped user data (INV-001, issue #10):
 * every row belongs to exactly one tenant, and access to it must flow
 * through {@link TenantScopedRepository}, which injects the caller's
 * tenant context into every query by construction.
 *
 * <p>Implementations expose their discriminator column through
 * {@link #tenantId()}; the attribute name must stay {@code tenantId}
 * because the enforcement seam builds its predicate on that path. The
 * column is index-backed in every schema that carries tenant-scoped
 * tables (for example {@code uq_tenant_members_tenant_user} and
 * {@code idx_applications_tenant_id}), so the injected predicate is an
 * index lookup, not a scan.
 *
 * <p>Authorization data is deliberately exempt: repositories that must
 * query across tenants to resolve roles are annotated
 * {@link CrossTenantByDesign} and are never used for endpoint data
 * paths. Tenant rows themselves are organization-scoped, not
 * tenant-scoped, and are likewise outside this contract.
 */
public interface TenantScope {

    /** The owning tenant of this row; never null once persisted. */
    UUID tenantId();
}
