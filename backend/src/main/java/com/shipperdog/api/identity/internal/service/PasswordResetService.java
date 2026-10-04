package com.shipperdog.api.identity.internal.service;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.internal.AuthException;
import com.shipperdog.api.identity.internal.config.AuthProperties;
import com.shipperdog.api.identity.internal.crypto.SecureTokens;
import com.shipperdog.api.identity.internal.entity.PasswordResetTokenEntity;
import com.shipperdog.api.identity.internal.entity.UserEntity;
import com.shipperdog.api.identity.internal.repository.PasswordResetTokenRepository;
import com.shipperdog.api.identity.internal.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single-use, short-TTL password resets. Requests are indistinguishable
 * for known and unknown addresses (no account enumeration); until the
 * notification module exists (S12-02) the reset link is delivered via a
 * WARNING-level log line on the audit logger, which local development and
 * tests can observe. Confirming a reset rehashes the new password and
 * revokes every refresh token of the account.
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private final UserRepository users;
    private final PasswordResetTokenRepository resetTokens;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;
    private final Clock clock;
    private final TokenService tokens;
    private final AuditEventPublisher audit;

    PasswordResetService(
            UserRepository users,
            PasswordResetTokenRepository resetTokens,
            PasswordEncoder passwordEncoder,
            AuthProperties properties,
            Clock clock,
            TokenService tokens,
            AuditEventPublisher audit) {
        this.users = users;
        this.resetTokens = resetTokens;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.clock = clock;
        this.tokens = tokens;
        this.audit = audit;
    }

    /** Always behaves identically for known and unknown addresses. */
    @Transactional
    public void requestReset(String rawEmail) {
        String email = AuthService.normalizeEmail(rawEmail);
        Optional<UserEntity> found = users.findByEmail(email);
        if (found.isPresent()) {
            UserEntity user = found.get();
            String rawToken = SecureTokens.randomToken();
            Instant now = Instant.now(clock);
            resetTokens.save(new PasswordResetTokenEntity(
                    user.getId(), SecureTokens.sha256Hex(rawToken), now.plus(properties.passwordResetTtl())));
            log.warn(
                    "password reset link issued (single use, expires at {}): "
                            + "/api/v1/auth/password-reset/confirm?token={} [userId={}]",
                    now.plus(properties.passwordResetTtl()),
                    rawToken,
                    user.getId());
            audit(AuditEventType.PASSWORD_RESET_REQUESTED, user.getId(), true);
        } else {
            audit(AuditEventType.PASSWORD_RESET_REQUESTED, null, false);
        }
    }

    /** Consumes the token, sets the new hash, revokes refresh tokens. */
    @Transactional
    public void confirmReset(String rawToken, String newPassword) {
        if (rawToken == null || rawToken.isBlank()) {
            throw invalidReset();
        }
        PasswordResetTokenEntity token =
                resetTokens.findByTokenHash(SecureTokens.sha256Hex(rawToken)).orElseThrow(this::invalidReset);
        Instant now = Instant.now(clock);
        if (token.getUsedAt() != null || token.getExpiresAt().isBefore(now)) {
            throw invalidReset();
        }
        UserEntity user = users.findById(token.getUserId()).orElseThrow(this::invalidReset);
        if (newPassword == null
                || newPassword.length() < RegistrationService.MIN_PASSWORD_LENGTH
                || newPassword.length() > RegistrationService.MAX_PASSWORD_LENGTH) {
            throw new AuthException(
                    AuthException.Code.WEAK_PASSWORD,
                    "password must be between "
                            + RegistrationService.MIN_PASSWORD_LENGTH
                            + " and "
                            + RegistrationService.MAX_PASSWORD_LENGTH
                            + " characters");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        users.save(user);
        token.setUsedAt(now);
        resetTokens.save(token);
        tokens.revokeAllForUser(user.getId());
        audit(AuditEventType.PASSWORD_RESET_COMPLETED, user.getId(), true);
    }

    private AuthException invalidReset() {
        return new AuthException(
                AuthException.Code.INVALID_RESET_TOKEN, "reset token is invalid, expired, or already used");
    }

    private void audit(AuditEventType type, UUID userId, boolean success) {
        audit.publish(new AuditEvent(type, Instant.now(clock), userId, null, null, success, Map.of()));
    }
}
