package com.shipperdog.api.tester;

import java.util.Optional;
import java.util.UUID;

/**
 * The tester module's public surface for the authorization engine
 * (issue #26). {@code org:} permissions evaluated against a
 * {@code 'tester'} target resolve the record's owning organization
 * through this interface — the exact pattern {@code application} and
 * {@code version} targets follow. Implementation lives in the module's
 * {@code .internal.} packages; cross-module callers must use only this
 * interface (enforced by ArchUnit).
 */
public interface TesterAccess {

    /**
     * The tester's owning organization, for permission evaluation and
     * concealment: a tester of another organization is indistinguishable
     * from a missing one. Empty when no such tester exists.
     */
    Optional<UUID> owningOrganizationOf(UUID testerId);

    /**
     * The linked tester person record of the user, or empty when the
     * user is not a tester (issue #30, S7-01): the E7 authorization
     * chain's subject resolution — the tester persona of a decision.
     * Authorization data only, the same character as the owning-
     * organization hop.
     */
    Optional<UUID> testerIdOfUser(UUID userId);
}
