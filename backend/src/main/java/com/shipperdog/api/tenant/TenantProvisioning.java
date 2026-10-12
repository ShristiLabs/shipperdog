package com.shipperdog.api.tenant;

import java.util.UUID;

/**
 * The tenant module's cross-module provisioning surface (S14-03, issue
 * #69): the SCIM joiner/leaver automation's membership half. Identity
 * owns the SCIM protocol and the account lifecycle; this port is the
 * tenant-membership leg of both flows — the mapped-group assignment
 * (joiner) and the bulk suspension cascade (leaver). Implementation
 * lives in the module's {@code .internal.} packages; identity must use
 * only this interface (enforced by ArchUnit).
 *
 * <p>Cross-tenant by design, like the membership reads of {@link
 * TenantAccess}: the operations address ONE user's memberships across
 * the tenants the mapping rules (or the cascade) name, never an
 * endpoint data path of the tenant-scoped seam.
 */
public interface TenantProvisioning {

    /**
     * Makes the user's membership in the tenant match the declared
     * state: the tenant role, ACTIVE. Creates the membership when
     * absent (born ACTIVE, refused while the tenant is DEACTIVATED —
     * the no-new-affiliations rule), changes the role when different,
     * and reactivates a SUSPENDED row (an existing membership is not a
     * new affiliation, so a deactivated tenant's existing rows remain
     * administrable). Idempotent: a matching row writes nothing.
     *
     * @param actingUserId the user the assignment is made on behalf of
     *                     (the provisioned user itself, the SSO
     *                     mapping convention)
     */
    Outcome ensureMembership(UUID tenantId, UUID userId, TenantRole role, UUID actingUserId);

    /**
     * Suspends every ACTIVE membership of the user across ALL tenants
     * (the S6-04 cascade behind SCIM deprovisioning): the rows park
     * SUSPENDED — retained, never deleted — and each flip carries its
     * own TENANT_MEMBER_STATUS_CHANGED audit line with the cascade
     * reason. The caller owns the session revocation (once, at the
     * account level, not per row). Returns the number of rows flipped.
     *
     * <p>Restoration is deliberately NOT this port's business: a
     * suspended membership returns through {@link #ensureMembership}
     * (the mapping's declarative re-derivation) or the tenant
     * administration routes, never a silent bulk undo.
     */
    int suspendMembershipsOf(UUID userId, String cascadeReason, UUID actingUserId);

    /** The {@link #ensureMembership} outcome. */
    enum Outcome {

        /** The membership row was created (born ACTIVE). */
        CREATED,

        /** An existing row's role and/or status changed. */
        CHANGED,

        /** The row already matched the declared state; nothing was written. */
        UNCHANGED,

        /** No tenant exists at the id: the mapping is stale; the caller audits the skip. */
        SKIPPED_UNKNOWN_TENANT,

        /** The tenant is DEACTIVATED and no row exists: the no-new-affiliations rule. */
        SKIPPED_INACTIVE_TENANT
    }
}
