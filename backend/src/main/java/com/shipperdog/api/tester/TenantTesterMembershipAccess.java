package com.shipperdog.api.tester;

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
}
