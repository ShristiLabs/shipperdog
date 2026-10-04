package com.shipperdog.api.organization;

import java.util.Optional;
import java.util.UUID;

/**
 * The organization module's public query/command surface for other
 * modules (issue #4). Implementation lives in the module's
 * {@code .internal.} packages; cross-module callers must use only this
 * interface (enforced by ArchUnit).
 */
public interface OrganizationAccess {

    /**
     * The user's primary organization: the earliest-created membership in
     * an ACTIVE organization, used to attach the {@code org_id}
     * access-token claim. Empty when the user has no active organization
     * (org-less users, and members of only deactivated organizations such
     * as exempt platform administrators), yielding a context-free token —
     * never a deactivated organization's id.
     */
    Optional<UUID> primaryOrganizationId(UUID userId);

    /**
     * Whether every organization the user belongs to is DEACTIVATED (and
     * they have at least one membership): such users may not log in or
     * refresh tokens. Platform-admin exemption is the caller's decision.
     */
    boolean loginBlockedFor(UUID userId);

    /** Whether the user holds an ORG_ADMIN membership in the organization. */
    boolean isOrganizationAdmin(UUID organizationId, UUID userId);

    /** Whether the organization exists. */
    boolean organizationExists(UUID organizationId);

    /**
     * Grants a membership. Rejects a deactivated organization or an
     * existing member with {@link OrganizationException}.
     *
     * @param actingUserId the user on whose behalf the membership is
     *                     granted (the inviter for invitation acceptance)
     */
    void addMember(UUID organizationId, UUID userId, OrgRole role, UUID actingUserId);
}
