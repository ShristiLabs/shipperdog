package com.shipperdog.api.identity;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The identity module's invitation surface for the tester module
 * (issue #26, S6-01). Tester onboarding reuses the platform's single
 * invitation machinery — one {@code user_invitations} table, one
 * digest-only token scheme, one accept endpoint
 * ({@code POST /api/v1/auth/register}) — instead of a second,
 * divergent invitation concept. Implementation lives in identity's
 * {@code .internal.} packages; the tester module must use only this
 * interface (enforced by ArchUnit).
 */
public interface TesterInvitations {

    /**
     * Revokes the tester's live invitation (if any) and mints a fresh
     * single-use token with the given TTL. The raw token is returned
     * exactly once; only its SHA-256 digest is persisted.
     *
     * @param organizationId the tester's owning organization (the
     *                       invitation's scope, for audit)
     * @param testerId       the tester record the invitation activates
     * @param email          the normalized tester address
     * @param invitedBy      the inviting administrator
     * @param ttl            the invitation lifetime (issue #26 default
     *                       7 days; distinct from the platform
     *                       invitation TTL)
     */
    Invitation mintForTester(UUID organizationId, UUID testerId, String email, UUID invitedBy, Duration ttl);

    /** The tester's live (PENDING) invitation state, if one exists. */
    Optional<PendingInvitation> pendingForTester(UUID testerId);

    /** Created invitation; {@code rawToken} is shown exactly once. */
    record Invitation(String email, String rawToken, Instant expiresAt) {}

    /** State of a live invitation; never carries the token. */
    record PendingInvitation(Instant expiresAt, UUID invitedBy, Instant createdAt) {}
}
