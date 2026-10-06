package com.shipperdog.api.tester.internal.service;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.tester.internal.entity.TesterGroupMemberEntity;
import com.shipperdog.api.tester.internal.repository.TesterGroupMemberRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional core of group-membership addition (issue #27),
 * isolated in its own bean ON PURPOSE — the TesterCreationAttempt
 * discipline from issue #26: the addMember orchestrator is deliberately
 * NOT transactional, so when this attempt loses the
 * {@code tester_group_members} primary-key race its rollback stays
 * contained here, the DataIntegrityViolationException crosses this
 * bean's transaction boundary, and the orchestrator catches a clean
 * exception and answers with the concurrent winner (the idempotent
 * {@code 200} acceptance criterion 2 promises). A try/catch INSIDE one
 * wrapping transaction cannot do that: PostgreSQL aborts the
 * transaction, the commit of a normal return then fails with
 * UnexpectedRollbackException, and the loser would answer 500.
 */
@Component
public class TesterGroupMembershipAttempt {

    private final TesterGroupMemberRepository members;
    private final AuditEventPublisher audit;
    private final Clock clock;

    TesterGroupMembershipAttempt(TesterGroupMemberRepository members, AuditEventPublisher audit, Clock clock) {
        this.members = members;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Inserts the membership and audits — atomically. The insert is
     * flushed inside the guard so a lost pair race throws HERE
     * (Hibernate defers inserts otherwise; the issue #13/#26 race
     * discipline).
     */
    @Transactional
    public TesterGroupMemberEntity attempt(UUID actorId, UUID tenantId, UUID groupId, UUID testerId) {
        TesterGroupMemberEntity membership = members.save(new TesterGroupMemberEntity(groupId, testerId));
        members.flush();
        audit.publish(AuditEvent.builder(AuditEventType.TESTER_GROUP_MEMBER_ADDED)
                .userId(actorId)
                .tenantId(tenantId)
                .occurredAt(Instant.now(clock))
                .success(true)
                .detail(membershipDetail(groupId, testerId))
                .build());
        return membership;
    }

    /** The membership event's detail block (shared with the removal audit). */
    static Map<String, String> membershipDetail(UUID groupId, UUID testerId) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("groupId", groupId.toString());
        detail.put("testerId", testerId.toString());
        return detail;
    }
}
