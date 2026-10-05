package com.shipperdog.api.tenant;

import java.util.UUID;

/**
 * Marks a {@link TenantScope} entity whose rows may be owned by the
 * organization itself instead of exactly one tenant (issue #14, S3-02,
 * PRD 14): the row carries a null {@code tenantId} and its owning
 * dimension is the organization. Organization-shared applications are the
 * M1 case; the model is the SaaS pattern where one application serves
 * many customer tenants through distribution (PRD 15), and which tenants
 * receive a shared application is decided by authorization
 * (S3-03) — never the other way around.
 *
 * <p>Implementations expose the owning organization through
 * {@link #organizationId()}; the attribute name must stay
 * {@code organizationId} because the isolation seam builds its
 * organization-scope predicate on that path (ADR-0002, third amendment).
 * A context bound in organization scope composes
 * {@code tenantId IS NULL AND organizationId = :context} into every
 * query: organization-owned rows are reachable only through their
 * organization's scope, exactly as tenant-owned rows are reachable only
 * through their tenant's scope — INV-001 keeps holding for both modes.
 *
 * <p>Entities that are only ever tenant-owned (for example
 * {@code tenant_members}) must NOT implement this interface: an
 * organization-scope context refuses to serve them, because no
 * organization-owned meaning exists for their rows.
 */
public interface OrganizationScope extends TenantScope {

    /** The owning organization of this row; never null once persisted. */
    UUID organizationId();
}
