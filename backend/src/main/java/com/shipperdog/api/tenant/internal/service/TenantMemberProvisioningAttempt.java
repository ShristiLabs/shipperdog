package com.shipperdog.api.tenant.internal.service;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.tenant.TenantRole;
import com.shipperdog.api.tenant.internal.entity.TenantMemberEntity;
import com.shipperdog.api.tenant.internal.repository.TenantMemberRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The unscoped membership insert of the cross-module provisioning
 * surface (S14-03, issue #69): {@code TenantMemberService.
 * ensureMembership} creates a row through this bean ON PURPOSE — the
 * same REQUIRES_NEW + flush discipline {@link TenantMemberWriteAttempt}
 * holds for the tenant-context paths, so a lost {@code
 * uq_tenant_members_tenant_user} race rolls back only this attempt and
 * the caller (whose own transaction stays sound) re-derives the winner
 * instead of answering the dead-catch 500 of issues #26/#27. The
 * caller binds the tenant scope BEFORE this bean runs (the thread-local
 * context survives the REQUIRES_NEW boundary), so the seam's write
 * guard sees the row's own tenant.
 */
@Component
public class TenantMemberProvisioningAttempt {

    private final TenantMemberRepository members;
    private final AuditEventPublisher audit;
    private final Clock clock;

    TenantMemberProvisioningAttempt(TenantMemberRepository members, AuditEventPublisher audit, Clock clock) {
        this.members = members;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Inserts the membership (born ACTIVE) and audits — atomically, in
     * this bean's own transaction. The insert is flushed inside the
     * guard so a lost uniqueness race throws HERE.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TenantMemberEntity attempt(UUID actorId, UUID tenantId, UUID targetUserId, TenantRole role) {
        TenantMemberEntity membership = members.save(new TenantMemberEntity(tenantId, targetUserId, role));
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
                        membership.getStatus().name(),
                        "cascade",
                        "scim_provisioned")));
        return membership;
    }
}
