package com.shipperdog.api.identity.internal.service;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.internal.AuthException;
import com.shipperdog.api.identity.internal.entity.UserEntity;
import com.shipperdog.api.identity.internal.repository.UserRepository;
import com.shipperdog.api.organization.OrganizationAccess;
import com.shipperdog.api.tester.TesterAccounts;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Email/password login with failed-attempt lockout. Every outcome emits an
 * audit event; every failure path performs a password hash comparison (the
 * dummy digest for unknown addresses) so response timing does not reveal
 * whether an address is registered. Since S1-04, users whose every
 * organization is DEACTIVATED are refused (uniform 401, distinct audit
 * reason) unless they are platform administrators. Since S6-01, users whose
 * tester record is SUSPENDED are refused the same way.
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
    private final OrganizationAccess organizations;
    private final TesterAccounts testerAccounts;
    private final TransactionTemplate transactions;

    AuthService(
            UserRepository users,
            PasswordEncoder passwordEncoder,
            Clock clock,
            TokenService tokens,
            LoginFailureRecorder failures,
            AuditEventPublisher audit,
            OrganizationAccess organizations,
            TesterAccounts testerAccounts,
            TransactionTemplate transactions) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.tokens = tokens;
        this.failures = failures;
        this.audit = audit;
        this.organizations = organizations;
        this.testerAccounts = testerAccounts;
        this.transactions = transactions;
    }

    /**
     * Deliberately NOT transactional as a whole (issue #8 audit fix): every
     * failure branch throws, and under a wrapping transaction the
     * BEFORE_COMMIT audit sink's LOGIN_FAILURE row would be rolled back
     * with the (empty) work of the request — the event was logged but never
     * persisted (S1-07 follow-up). Without the outer transaction each
     * failure-path publish falls back to an immediate, independently
     * committed insert. The success path alone is atomic, via
     * {@link TransactionTemplate}: counter reset, token issuance, and the
     * LOGIN_SUCCESS event commit together.
     */
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
            // REQUIRES_NEW: the counter must survive this throw.
            failures.registerFailure(user.getId());
            audit(AuditEventType.LOGIN_FAILURE, user.getId(), "invalid_credentials");
            throw invalidCredentials();
        }
        if (!user.isPlatformAdmin() && organizations.loginBlockedFor(user.getId())) {
            // S1-04: a user whose every organization is DEACTIVATED may not
            // log in; platform administrators stay exempt. The response stays
            // the uniform generic 401 so organization state is not revealed.
            audit(AuditEventType.LOGIN_FAILURE, user.getId(), "organization_deactivated");
            throw invalidCredentials();
        }
        if (!user.isPlatformAdmin() && testerAccounts.loginBlockedFor(user.getId())) {
            // S6-01: a SUSPENDED tester may not log in; the same uniform
            // 401 and the same platform-admin exemption as the
            // deactivated-organization block above.
            audit(AuditEventType.LOGIN_FAILURE, user.getId(), "tester_suspended");
            throw invalidCredentials();
        }

        return transactions.execute(status -> {
            user.setFailedLoginAttempts(0);
            user.setLockedUntil(null);
            user.setLastLoginAt(now);
            users.save(user);
            TokenService.IssuedTokens issued = tokens.issueForLogin(user);
            audit.publish(new AuditEvent(AuditEventType.LOGIN_SUCCESS, now, user.getId(), null, null, true, Map.of()));
            return issued;
        });
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
