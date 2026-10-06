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
import com.shipperdog.api.tenant.internal.repository.TenantMemberScopedRepository;
import com.shipperdog.api.tenant.internal.repository.TenantRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant membership and role assignment (issue #5; tenant lifecycle
 * itself lives in {@link TenantService} since issue #9; assignment and
 * the membership status switch arrive with issue #11). This service
 * is the RBAC surface the issues need: listing members, adding members
 * with a tenant role, assigning TENANT_ADMIN/TENANT_TESTER roles,
 * suspending/reactivating memberships, and the cross-module
 * {@link TenantAccess} queries the authorization module's evaluator
 * uses. Endpoint permissions (tenant:members:*) are enforced at the
 * controller; the service keeps business rules only. There is
 * deliberately no last-TENANT_ADMIN guard: an organization
 * administrator (or platform administrator) of the owning organization
 * can always reassign, so a tenant cannot be orphaned.
 *
 * <p>The membership status switch (issue #11 technical notes): access
 * needs both switches on — the user account (identity refuses disabled
 * accounts at login and token refresh) and the membership row (only
 * ACTIVE memberships resolve to roles in the {@link TenantAccess}
 * surface). Suspension therefore strips the member's effective tenant
 * permissions at the next permission evaluation and ends their
 * sessions at the next token refresh, without deleting anything.
 */
@Service
public class TenantMemberService implements TenantAccess {

    private final TenantRepository tenants;
    private final TenantMemberRepository members;
    private final TenantMemberScopedRepository scopedMembers;
    private final UserDirectory directory;
    private final SessionRevocation sessions;
    private final TenantMemberWriteAttempt writes;
    private final TenantMemberConflictResolver conflicts;
    private final AuditEventPublisher audit;
    private final Clock clock;

    TenantMemberService(
            TenantRepository tenants,
            TenantMemberRepository members,
            TenantMemberScopedRepository scopedMembers,
            UserDirectory directory,
            SessionRevocation sessions,
            TenantMemberWriteAttempt writes,
            TenantMemberConflictResolver conflicts,
            AuditEventPublisher audit,
            Clock clock) {
        this.tenants = tenants;
        this.members = members;
        this.scopedMembers = scopedMembers;
        this.directory = directory;
        this.sessions = sessions;
        this.writes = writes;
        this.conflicts = conflicts;
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
                .filter(membership -> membership.getStatus() == TenantMemberEntity.Status.ACTIVE)
                .collect(Collectors.toMap(
                        TenantMemberEntity::getTenantId,
                        TenantMemberEntity::getRole,
                        (first, second) -> first,
                        LinkedHashMap::new));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TenantRole> roleInTenant(UUID tenantId, UUID userId) {
        return members.findByTenantIdAndUserId(tenantId, userId)
                .filter(membership -> membership.getStatus() == TenantMemberEntity.Status.ACTIVE)
                .map(TenantMemberEntity::getRole);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> owningOrganizationId(UUID tenantId) {
        return tenants.findById(tenantId).map(tenant -> tenant.getOrganizationId());
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> tenantIdsOfOrganization(UUID organizationId) {
        return tenants.findByOrganizationId(organizationId).stream()
                .map(TenantEntity::getId)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean acceptsNewAffiliations(UUID tenantId) {
        return tenants.findById(tenantId)
                .map(tenant -> tenant.getStatus() == TenantEntity.Status.ACTIVE)
                .orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, TenantSummary> tenantSummariesOf(Collection<UUID> tenantIds) {
        if (tenantIds.isEmpty()) {
            return Map.of();
        }
        return tenants.findAllById(tenantIds).stream()
                .collect(Collectors.toMap(
                        TenantEntity::getId,
                        tenant -> new TenantSummary(
                                tenant.getId(),
                                tenant.getName(),
                                tenant.getStatus().name())));
    }

    // -------------------------------------------------------------------------
    // Module API (TenantController)
    // -------------------------------------------------------------------------

    /**
     * Lists the tenant's members, paged (issue #8); caller permission is
     * enforced at the controller. The query runs through the
     * tenant-scoped seam (issue #10): the request's tenant context, not
     * an argument, decides which tenant's rows are visible, and the seam
     * applies the platform listing order.
     */
    @Transactional(readOnly = true)
    public Page<MemberView> listMembers(UUID tenantId, long offset, int limit) {
        mustFindTenant(tenantId);
        Page<TenantMemberEntity> memberships =
                scopedMembers.findAllScoped(Specification.unrestricted(), new CursorPageable((int) offset, limit));
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
                    membership.getStatus(),
                    membership.getCreatedAt());
        });
    }

    /**
     * Assigns a user to the tenant with a tenant role (issue #11,
     * acceptance criterion 1): the membership is born ACTIVE. A
     * duplicate assignment with the same role is idempotent — the
     * existing membership is returned unchanged and nothing is written
     * or audited (no change happened); a duplicate with a different
     * role is {@code 409 TENANT_MEMBER_EXISTS} (role changes belong to
     * {@link #assignRole}). Adding any member to a DEACTIVATED tenant
     * is blocked (the affiliation rule carried from issue #9). The
     * unique constraint is the final authority on races: deliberately
     * NOT transactional since issue #28 — the insert runs in
     * {@link TenantMemberWriteAttempt}'s own transaction, so a lost
     * {@code uq_tenant_members_tenant_user} race rolls that back and
     * is answered here with the concurrent winner re-derived on a
     * fresh transaction ({@link TenantMemberConflictResolver}). The
     * former in-transaction catch could not return the idempotent
     * answer: PostgreSQL had already aborted the transaction and the
     * commit failed with UnexpectedRollbackException (500) — the dead
     * catch carried from issues #26/#27.
     */
    public MemberAssignment addMember(UUID tenantId, UUID targetUserId, TenantRole role, UUID actorId) {
        TenantEntity tenant = mustFindTenant(tenantId);
        if (!directory.userExists(targetUserId)) {
            throw new TenantException(TenantException.Code.NOT_FOUND, "user not found");
        }
        TenantMemberEntity existing =
                scopedMembers.findOneScoped(userEquals(targetUserId)).orElse(null);
        if (existing != null) {
            if (existing.getRole() == role) {
                return new MemberAssignment(viewOf(tenantId, existing), false);
            }
            throw new TenantException(
                    TenantException.Code.MEMBER_EXISTS, "user is already a member with a different role");
        }
        if (tenant.getStatus() == TenantEntity.Status.DEACTIVATED) {
            throw new TenantException(
                    TenantException.Code.TENANT_DEACTIVATED, "cannot add members to a deactivated tenant");
        }
        try {
            TenantMemberEntity membership = writes.attempt(actorId, tenantId, targetUserId, role);
            return new MemberAssignment(viewOf(tenantId, membership), true);
        } catch (DataIntegrityViolationException e) {
            // Lost race on uq_tenant_members_tenant_user: the winner's
            // row decides between the idempotent and conflict paths —
            // read on a fresh transaction (this thread holds none open).
            TenantMemberEntity winner = conflicts.winnerOf(targetUserId).orElse(null);
            if (winner != null && winner.getRole() == role) {
                return new MemberAssignment(viewOf(tenantId, winner), false);
            }
            throw new TenantException(
                    TenantException.Code.MEMBER_EXISTS, "user is already a member with a different role");
        }
    }

    /**
     * Suspends or reactivates a membership (issue #11 technical notes:
     * the membership switch, independent of the user account switch).
     * Suspending revokes the member's refresh tokens — their tenant
     * context ends at the next token refresh at the latest, and the
     * live role resolution stops granting the tenant role immediately.
     * Both transitions stay possible on a DEACTIVATED tenant: an
     * existing membership is not a new affiliation, and a deactivated
     * tenant remains administrable (issue #9/#10 decisions). A no-op
     * request returns the current view and is audited with equal
     * before/after, mirroring tenant updates.
     */
    @Transactional
    public MemberView changeMemberStatus(
            UUID tenantId, UUID targetUserId, TenantMemberEntity.Status target, UUID actorId) {
        mustFindTenant(tenantId);
        TenantMemberEntity membership = scopedMembers
                .findOneScoped(userEquals(targetUserId))
                .orElseThrow(() -> new TenantException(TenantException.Code.NOT_FOUND, "member not found"));
        TenantMemberEntity.Status before = membership.getStatus();
        boolean suspended =
                before != TenantMemberEntity.Status.SUSPENDED && target == TenantMemberEntity.Status.SUSPENDED;
        membership.setStatus(target);
        scopedMembers.saveScoped(membership);
        if (suspended) {
            sessions.revokeRefreshTokens(targetUserId, "membership_suspended");
        }
        audit.publish(new AuditEvent(
                AuditEventType.TENANT_MEMBER_STATUS_CHANGED,
                Instant.now(clock),
                actorId,
                null,
                tenantId,
                true,
                Map.of(
                        "userId",
                        targetUserId.toString(),
                        "status.before",
                        before.name(),
                        "status.after",
                        target.name(),
                        "sessionsRevoked",
                        Boolean.toString(suspended))));
        return viewOf(tenantId, membership);
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
                scopedMembers.findOneScoped(userEquals(targetUserId)).orElse(null);
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
        scopedMembers.saveScoped(membership);
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
        return viewOf(tenantId, membership);
    }

    /** Removes a tenant membership; removal always revokes the member's refresh tokens. */
    @Transactional
    public void removeMember(UUID tenantId, UUID targetUserId, UUID actorId) {
        mustFindTenant(tenantId);
        TenantMemberEntity membership = scopedMembers
                .findOneScoped(userEquals(targetUserId))
                .orElseThrow(() -> new TenantException(TenantException.Code.NOT_FOUND, "member not found"));
        scopedMembers.delete(membership);
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

    /** Membership-by-user condition, composed with the seam's tenant predicate. */
    private static Specification<TenantMemberEntity> userEquals(UUID userId) {
        return (root, query, builder) -> builder.equal(root.get("userId"), userId);
    }

    /** Resolves the member's display fields for a returned view. */
    private MemberView viewOf(UUID tenantId, TenantMemberEntity membership) {
        UserDirectory.UserSummary user = directory.byIds(List.of(membership.getUserId())).stream()
                .findFirst()
                .orElse(null);
        return new MemberView(
                membership.getUserId(),
                user == null ? null : user.email(),
                user == null ? null : user.fullName(),
                membership.getRole(),
                membership.getStatus(),
                membership.getCreatedAt());
    }

    /**
     * Tenant member view (issue #11: carries the membership status
     * switch); display fields are null when the directory has no
     * summary.
     */
    public record MemberView(
            UUID userId,
            String email,
            String fullName,
            TenantRole role,
            TenantMemberEntity.Status status,
            Instant createdAt) {}

    /** Assignment outcome: the member view and whether a row was created. */
    public record MemberAssignment(MemberView view, boolean created) {}
}
