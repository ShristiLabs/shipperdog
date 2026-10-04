package com.shipperdog.api.identity.internal.service;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.internal.config.AuthProperties;
import com.shipperdog.api.identity.internal.entity.UserEntity;
import com.shipperdog.api.identity.internal.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records failed login attempts in its own transaction. Login fails by
 * throwing, which rolls the caller's transaction back — so the attempt
 * counter and the lockout must be persisted (and audited) in a separate
 * transaction that survives that rollback.
 */
@Service
public class LoginFailureRecorder {

    private final UserRepository users;
    private final AuthProperties properties;
    private final Clock clock;
    private final AuditEventPublisher audit;

    LoginFailureRecorder(UserRepository users, AuthProperties properties, Clock clock, AuditEventPublisher audit) {
        this.users = users;
        this.properties = properties;
        this.clock = clock;
        this.audit = audit;
    }

    /** Increments the failure counter; locks the account at the threshold. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registerFailure(UUID userId) {
        UserEntity user = users.findById(userId).orElse(null);
        if (user == null) {
            return;
        }
        Instant now = Instant.now(clock);
        int attempts = user.getFailedLoginAttempts() + 1;
        user.setFailedLoginAttempts(attempts);
        if (attempts >= properties.lockoutThreshold()) {
            user.setLockedUntil(now.plus(properties.lockoutDuration()));
            audit.publish(new AuditEvent(
                    AuditEventType.USER_LOCKED_OUT,
                    now,
                    user.getId(),
                    null,
                    null,
                    false,
                    Map.of("attempts", String.valueOf(attempts))));
        }
        users.save(user);
    }
}
