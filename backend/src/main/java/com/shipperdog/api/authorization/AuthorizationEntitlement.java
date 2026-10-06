package com.shipperdog.api.authorization;

import java.util.UUID;

/**
 * The FR-011 entitlement seam of the layered engine (issue #30,
 * S7-01): who may act on which version. The engine's version layer
 * asks every registered contributor and allows when ANY contributor
 * answers entitled (union semantics, the same composition as the RBAC
 * scopes); layers orchestrate only, so entitlements are contributed,
 * never hard-coded.
 *
 * <p>S7-01 ships exactly one contributor — the role overlay
 * ({@code RoleOverlayEntitlement}, the engine's internal default): a
 * subject holding the console authority of the action at the
 * application's owning organization or tenant, or the platform
 * wildcard, is entitled. This preserves the pre-engine behavior of
 * the console surfaces the engine now guards.
 *
 * <p>S7-02 registers the second contributor over the
 * {@code version_access} grants (explicit version, application-wide,
 * tag-based, latest-N — FR-011's four shapes): a linked tester with an
 * applicable, unexpired, unrevoked grant is entitled, exactly the
 * INV-005 authorization a download requires. Until it lands, a pure
 * tester decides DENY {@code ENTITLEMENT_MISSING} — deny-by-default
 * is the default, not an omission.
 *
 * <p>Contributors answer a structured query — the seam the S14-04
 * ABAC engine widens with attribute predicates.
 */
public interface AuthorizationEntitlement {

    /**
     * Whether the contributor entitles the subject for the action over
     * the version. Contributors must stay read-only point lookups
     * (indexed; the PRD 42 decision SLO applies) and must never throw
     * — an unavailable source answers not-entitled (fail-closed).
     *
     * @param query the structured entitlement question
     */
    boolean isEntitled(EntitlementQuery query);

    /**
     * The entitlement question: the subject, the owning dimensions the
     * engine resolved (organization always; the owning tenant when the
     * application is tenant-owned, null when organization-shared), the
     * application and version addressed, and the action.
     *
     * @param subjectUserId  the deciding subject
     * @param organizationId the owning organization of the application
     * @param tenantId       the owning tenant, or null when
     *                       organization-shared
     * @param applicationId  the addressed application
     * @param versionId      the addressed version
     * @param action         the action the decision is for
     */
    record EntitlementQuery(
            UUID subjectUserId,
            UUID organizationId,
            UUID tenantId,
            UUID applicationId,
            UUID versionId,
            AuthorizationAction action) {}
}
