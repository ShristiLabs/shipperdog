package com.shipperdog.api.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The application module's public surface for other modules (issue #13,
 * extended by issue #17): the RBAC evaluator resolves {@code app:}
 * permissions through the application's owning tenant/organization, the
 * tenant module's web layer binds the request's tenant context for
 * application-addressing routes the same way, and — since E4 — the
 * version module resolves each version's owning-dimension snapshot,
 * platform set, and archived guard through the profile read.
 * Implementation lives in the module's {@code .internal.} packages
 * behind the sanctioned cross-tenant registry guard; cross-module
 * callers must use only this interface (enforced by ArchUnit).
 */
public interface ApplicationAccess {

    /**
     * The owning scope of the application, or empty when unknown. A null
     * {@code tenantId} means organization-shared (PRD 14, S3-02): the
     * row is owned by the organization itself and resolves at
     * organization scope.
     */
    Optional<Ownership> ownershipOf(UUID applicationId);

    /**
     * The application profile (issue #17): the ownership tuple plus the
     * platform vocabulary and lifecycle status the version layer needs —
     * tuples only, never the row. Empty when the application is
     * unknown.
     */
    Optional<ApplicationProfile> profileOf(UUID applicationId);

    /** The ownership tuple callers may resolve; never the row itself. */
    record Ownership(UUID applicationId, UUID organizationId, UUID tenantId) {}

    /**
     * The profile tuple: ownership, the registered platform set (the
     * per-version artifact slots group by it), and the lifecycle status
     * (the archived guard of version creation).
     */
    record ApplicationProfile(
            UUID applicationId, UUID organizationId, UUID tenantId, List<String> platforms, String status) {}
}
