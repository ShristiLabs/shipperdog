package com.shipperdog.api.organization;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The organization module's public query/command surface for other
 * modules (issues #4 and #5). Implementation lives in the module's
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

    /**
     * The user's roles in ACTIVE organizations, keyed by organization id
     * (issue #5). Roles held in DEACTIVATED organizations grant nothing
     * and are omitted, so deactivation revokes effective permissions
     * without touching memberships.
     */
    Map<UUID, OrgRole> activeOrganizationRoles(UUID userId);

    /** Whether the organization exists. */
    boolean organizationExists(UUID organizationId);

    /**
     * The user ids holding a role in the organization (S11-02, issue
     * #47): the subscribed-role resolution the feedback module's
     * critical-report notification walks (which members hold QA or
     * security standing in the organization that owns the release).
     * Membership data in the port's own character — the rows as
     * recorded, exactly like the role reads above.
     */
    List<UUID> memberUserIdsOfRole(UUID organizationId, OrgRole role);

    /**
     * The organization's lifecycle status ({@code ACTIVE} or
     * {@code DEACTIVATED}), or empty when unknown (issue #30, S7-01):
     * the E7 authorization chain's organization-layer input.
     * Authorization data only — the same character as the role reads.
     */
    Optional<String> statusOf(UUID organizationId);

    /**
     * Grants a membership. Rejects a deactivated organization or an
     * existing member with {@link OrganizationException}.
     *
     * @param actingUserId the user on whose behalf the membership is
     *                     granted (the inviter for invitation acceptance)
     */
    void addMember(UUID organizationId, UUID userId, OrgRole role, UUID actingUserId);

    /**
     * Replaces a member's organization role (S14-01): the SSO
     * group-claim mapping's apply step, on the same guarded mutation
     * the member administration uses (last-ORG_ADMIN protection,
     * demotion session revocation, and the ORG_MEMBER_ROLE_CHANGED
     * audit ride along unchanged).
     *
     * @param actingUserId the user on whose behalf the role changes
     *                     (the mapped user itself for SSO logins)
     */
    void updateMemberRole(UUID organizationId, UUID userId, OrgRole newRole, UUID actingUserId);
}
