package com.shipperdog.api.tenant.internal.service;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.authorization.RolePermissions;
import com.shipperdog.api.config.CursorPageable;
import com.shipperdog.api.identity.SessionRevocation;
import com.shipperdog.api.identity.UserDirectory;
import com.shipperdog.api.tenant.TenantAccess;
import com.shipperdog.api.tenant.TenantException;
import com.shipperdog.api.tenant.TenantRole;
import com.shipperdog.api.tenant.internal.entity.TenantEntity;
import com.shipperdog.api.tenant.internal.entity.TenantMemberEntity;
import com.shipperdog.api.tenant.internal.repository.TenantMemberRepository;
import com.shipperdog.api.tenant.internal.repository.TenantRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant membership and role assignment (issue #5; tenant lifecycle
 * itself lives in {@link TenantService} since issue #9). This service
 * is the RBAC surface the issue needs: listing members, assigning
 * TENANT_ADMIN/TENANT_TESTER roles, and the cross-module
 * {@link TenantAccess} queries the authorization module's evaluator
 * uses. Endpoint permissions (tenant:members:*) are enforced at the
 * controller; the service keeps business rules only. There is
 * deliberately no last-TENANT_ADMIN guard: an organization
 * administrator (or platform administrator) of the owning organization
 * can always reassign, so a tenant cannot be orphaned.
 */
@Service
public class TenantMemberService implements TenantAccess {

    private final TenantRepository tenants;
    private final TenantMemberRepository members;
    private final UserDirectory directory;
    private final SessionRevocation sessions;
    private final AuditEventPublisher audit;
    private final Clock clock;

    TenantMemberService(
            TenantRepository tenants,
            TenantMemberRepository members,
            UserDirectory directory,
            SessionRevocation sessions,
            AuditEventPublisher audit,
            Clock clock) {
        this.tenants = tenants;
        this.members = members;
        this.directory = directory;
        this.sessions = sessions;
        this.audit = audit;
        this.clock = clock;
    }

    // -------------------------------------------------------------------------
    // TenantAccess: cross-module surface
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, TenantRole> membershipsOf(UUID userId) {
        return members.findByUserId(userId).stream()
                .collect(Collectors.toMap(
                        TenantMemberEntity::getTenantId,
                        TenantMemberEntity::getRole,
                        (first, second) -> first,
                        LinkedHashMap::new));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TenantRole> roleInTenant(UUID tenantId, UUID userId) {
        return members.findByTenantIdAndUserId(tenantId, userId).map(TenantMemberEntity::getRole);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> owningOrganizationId(UUID tenantId) {
        return tenants.findById(tenantId).map(tenant -> tenant.getOrganizationId());
    }

    // -------------------------------------------------------------------------
    // Module API (TenantController)
    // -------------------------------------------------------------------------

    /** Lists the tenant's members, paged (issue #8); caller permission is enforced at the controller. */
    @Transactional(readOnly = true)
    public Page<MemberView> listMembers(UUID tenantId, long offset, int limit) {
        mustFindTenant(tenantId);
        Page<TenantMemberEntity> memberships =
                members.findByTenantIdOrderByCreatedAtAscIdAsc(tenantId, new CursorPageable((int) offset, limit));
        Map<UUID, UserDirectory.UserSummary> users =
                directory
                        .byIds(memberships.stream()
                                .map(TenantMemberEntity::getUserId)
                                .toList())
                        .stream()
                        .collect(Collectors.toMap(UserDirectory.UserSummary::id, Function.identity()));
        return memberships.map(membership -> {
            UserDirectory.UserSummary user = users.get(membership.getUserId());
            return new MemberView(
                    membership.getUserId(),
                    user == null ? null : user.email(),
                    user == null ? null : user.fullName(),
                    membership.getRole(),
                    membership.getCreatedAt());
        });
    }

    /**
     * Assigns (or changes) a member's tenant role. Assignment to a
     * non-member creates the membership — blocked while the tenant is
     * DEACTIVATED, the membership analog of "no new invitations" (issue
     * #9, acceptance criterion 3); role changes of existing members stay
     * possible so a deactivated tenant remains administrable. Tenant
     * testers need not be organization members. A permission-losing
     * change revokes the member's refresh tokens (issue #5, acceptance
     * criterion 5).
     */
    @Transactional
    public MemberView assignRole(UUID tenantId, UUID targetUserId, TenantRole newRole, UUID actorId) {
        TenantEntity tenant = mustFindTenant(tenantId);
        if (!directory.userExists(targetUserId)) {
            throw new TenantException(TenantException.Code.NOT_FOUND, "user not found");
        }
        TenantMemberEntity membership =
                members.findByTenantIdAndUserId(tenantId, targetUserId).orElse(null);
        boolean demotion;
        if (membership == null) {
            if (tenant.getStatus() == TenantEntity.Status.DEACTIVATED) {
                throw new TenantException(
                        TenantException.Code.TENANT_DEACTIVATED, "cannot add members to a deactivated tenant");
            }
            membership = new TenantMemberEntity(tenantId, targetUserId, newRole);
            demotion = false;
        } else {
            TenantRole previous = membership.getRole();
            demotion =
                    !RolePermissions.lostOnTenantRoleChange(previous, newRole).isEmpty();
            membership.setRole(newRole);
        }
        members.save(membership);
        if (demotion) {
            sessions.revokeRefreshTokens(targetUserId, "role_demoted");
        }
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("userId", targetUserId.toString());
        detail.put("role.after", newRole.name());
        detail.put("sessionsRevoked", Boolean.toString(demotion));
        audit.publish(new AuditEvent(
                AuditEventType.TENANT_MEMBER_ROLE_CHANGED,
                Instant.now(clock),
                actorId,
                null,
                tenantId,
                true,
                Map.copyOf(detail)));
        return new MemberView(targetUserId, null, null, newRole, membership.getCreatedAt());
    }

    /** Removes a tenant membership; removal always revokes the member's refresh tokens. */
    @Transactional
    public void removeMember(UUID tenantId, UUID targetUserId, UUID actorId) {
        mustFindTenant(tenantId);
        TenantMemberEntity membership = members.findByTenantIdAndUserId(tenantId, targetUserId)
                .orElseThrow(() -> new TenantException(TenantException.Code.NOT_FOUND, "member not found"));
        members.delete(membership);
        sessions.revokeRefreshTokens(targetUserId, "membership_removed");
        audit.publish(new AuditEvent(
                AuditEventType.TENANT_MEMBER_REMOVED,
                Instant.now(clock),
                actorId,
                null,
                tenantId,
                true,
                Map.of(
                        "userId",
                        targetUserId.toString(),
                        "role",
                        membership.getRole().name())));
    }

    private TenantEntity mustFindTenant(UUID tenantId) {
        return tenants.findById(tenantId)
                .orElseThrow(() -> new TenantException(TenantException.Code.NOT_FOUND, "tenant not found"));
    }

    /** Tenant member view; display fields are null when returned from an assignment. */
    public record MemberView(UUID userId, String email, String fullName, TenantRole role, Instant createdAt) {}
}
