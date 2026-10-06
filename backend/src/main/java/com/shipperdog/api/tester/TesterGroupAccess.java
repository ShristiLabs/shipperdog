package com.shipperdog.api.tester;

import java.util.Optional;
import java.util.UUID;

/**
 * The tester module's public surface for group-ownership resolution
 * (issue #27, S6-02). Two readers need a group's owning tenant BEFORE
 * any tenant context exists, so they cannot use the isolation seam:
 * the authorization evaluator resolves {@code 'tester-group'} targets
 * (an {@code app:} permission checked at the group resolves through
 * the owning tenant, with the owning organization's role overlaid),
 * and the tenant module's route interceptor binds the request's tenant
 * context from the group in the path — the exact division of labor the
 * {@code application} target established (issue #13). Implementation
 * lives in the module's {@code .internal.} packages; cross-module
 * callers must use only this interface (enforced by ArchUnit).
 */
public interface TesterGroupAccess {

    /**
     * The group's owning tenant, for permission evaluation and request
     * scoping: a group of a tenant the caller cannot reach is
     * indistinguishable from a missing one. Empty when no such group
     * exists.
     */
    Optional<UUID> owningTenantOf(UUID groupId);
}
