package com.shipperdog.api.identity.internal.service;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.internal.AuthException;
import com.shipperdog.api.identity.internal.entity.UserEntity;
import com.shipperdog.api.identity.internal.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Email/password login with failed-attempt lockout. Every outcome emits an
 * audit event; every failure path performs a password hash comparison (the
 * dummy digest for unknown addresses) so response timing does not reveal
 * whether an address is registered.
 */
@Service
public class AuthService {

    /**
     * Argon2id digest of an unguessable random UUID; hashing against it
     * equalizes timing for unknown accounts. Generated with the same
     * encoder settings as production hashes.
     */
    private static final String DUMMY_HASH =
            "$argon2id$v=19$m=19456,t=2,p=1$7GVp2ZkOraogJj1oJr0EXg$6zg968uinjcUHGOUccHveZK00wxq/QuRaPfxU8xQAFg";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final TokenService tokens;
    private final LoginFailureRecorder failures;
    private final AuditEventPublisher audit;

    AuthService(
            UserRepository users,
            PasswordEncoder passwordEncoder,
            Clock clock,
            TokenService tokens,
            LoginFailureRecorder failures,
            AuditEventPublisher audit) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.tokens = tokens;
        this.failures = failures;
        this.audit = audit;
    }

    @Transactional
    public TokenService.IssuedTokens login(String rawEmail, String password) {
        String email = normalizeEmail(rawEmail);
        Optional<UserEntity> found = users.findByEmail(email);
        String candidate = password == null ? "" : password;
        Instant now = Instant.now(clock);

        if (found.isEmpty()) {
            passwordEncoder.matches(candidate, DUMMY_HASH);
            audit(AuditEventType.LOGIN_FAILURE, null, "invalid_credentials");
            throw invalidCredentials();
        }
        UserEntity user = found.get();
        if (user.getStatus() == UserEntity.Status.DISABLED) {
            passwordEncoder.matches(candidate, DUMMY_HASH);
            audit(AuditEventType.LOGIN_FAILURE, user.getId(), "account_disabled");
            throw invalidCredentials();
        }
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now)) {
            // Same timing equalization as the unknown/disabled branches so a
            // locked account is not distinguishable by response time.
            passwordEncoder.matches(candidate, DUMMY_HASH);
            audit(AuditEventType.LOGIN_FAILURE, user.getId(), "account_locked");
            throw invalidCredentials();
        }
        if (!passwordEncoder.matches(candidate, user.getPasswordHash())) {
            // REQUIRES_NEW: the counter must survive the rollback this throw causes.
            failures.registerFailure(user.getId());
            audit(AuditEventType.LOGIN_FAILURE, user.getId(), "invalid_credentials");
            throw invalidCredentials();
        }

        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(now);
        users.save(user);
        TokenService.IssuedTokens issued = tokens.issueForLogin(user);
        audit.publish(new AuditEvent(AuditEventType.LOGIN_SUCCESS, now, user.getId(), null, null, true, Map.of()));
        return issued;
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        tokens.revoke(rawRefreshToken);
    }

    private void audit(AuditEventType type, UUID userId, String reason) {
        audit.publish(new AuditEvent(type, Instant.now(clock), userId, null, null, false, Map.of("reason", reason)));
    }

    private static AuthException invalidCredentials() {
        return new AuthException(
                AuthException.Code.INVALID_CREDENTIALS,
                "email or password is incorrect, or the account is locked or disabled");
    }

    static String normalizeEmail(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }
}
