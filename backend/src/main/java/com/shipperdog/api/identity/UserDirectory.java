package com.shipperdog.api.identity;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The identity module's public user lookup surface (issue #5).
 * Organization and tenant member listings need the users' display
 * fields; the {@code users} table belongs to identity's
 * {@code .internal.} packages, so other modules resolve summaries
 * through this interface only (enforced by ArchUnit).
 */
public interface UserDirectory {

    /**
     * Resolves the requested users. Unknown ids are skipped; the result
     * order is unspecified.
     */
    List<UserSummary> byIds(Collection<UUID> userIds);

    /** Whether the user exists. */
    boolean userExists(UUID userId);

    /**
     * Non-sensitive display fields of a user. Credentials and lockout
     * state never appear here.
     *
     * @param id        the user's id
     * @param email     the registered address
     * @param fullName  the display name
     */
    record UserSummary(UUID id, String email, String fullName) {}
}
