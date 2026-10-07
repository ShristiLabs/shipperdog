package com.shipperdog.api.tenant.internal.service;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.tenant.TenantRole;
import com.shipperdog.api.tenant.internal.entity.TenantMemberEntity;
import com.shipperdog.api.tenant.internal.repository.TenantMemberScopedRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional core of tenant-member addition (issue #11), isolated
 * in its own bean ON PURPOSE since issue #28 — the TesterCreationAttempt
 * discipline from issue #26 (applied to group memberships by #27):
 * {@code TenantMemberService.addMember}'s former in-transaction catch of
 * {@code DataIntegrityViolationException} was dead code for a TRUE race
 * — PostgreSQL had already aborted the transaction, so the catch's
 * normal return failed the commit with UnexpectedRollbackException and
 * the loser answered 500 instead of the idempotent 200 (the finding
 * carried from #26 and #27). With the insert isolated here, a lost
 * {@code uq_tenant_members_tenant_user} race rolls back only this
 * attempt, the exception crosses this bean's transaction boundary, and
 * the orchestrator answers with the concurrent winner re-derived on a
 * fresh transaction ({@code TenantMemberConflictResolver}).
 */
@Component
public class TenantMemberWriteAttempt {

    private final TenantMemberScopedRepository members;
    private final AuditEventPublisher audit;
    private final Clock clock;

    TenantMemberWriteAttempt(TenantMemberScopedRepository members, AuditEventPublisher audit, Clock clock) {
        this.members = members;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Inserts the membership (born ACTIVE) and audits — atomically.
     * The insert is flushed inside the guard so a lost uniqueness race
     * throws HERE (Hibernate defers inserts otherwise; the issue
     * #13/#26 race discipline). REQUIRES_NEW since S7-05 (issue #34):
     * the attempt must own its transaction even when a transactional
     * orchestrator calls it, so a lost race rolls back only this
     * attempt and the caller's transaction stays usable for conflict
     * resolution — the assignRole insert arm (the #26/#27 carried
     * candidate) composes with the same discipline addMember has held
     * since #28. For a non-transactional caller (addMember),
     * REQUIRES_NEW behaves exactly as before.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TenantMemberEntity attempt(UUID actorId, UUID tenantId, UUID targetUserId, TenantRole role) {
        TenantMemberEntity membership = members.saveScoped(new TenantMemberEntity(tenantId, targetUserId, role));
        members.flush();
        audit.publish(new AuditEvent(
                AuditEventType.TENANT_MEMBER_ADDED,
                Instant.now(clock),
                actorId,
                null,
                tenantId,
                true,
                Map.of(
                        "userId",
                        targetUserId.toString(),
                        "role",
                        role.name(),
                        "status",
                        membership.getStatus().name())));
        return membership;
    }
}
