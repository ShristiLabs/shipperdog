package com.shipperdog.api.tester.internal.service;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.TesterInvitations;
import com.shipperdog.api.tester.internal.config.TesterProperties;
import com.shipperdog.api.tester.internal.entity.TesterEntity;
import com.shipperdog.api.tester.internal.repository.TesterRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional core of tester creation (issue #26), isolated in
 * its own bean ON PURPOSE: the create orchestrator is deliberately
 * NOT transactional, so when this attempt loses the
 * uq_testers_organization_email race its rollback stays contained
 * here — the DataIntegrityViolationException crosses this bean's
 * transaction boundary, the physical transaction is rolled back by
 * the interceptor, and the orchestrator catches a clean exception and
 * answers with the concurrent winner. A try/catch INSIDE one wrapping
 * transaction cannot do that: PostgreSQL aborts the transaction, the
 * commit of a normal return then fails with UnexpectedRollbackException,
 * and the loser would answer 500 instead of the idempotent 200 the
 * story's first acceptance criterion promises.
 */
@Component
public class TesterCreationAttempt {

    private final TesterRepository testers;
    private final TesterInvitations invitations;
    private final TesterInvitationMailer mailer;
    private final TesterProperties properties;
    private final Clock clock;
    private final AuditEventPublisher audit;

    TesterCreationAttempt(
            TesterRepository testers,
            TesterInvitations invitations,
            TesterInvitationMailer mailer,
            TesterProperties properties,
            Clock clock,
            AuditEventPublisher audit) {
        this.testers = testers;
        this.invitations = invitations;
        this.mailer = mailer;
        this.properties = properties;
        this.clock = clock;
        this.audit = audit;
    }

    /**
     * Creates the INVITED tester, mints its invitation, delivers the
     * email, and audits — atomically. The insert is flushed inside the
     * guard so a lost uniqueness race throws HERE (Hibernate defers
     * UUID-keyed inserts otherwise; the issue #13 race discipline).
     */
    @Transactional
    public TesterService.CreatedTester attempt(UUID invitedBy, UUID organizationId, String email, String name) {
        TesterEntity tester = testers.save(new TesterEntity(organizationId, email, name));
        testers.flush();
        TesterInvitations.Invitation invitation =
                invitations.mintForTester(organizationId, tester.getId(), email, invitedBy, properties.invitationTtl());
        mailer.sendInvitation(email, tester.getFullName(), invitation.rawToken(), invitation.expiresAt());
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("email", email);
        detail.put("testerId", tester.getId().toString());
        audit.publish(AuditEvent.builder(AuditEventType.TESTER_INVITED)
                .userId(invitedBy)
                .orgId(organizationId)
                .occurredAt(Instant.now(clock))
                .success(true)
                .detail(detail)
                .build());
        Map<String, Object> invitationBlock = Map.of(
                "status",
                "PENDING",
                "invitationToken",
                invitation.rawToken(),
                "acceptUrl",
                mailer.acceptUrl(invitation.rawToken()),
                "expiresAt",
                invitation.expiresAt().toString());
        return new TesterService.CreatedTester(tester, true, invitationBlock);
    }
}
