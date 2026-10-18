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

    /**
     * The single-application form of the effective-visibility
     * resolution (issue #30, S7-01): whether the tenant may see the
     * application — tenant-owned by it, or organization-shared with an
     * ACTIVE authorization mapping for it. The set form is the catalog
     * listing's sanctioned union (ADR-0002 fourth amendment); this is
     * the same predicate for one addressed application, the E7
     * authorization chain's "Does the tenant have Banking?" layer
     * input. Authorization data only — never an endpoint data path.
     */
    boolean visibleToTenant(UUID applicationId, UUID tenantId);

    /**
     * The application's display summary (issue #36, S8-02): id, name,
     * and lifecycle status — the card facts the decision API's portal
     * catalog composes beside the effective-set resolution the engine
     * sanctions. The same registry-read character as the profile hop
     * (tuples only, never the row; the decision surface's data, the
     * exact precedent of the eligibility listing serving version
     * names); empty when the application is unknown.
     */
    Optional<ApplicationSummary> summaryOf(UUID applicationId);

    /** The application card's display tuple; never the row itself. */
    record ApplicationSummary(UUID applicationId, String name, String status) {}

    /** The ownership tuple callers may resolve; never the row itself. */
    record Ownership(UUID applicationId, UUID organizationId, UUID tenantId) {}

    /**
     * The profile tuple: ownership, the registered platform set (the
     * per-version artifact slots group by it), the lifecycle status
     * (the archived guard of version creation), and — since S5-05 —
     * the registered platform identifiers the artifact validation
     * pipeline's APPLICATION_MATCH stage compares against (issue #24:
     * an uploaded bundle id or package name that differs from the
     * registry's fails the artifact). {@code bundleId} serves IOS,
     * {@code packageName} ANDROID (S3-01's registry rule); each is
     * null for a platform the application did not register. Since
     * S16-03 the tuple also carries the OTA opt-in flag (V55's
     * {@code ota_enabled}): the otabundle module's authoring guard.
     */
    record ApplicationProfile(
            UUID applicationId,
            UUID organizationId,
            UUID tenantId,
            List<String> platforms,
            String status,
            String bundleId,
            String packageName,
            boolean otaEnabled) {}
}
