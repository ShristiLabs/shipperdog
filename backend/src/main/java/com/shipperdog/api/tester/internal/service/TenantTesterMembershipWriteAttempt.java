package com.shipperdog.api.tester.internal.service;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.tester.internal.entity.TenantTesterMembershipEntity;
import com.shipperdog.api.tester.internal.repository.TenantTesterMembershipScopedRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional core of tenant-tester membership linking (issue
 * #28), isolated in its own bean ON PURPOSE — the
 * TesterCreationAttempt discipline from issue #26 (applied to group
 * memberships by #27): the link orchestrator is deliberately NOT
 * transactional, so when this attempt loses the
 * {@code tenant_tester_memberships} primary-key race its rollback
 * stays contained here, the DataIntegrityViolationException crosses
 * this bean's transaction boundary, and the orchestrator catches a
 * clean exception and answers with the concurrent winner (the
 * idempotent {@code 200}). A try/catch INSIDE one wrapping transaction
 * cannot do that: PostgreSQL aborts the transaction, the commit of a
 * normal return then fails with UnexpectedRollbackException, and the
 * loser would answer 500 — the dead-catch defect twice carried from
 * issues #26/#27 and fixed for {@code TenantMemberService.addMember}
 * by this story.
 */
@Component
public class TenantTesterMembershipWriteAttempt {

    private final TenantTesterMembershipScopedRepository memberships;
    private final AuditEventPublisher audit;
    private final Clock clock;

    TenantTesterMembershipWriteAttempt(
            TenantTesterMembershipScopedRepository memberships, AuditEventPublisher audit, Clock clock) {
        this.memberships = memberships;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Inserts the fresh membership (born ACTIVE) and audits —
     * atomically. The insert is flushed inside the guard so a lost
     * pair race throws HERE (Hibernate defers inserts otherwise; the
     * issue #13/#26 race discipline).
     */
    @Transactional
    public TenantTesterMembershipEntity attempt(UUID actorId, UUID tenantId, UUID testerId) {
        TenantTesterMembershipEntity membership =
                memberships.saveScoped(new TenantTesterMembershipEntity(tenantId, testerId));
        memberships.flush();
        audit.publish(AuditEvent.builder(AuditEventType.TENANT_TESTER_MEMBERSHIP_CREATED)
                .userId(actorId)
                .tenantId(tenantId)
                .occurredAt(Instant.now(clock))
                .success(true)
                .detail(membershipDetail(tenantId, testerId, TenantTesterMembershipEntity.Status.ACTIVE))
                .build());
        return membership;
    }

    /**
     * Reactivates a REVOKED membership row (removal preserved it) and
     * audits — atomically, in its own transaction like the insert: the
     * re-link answers the same idempotent surface. The row is loaded
     * through the seam (the route's bound tenant context).
     */
    @Transactional
    public TenantTesterMembershipEntity reactivate(UUID actorId, TenantTesterMembershipEntity membership) {
        membership.setStatus(TenantTesterMembershipEntity.Status.ACTIVE);
        TenantTesterMembershipEntity saved = memberships.saveScoped(membership);
        memberships.flush();
        audit.publish(AuditEvent.builder(AuditEventType.TENANT_TESTER_MEMBERSHIP_CREATED)
                .userId(actorId)
                .tenantId(membership.getTenantId())
                .occurredAt(Instant.now(clock))
                .success(true)
                .detail(Map.copyOf(new LinkedHashMap<>(membershipDetail(
                        membership.getTenantId(),
                        membership.getTesterId(),
                        TenantTesterMembershipEntity.Status.ACTIVE,
                        true))))
                .build());
        return saved;
    }

    /** The membership event's detail block (shared with the other membership audits). */
    static Map<String, String> membershipDetail(
            UUID tenantId, UUID testerId, TenantTesterMembershipEntity.Status status) {
        return membershipDetail(tenantId, testerId, status, false);
    }

    private static Map<String, String> membershipDetail(
            UUID tenantId, UUID testerId, TenantTesterMembershipEntity.Status status, boolean reactivated) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("tenantId", tenantId.toString());
        detail.put("testerId", testerId.toString());
        detail.put("status", status.name());
        if (reactivated) {
            detail.put("reactivated", "true");
        }
        return detail;
    }
}
