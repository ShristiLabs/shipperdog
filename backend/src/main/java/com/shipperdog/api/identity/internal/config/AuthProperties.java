package com.shipperdog.api.identity.internal.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Identity/auth settings (prefix {@code shipperdog.auth}). All values come
 * from environment variables via application.yml; the JWT secret has no
 * default and fails startup validation when missing (see
 * {@link IdentityConfig}).
 *
 * @param jwtSecret         HMAC-SHA256 signing key, minimum 32 bytes
 * @param issuer            JWT issuer claim value
 * @param accessTokenTtl    access-token lifetime (issue #3 caps it at 15
 *                          minutes; authorization caching must not depend
 *                          on it)
 * @param refreshTokenTtl   refresh-token lifetime before rotation is forced
 * @param lockoutThreshold  failed logins before the account locks
 * @param lockoutDuration   how long a lockout lasts
 * @param passwordResetTtl  lifetime of a password-reset token (single use)
 * @param invitationTtl     lifetime of a registration invitation
 */
@ConfigurationProperties(prefix = "shipperdog.auth")
public record AuthProperties(
        String jwtSecret,
        String issuer,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        int lockoutThreshold,
        Duration lockoutDuration,
        Duration passwordResetTtl,
        Duration invitationTtl) {

    static final int MIN_SECRET_BYTES = 32;
    static final Duration DEFAULT_ACCESS_TTL = Duration.ofMinutes(15);
    static final Duration DEFAULT_REFRESH_TTL = Duration.ofDays(7);
    static final int DEFAULT_LOCKOUT_THRESHOLD = 5;
    static final Duration DEFAULT_LOCKOUT_DURATION = Duration.ofMinutes(15);
    static final Duration DEFAULT_RESET_TTL = Duration.ofMinutes(30);
    static final Duration DEFAULT_INVITATION_TTL = Duration.ofDays(3);

    public AuthProperties {
        issuer = issuer == null || issuer.isBlank() ? "shipperdog-api" : issuer;
        accessTokenTtl = accessTokenTtl == null ? DEFAULT_ACCESS_TTL : accessTokenTtl;
        refreshTokenTtl = refreshTokenTtl == null ? DEFAULT_REFRESH_TTL : refreshTokenTtl;
        lockoutThreshold = lockoutThreshold <= 0 ? DEFAULT_LOCKOUT_THRESHOLD : lockoutThreshold;
        lockoutDuration = lockoutDuration == null ? DEFAULT_LOCKOUT_DURATION : lockoutDuration;
        passwordResetTtl = passwordResetTtl == null ? DEFAULT_RESET_TTL : passwordResetTtl;
        invitationTtl = invitationTtl == null ? DEFAULT_INVITATION_TTL : invitationTtl;
    }

    /**
     * Fails startup when the signing secret is missing or too weak. Called
     * eagerly by {@link IdentityConfig} so a misconfigured deployment
     * cannot run with unsigned-token validation silently disabled.
     */
    public void requireValidSecret() {
        if (jwtSecret == null
                || jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("AUTH_JWT_SECRET must be set to at least "
                    + MIN_SECRET_BYTES
                    + " bytes for HS256 token signing; refusing to start");
        }
    }
}
