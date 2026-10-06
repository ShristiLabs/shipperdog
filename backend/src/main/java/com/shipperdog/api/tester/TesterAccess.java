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
}
