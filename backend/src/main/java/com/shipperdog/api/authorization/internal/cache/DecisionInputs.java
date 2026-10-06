package com.shipperdog.api.authorization.internal.cache;

import com.shipperdog.api.authorization.internal.cache.AuthorizationDecisionCache.CachedGrant;
import java.util.List;
import java.util.UUID;

/**
 * The decision path's resolved inputs (issue #32, S7-03): the seam
 * the layered engine's components consume — the cached lookups of
 * {@link AuthorizationDecisionCache}, separated from the
 * {@link com.shipperdog.api.authorization.AuthorizationDecisionInvalidation}
 * write-through face so the engine's readers depend on exactly the
 * four questions they ask and unit tests can fake them without
 * Redis. Every lookup is cache-aside over the live loaders: a hit
 * replaces the database descent, a miss or failure answers the live
 * query (fail-closed — the cache changes latency, never semantics).
 */
public interface DecisionInputs {

    /**
     * The tester's grants through the tenant — all applications, all
     * expiries; expiry filters at evaluation time.
     */
    List<CachedGrant> grantsOf(UUID tenantId, UUID testerId);

    /** The tester's linked tenants (retained membership rows, any status). */
    List<UUID> tenantsOfTester(UUID testerId);

    /** The two-switches effectiveness flag (person ACTIVE and membership ACTIVE). */
    boolean effectiveMembership(UUID tenantId, UUID testerId);

    /** The S3-03 visibility flag: whether the tenant can see the application. */
    boolean visibleToTenant(UUID applicationId, UUID tenantId);
}
