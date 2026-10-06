package com.shipperdog.api.authorization;

import java.util.Collection;
import java.util.UUID;

/**
 * The decision-input cache's write-through invalidation port (issue
 * #32, S7-03, acceptance criterion 2): every mutation that changes a
 * fact the layered engine's decision path resolves through the cache
 * drops the affected keys BEFORE the new state becomes visible —
 * inside the mutating transaction, at before-commit, so no reader
 * ever sees committed state through stale cache (a later rollback
 * only over-invalidates, the safe direction; the TTL stays the
 * backstop for an invalidation that itself fails while Redis is
 * down).
 *
 * <p>The cached inputs and their authorities — which module's
 * mutations drop which keys:
 *
 * <ul>
 *   <li><strong>Grant sets</strong> (per tester x tenant, all of the
 *       tester's grants through that tenant) — dropped by the
 *       authorization module's own grant mutations (create, expiry
 *       change, delete).</li>
 *   <li><strong>Membership effectiveness</strong> (per tester x
 *       tenant, the two-switches flag) — dropped by the tester
 *       module's membership mutations (link, re-link, status switch,
 *       removal) and by tester person-status changes for every tenant
 *       holding a retained row (the person-record switch flips
 *       effectiveness for all of them).</li>
 *   <li><strong>Tenant-application visibility</strong> (per
 *       application x tenant) — dropped by the application module's
 *       S3-03 authorization-mapping mutations.</li>
 *   <li><strong>Linked-tenant sets</strong> (per tester, the retained
 *       membership rows' tenants) — dropped when a fresh membership
 *       row is created (a link of a never-linked pair); removal
 *       retains the row REVOKED, so the set is unchanged.</li>
 * </ul>
 *
 * <p>Deliberately NOT invalidated: version tag and lifecycle
 * mutations (the TAG-grant probe and the LATEST_N working-set count
 * resolve LIVE on every decision — decisions stay computed, only
 * input SETS are cached), and console-role changes (roles resolve
 * per request through {@code UserRolesSnapshot}, never cached).
 *
 * <p>Implementations must tolerate Redis being unavailable — an
 * invalidation that cannot run counts {@code degraded} on the
 * decision-cache outcome counter and MUST NOT fail the mutating
 * transaction; the TTL bounds the stale window (the fail-closed
 * design: the worst a stale entry can do is delay, never widen).
 */
public interface AuthorizationDecisionInvalidation {

    /** Drops the tester's cached grant set for the tenant (grant mutations). */
    void invalidateGrantSet(UUID tenantId, UUID testerId);

    /**
     * Drops the tester's cached membership-effectiveness flag for the
     * tenant (membership link, re-link, status switch, removal).
     */
    void invalidateMembership(UUID tenantId, UUID testerId);

    /**
     * Drops the tester's cached membership-effectiveness flags for
     * every given tenant (tester person-status changes — the person
     * switch flips effectiveness for each retained row).
     */
    void invalidateMembershipsOfTester(UUID testerId, Collection<UUID> tenantIds);

    /**
     * Drops the cached tenant-application visibility flag (S3-03
     * authorization-mapping mutations).
     */
    void invalidateApplicationVisibility(UUID applicationId, UUID tenantId);

    /**
     * Drops the tester's cached linked-tenant set (a fresh membership
     * row creation — removal retains, so only creation widens the
     * set).
     */
    void invalidateTesterTenantSet(UUID testerId);
}
