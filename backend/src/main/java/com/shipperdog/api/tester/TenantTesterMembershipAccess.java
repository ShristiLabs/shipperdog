package com.shipperdog.api.tester;

import java.util.List;
import java.util.UUID;

/**
 * The tester module's membership surface for consumers outside the
 * module (issue #28, S6-03): the E7 authorization chain's first layer
 * — a version grant to a tenant-scoped audience is effective only for
 * testers who are ACTIVE persons with an ACTIVE membership in the
 * tenant (the two-switches rule). Both checks are LIVE queries (no
 * cache; the documented staleness TTL is zero — a suspension or
 * removal reflects on the very next evaluation, the issue's acceptance
 * criterion 3), served by the cross-tenant membership catalog exactly
 * like the authorization engine's role resolution. Implementation
 * lives in the module's {@code .internal.} packages; cross-module
 * callers must use only this interface (enforced by ArchUnit).
 *
 * <p>Since S7-03 (issue #32) the authorization module caches these
 * lookups ABOVE this port — the engine's decision path resolves
 * through the cache and falls back to these live queries on any cache
 * miss or failure (fail-closed direct evaluation); the port itself
 * stays live-query so every uncached consumer keeps the zero-staleness
 * contract.
 */
public interface TenantTesterMembershipAccess {

    /**
     * Whether the tester holds an ACTIVE membership row in the tenant
     * (the membership switch alone — the person record's own status is
     * not consulted).
     */
    boolean isActiveMember(UUID tenantId, UUID testerId);

    /**
     * Whether the tester's access to the tenant is effective: the
     * person record ACTIVE and the membership ACTIVE — both switches
     * on (issue #28, acceptance criterion 2). A suspended tester, a
     * suspended membership, or a revoked membership all answer false.
     */
    boolean isEffectivelyActive(UUID tenantId, UUID testerId);

    /**
     * Whether a retained membership row exists for the pair in ANY
     * status (issue #31, S7-02): the grant-creation target check — a
     * grant addresses a tester linked to the tenant, but effectiveness
     * is deliberately NOT consulted at grant time (the two-switches
     * rule is honored at decision time, not grant time: a suspended
     * tester keeps their grants and loses access; re-enabling restores
     * both). A tester never linked to the tenant has no row at all,
     * and a grant to them would be dead data the API refuses.
     */
    boolean membershipExists(UUID tenantId, UUID testerId);

    /**
     * Every tenant holding a retained membership row for the tester,
     * any status, link-ordered (issue #32, S7-03): the decision-input
     * cache's candidate set — the grant cache is keyed per tester x
     * tenant (the story's technical note), so the shared-application
     * probe (a grant may live under any tenant the tester reached)
     * enumerates the linked tenants and resolves each per-tenant grant
     * set. Complete by construction: a grant's creation requires a
     * retained membership row, and removal retains the row REVOKED,
     * so every grant's tenant appears here. Authorization data in the
     * same live-query character as {@link #isEffectivelyActive};
     * never an endpoint data path.
     */
    List<UUID> tenantIdsOfTester(UUID testerId);
}
