package com.shipperdog.api.identity;

import java.util.UUID;

/**
 * The identity module's public session-revocation surface (issue #5).
 * Role demotions must take effect on next token refresh at the latest
 * (issue #5, acceptance criterion 5): permission-reducing role changes
 * revoke every refresh token of the affected user so the demotion ends
 * their sessions; the reason is recorded in the TOKEN_REVOKED audit
 * event, never in an HTTP response.
 */
public interface SessionRevocation {

    /**
     * Revokes every refresh token of the user.
     *
     * @param reason machine-readable audit detail (e.g. {@code
     *               role_demoted}); never surfaced to HTTP clients
     */
    void revokeRefreshTokens(UUID userId, String reason);
}
