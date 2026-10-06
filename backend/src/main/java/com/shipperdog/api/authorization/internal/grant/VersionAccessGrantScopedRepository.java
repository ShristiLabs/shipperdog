package com.shipperdog.api.authorization.internal.grant;

import com.shipperdog.api.tenant.TenantScopedRepository;

/**
 * The endpoint surface over {@code version_access} (issue #31,
 * S7-02): grant administration rides the tenant path
 * ({@code /api/v1/tenants/{id}/authorization-grants}), where the
 * route interceptor binds the tenant context this seam enforces for
 * every grant read and write. Declares no operations of its own (the
 * seam rule): listings compose {@code Specification} filters, item
 * reads go through {@code findScoped}, writes through
 * {@code saveScoped}. The engine's decision-time probe uses
 * {@link VersionAccessGrantCatalog} instead — this repository's
 * context-bound queries cannot run mid-decision.
 */
public interface VersionAccessGrantScopedRepository extends TenantScopedRepository<VersionAccessGrantEntity> {}
