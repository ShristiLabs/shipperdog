package com.shipperdog.api.tester;

import java.util.List;
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
 * {@code application} target established (issue #13). Since S10-04
 * (issue #43) the ring-eligibility seam reads the tester's own group
 * memberships the same way — cross-tenant facts before any tenant
 * context exists. Implementation lives in the module's {@code
 * .internal.} packages; cross-module callers must use only this
 * interface (enforced by ArchUnit).
 */
public interface TesterGroupAccess {

    /**
     * The group's owning tenant, for permission evaluation and request
     * scoping: a group of a tenant the caller cannot reach is
     * indistinguishable from a missing one. Empty when no such group
     * exists.
     */
    Optional<UUID> owningTenantOf(UUID groupId);

    /**
     * The tester's group memberships across every tenant of their
     * organization (S10-04): the live rows, any status — the ring
     * eligibility seam composes them with its own activity rules.
     * Unknown testers answer empty, not a confirmed nobody.
     */
    List<UUID> groupIdsOfTester(UUID testerId);

    /**
     * The group's standing facts (S13-02, issue #62): identity, owning
     * tenant, and name — the store connectors' group-mapping surface
     * renders the ShipperDog side of a link from these. Empty when no
     * such group exists (concealed like every read here).
     */
    default Optional<GroupFacts> groupFactsOf(UUID groupId) {
        return Optional.empty();
    }

    /**
     * The group's members with their emails (S13-02, issue #62): the
     * ShipperDog-side membership the TestFlight group sync pushes to
     * the mapped ASC beta group, keyed by the email Apple matches
     * testers on. Empty for an unknown group; members whose tester row
     * vanished answer nothing (an orphaned membership is not a tester).
     */
    default List<GroupMember> membersOf(UUID groupId) {
        return List.of();
    }

    /** A tester group's served facts (S13-02). */
    record GroupFacts(UUID groupId, UUID tenantId, String name) {}

    /** One group member as the store sync addresses them (S13-02). */
    record GroupMember(UUID testerId, String email) {}
}
