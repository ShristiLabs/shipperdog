package com.shipperdog.api.tenant;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The tenant module's public query surface for other modules (issue #5).
 * Implementation lives in the module's {@code .internal.} packages;
 * cross-module callers must use only this interface (enforced by
 * ArchUnit).
 */
public interface TenantAccess {

    /** The user's tenant memberships and roles, keyed by tenant id. */
    Map<UUID, TenantRole> membershipsOf(UUID userId);

    /** The user's role in the tenant, or empty when not a member. */
    Optional<TenantRole> roleInTenant(UUID tenantId, UUID userId);

    /** The organization that owns the tenant, or empty when unknown. */
    Optional<UUID> owningOrganizationId(UUID tenantId);

    /**
     * The organization's tenants (issue #27: the groups-per-tester
     * listing spans the tester's organization's tenants — an
     * organization-grain read no single tenant context can scope).
     */
    List<UUID> tenantIdsOfOrganization(UUID organizationId);

    /**
     * Whether the tenant accepts NEW affiliations — members (since S2-03)
     * and applications (S3-01). Deactivation blocks new affiliations
     * only: existing data stays readable and administrable (issue #9).
     */
    boolean acceptsNewAffiliations(UUID tenantId);
}
