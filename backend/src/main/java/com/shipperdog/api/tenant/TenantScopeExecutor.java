package com.shipperdog.api.tenant;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Explicitly establishes the request's tenant scope for work whose
 * target tenant is not addressable from the route (issue #13): a
 * creation route carries the future owner tenant in the request body
 * ({@code POST /api/v1/applications}), so no route interceptor can bind
 * the context before the handler runs. This executor is the tenant
 * module's sanctioned equivalent of that binding — the same authority,
 * the same guards, callable at the point the target scope is known.
 *
 * <p>Semantics (ADR-0002): the executor may only ESTABLISH a scope —
 * it refuses when any context is already bound (no re-scoping, ever),
 * so the route interceptor's binding always wins on routes that carry
 * the tenant in the path. The platform-administrator override is
 * derived from {@code users.platform_admin} exactly as the interceptor
 * derives it, and every platform-administered execution is audited as
 * {@code CROSS_TENANT_ACCESS}. Background jobs that touch tenant-scoped
 * data use the same entry point with their own scope.
 */
public interface TenantScopeExecutor {

    /**
     * Runs {@code work} inside a tenant scope bound to {@code tenantId}
     * for {@code actorId}.
     *
     * @throws TenantIsolationException when a tenant context is already
     *     bound to this thread — establishing a second scope would be a
     *     re-scope, which the seam forbids
     */
    <T> T inScope(UUID tenantId, UUID actorId, Supplier<T> work);

    /**
     * Runs {@code work} inside an organization scope bound to
     * {@code organizationId} for {@code actorId} (issue #14, S3-02): the
     * sanctioned establishment for organization-owned rows whose owning
     * organization arrives in the request body (creating an
     * organization-shared application, PRD 14) and for background jobs
     * over organization-owned data. Same semantics as {@link #inScope}:
     * establish-only, platform-administrator override derived from
     * {@code users.platform_admin}, every platform-administered execution
     * audited as {@code CROSS_TENANT_ACCESS}.
     *
     * @throws TenantIsolationException when a context is already bound to
     *     this thread — establishing a second scope would be a re-scope,
     *     which the seam forbids
     */
    <T> T inOrganizationScope(UUID organizationId, UUID actorId, Supplier<T> work);
}
