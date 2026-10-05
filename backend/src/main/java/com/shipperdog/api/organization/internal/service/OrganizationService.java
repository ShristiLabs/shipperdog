package com.shipperdog.api.organization.internal.service;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.authorization.RolePermissions;
import com.shipperdog.api.config.TraceAttributes;
import com.shipperdog.api.identity.PlatformAdmins;
import com.shipperdog.api.identity.SessionRevocation;
import com.shipperdog.api.identity.UserDirectory;
import com.shipperdog.api.organization.OrgRole;
import com.shipperdog.api.organization.OrganizationAccess;
import com.shipperdog.api.organization.OrganizationException;
import com.shipperdog.api.organization.internal.entity.OrganizationEntity;
import com.shipperdog.api.organization.internal.entity.OrganizationIdpConfigEntity;
import com.shipperdog.api.organization.internal.entity.OrganizationMemberEntity;
import com.shipperdog.api.organization.internal.repository.OrganizationIdpConfigRepository;
import com.shipperdog.api.organization.internal.repository.OrganizationMemberRepository;
import com.shipperdog.api.organization.internal.repository.OrganizationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Organization lifecycle and membership (issues #4 and #5): creation by
 * platform administrators (creator becomes ORG_ADMIN), settings and
 * status changes, the stored IdP configuration record (S14 placeholder),
 * member listing and role assignment (S1-05), and the cross-module
 * {@link OrganizationAccess} queries the identity and authorization
 * modules use for login blocks, token claims, and permission snapshots.
 * Every mutation emits an audit event carrying who/what and before/after
 * detail.
 *
 * <p>Since S1-05, endpoint authorization is declared with
 * {@code @PreAuthorize} permission annotations enforced by the
 * authorization module — the single enforcement point. The service keeps
 * only business rules (existence, lifecycle state, the last-admin
 * guard) plus the one parameter-level refinement (externalId changes
 * stay platform-admin only inside the general update permission).
 */
@Service
public class OrganizationService implements OrganizationAccess {

    private final OrganizationRepository organizations;
    private final OrganizationMemberRepository members;
    private final OrganizationIdpConfigRepository idpConfigs;
    private final PlatformAdmins platformAdmins;
    /**
     * Lazy on purpose: TokenService depends on OrganizationAccess, so a
     * direct constructor dependency here would close a bean cycle. The
     * provider resolves the SessionRevocation binding (TokenService) on
     * first demotion only.
     */
    private final ObjectProvider<SessionRevocation> sessions;

    private final UserDirectory directory;
    private final AuditEventPublisher audit;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TraceAttributes trace;

    OrganizationService(
            OrganizationRepository organizations,
            OrganizationMemberRepository members,
            OrganizationIdpConfigRepository idpConfigs,
            PlatformAdmins platformAdmins,
            ObjectProvider<SessionRevocation> sessions,
            UserDirectory directory,
            AuditEventPublisher audit,
            ObjectMapper mapper,
            Clock clock,
            TraceAttributes trace) {
        this.organizations = organizations;
        this.members = members;
        this.idpConfigs = idpConfigs;
        this.platformAdmins = platformAdmins;
        this.sessions = sessions;
        this.directory = directory;
        this.audit = audit;
        this.mapper = mapper;
        this.clock = clock;
        this.trace = trace;
    }

    // -------------------------------------------------------------------------
    // OrganizationAccess: cross-module surface
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> primaryOrganizationId(UUID userId) {
        // Earliest ACTIVE membership only: a user whose earliest
        // organization is DEACTIVATED but who holds a later active one is
        // login-eligible and must not carry a deactivated organization's
        // id in the org_id claim. Users without an active organization
        // (org-less, or deactivated-only such as exempt platform admins)
        // get a context-free token.
        return members
                .findByUserIdAndOrganizationStatus(userId, OrganizationEntity.Status.ACTIVE, PageRequest.of(0, 1))
                .stream()
                .findFirst()
                .map(OrganizationMemberEntity::getOrganizationId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean loginBlockedFor(UUID userId) {
        if (members.countByUserId(userId) == 0) {
            return false;
        }
        return members.countByUserIdAndOrganizationStatus(userId, OrganizationEntity.Status.ACTIVE) == 0;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isOrganizationAdmin(UUID organizationId, UUID userId) {
        return members.existsByOrganizationIdAndUserIdAndRole(organizationId, userId, OrgRole.ORG_ADMIN);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, OrgRole> activeOrganizationRoles(UUID userId) {
        return members
                .findByUserIdAndOrganizationStatus(userId, OrganizationEntity.Status.ACTIVE, Pageable.unpaged())
                .stream()
                .collect(Collectors.toMap(
                        OrganizationMemberEntity::getOrganizationId,
                        OrganizationMemberEntity::getRole,
                        (first, second) -> first,
                        LinkedHashMap::new));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean organizationExists(UUID organizationId) {
        return organizations.existsById(organizationId);
    }

    @Override
    @Transactional
    public void addMember(UUID organizationId, UUID userId, OrgRole role, UUID actingUserId) {
        OrganizationEntity organization = mustFind(organizationId);
        if (organization.getStatus() == OrganizationEntity.Status.DEACTIVATED) {
            throw new OrganizationException(
                    OrganizationException.Code.ORGANIZATION_DEACTIVATED,
                    "cannot add members to a deactivated organization");
        }
        if (members.existsByOrganizationIdAndUserId(organizationId, userId)) {
            throw new OrganizationException(OrganizationException.Code.MEMBER_EXISTS, "user is already a member");
        }
        members.save(new OrganizationMemberEntity(organizationId, userId, role));
        audit(
                AuditEventType.ORG_MEMBER_ADDED,
                actingUserId,
                organizationId,
                true,
                Map.of("userId", userId.toString(), "role", role.name()));
    }

    // -------------------------------------------------------------------------
    // Module API (OrganizationController)
    // -------------------------------------------------------------------------

    /**
     * Creates an organization; the creator becomes its ORG_ADMIN (AC1).
     * Caller permission (platform:organizations:create) is enforced by the
     * controller annotation (S1-05).
     */
    @Transactional
    public OrganizationEntity create(UUID actorId, String name, String externalId) {
        String validatedName = requireName(name);
        String validatedExternalId = validateExternalId(externalId);
        if (validatedExternalId != null && organizations.existsByExternalId(validatedExternalId)) {
            throw externalIdTaken();
        }
        OrganizationEntity organization;
        try {
            organization = organizations.save(new OrganizationEntity(validatedName, validatedExternalId));
        } catch (DataIntegrityViolationException e) {
            throw externalIdTaken();
        }
        members.save(new OrganizationMemberEntity(organization.getId(), actorId, OrgRole.ORG_ADMIN));
        // The created id is only known here, after the insert — creation
        // requests have no guarded target id for the evaluator to tag,
        // so the service stamps the trace itself (issue #7).
        trace.organization(organization.getId());
        Map<String, String> created = new LinkedHashMap<>();
        created.put("name", validatedName);
        if (validatedExternalId != null) {
            created.put("externalId", validatedExternalId);
        }
        audit(AuditEventType.ORGANIZATION_CREATED, actorId, organization.getId(), true, Map.copyOf(created));
        return organization;
    }

    /** Read is permission-gated at the controller (org:organizations:read). */
    @Transactional(readOnly = true)
    public OrganizationEntity get(UUID organizationId) {
        return mustFind(organizationId);
    }

    /** Platform admins see every organization; members see their own. */
    @Transactional(readOnly = true)
    public List<OrganizationEntity> list(UUID actorId) {
        if (platformAdmins.isPlatformAdmin(actorId)) {
            return organizations.findAll(Sort.by("createdAt").ascending());
        }
        return organizations.findAllForMember(actorId, Sort.by("createdAt").ascending());
    }

    /**
     * Updates organization settings (name, status, externalId). Null
     * fields are left unchanged. Caller permission
     * (org:organizations:update) is enforced at the controller; the
     * externalId branch keeps its finer parameter-level rule — platform
     * administrators only — because external ids map to billing/CRM
     * records (AC2).
     */
    @Transactional
    public OrganizationEntity update(UUID actorId, UUID organizationId, String name, String status, String externalId) {
        OrganizationEntity organization = mustFind(organizationId);
        Map<String, String> changes = new LinkedHashMap<>();
        if (name != null) {
            String validated = requireName(name);
            putChange(changes, "name", organization.getName(), validated);
            organization.setName(validated);
        }
        if (status != null) {
            OrganizationEntity.Status parsed = parseLifecycleStatus(status);
            putChange(changes, "status", organization.getStatus().name(), parsed.name());
            organization.setStatus(parsed);
        }
        if (externalId != null) {
            // Parameter-level refinement inside org:organizations:update:
            // the billing/CRM mapping is platform-admin territory.
            if (!platformAdmins.isPlatformAdmin(actorId)) {
                throw new OrganizationException(
                        OrganizationException.Code.FORBIDDEN,
                        "only platform administrators may change the external organization identifier");
            }
            String validated = validateExternalId(externalId);
            if (validated != null
                    && !validated.equals(organization.getExternalId())
                    && organizations.existsByExternalId(validated)) {
                throw externalIdTaken();
            }
            putChange(changes, "externalId", organization.getExternalId(), validated);
            organization.setExternalId(validated);
        }
        try {
            organization = organizations.save(organization);
        } catch (DataIntegrityViolationException e) {
            throw externalIdTaken();
        }
        audit(AuditEventType.ORGANIZATION_UPDATED, actorId, organization.getId(), true, Map.copyOf(changes));
        return organization;
    }

    /**
     * Stores (upserts) the IdP configuration record (S14 placeholder).
     * Caller permission (org:idp-configs:write) is enforced at the
     * controller (S1-05).
     */
    @Transactional
    public IdpConfig updateIdpConfig(UUID actorId, UUID organizationId, String providerType, JsonNode config) {
        mustFind(organizationId);
        String validatedProvider = requireProviderType(providerType);
        String serialized = serializeConfig(config);
        OrganizationIdpConfigEntity entity =
                idpConfigs.findByOrganizationId(organizationId).orElse(null);
        String previousProvider = entity == null ? null : entity.getProviderType();
        if (entity == null) {
            entity = new OrganizationIdpConfigEntity(organizationId, validatedProvider, serialized);
        } else {
            entity.setProviderType(validatedProvider);
            entity.setConfig(serialized);
        }
        idpConfigs.save(entity);
        Map<String, String> configured = new LinkedHashMap<>();
        configured.put("providerType.before", previousProvider == null ? "" : previousProvider);
        configured.put("providerType.after", validatedProvider);
        configured.put("record", "stored");
        audit(AuditEventType.ORG_IDP_CONFIGURED, actorId, organizationId, true, Map.copyOf(configured));
        return new IdpConfig(validatedProvider, config, entity.getUpdatedAt());
    }

    /** Read is permission-gated at the controller (org:idp-configs:read). */
    @Transactional(readOnly = true)
    public IdpConfig getIdpConfig(UUID organizationId) {
        mustFind(organizationId);
        OrganizationIdpConfigEntity entity = idpConfigs
                .findByOrganizationId(organizationId)
                .orElseThrow(() -> new OrganizationException(
                        OrganizationException.Code.NOT_FOUND, "no identity provider configuration stored"));
        return new IdpConfig(entity.getProviderType(), mapper.readTree(entity.getConfig()), entity.getUpdatedAt());
    }

    // -------------------------------------------------------------------------
    // Member listing and role assignment (issue #5)
    // -------------------------------------------------------------------------

    /**
     * Lists the organization's members with their roles; display fields
     * resolve through the identity module's {@link UserDirectory}.
     * Caller permission (org:members:read) is enforced at the controller.
     */
    @Transactional(readOnly = true)
    public List<MemberView> listMembers(UUID organizationId) {
        mustFind(organizationId);
        List<OrganizationMemberEntity> memberships =
                members.findByOrganizationIdOrderByCreatedAtAscIdAsc(organizationId);
        Map<UUID, UserDirectory.UserSummary> users =
                directory
                        .byIds(memberships.stream()
                                .map(OrganizationMemberEntity::getUserId)
                                .toList())
                        .stream()
                        .collect(Collectors.toMap(UserDirectory.UserSummary::id, Function.identity()));
        return memberships.stream()
                .map(membership -> {
                    UserDirectory.UserSummary user = users.get(membership.getUserId());
                    return new MemberView(
                            membership.getUserId(),
                            user == null ? null : user.email(),
                            user == null ? null : user.fullName(),
                            membership.getRole(),
                            membership.getCreatedAt());
                })
                .toList();
    }

    /**
     * Changes a member's organization role (issue #5). A permission-losing
     * change demotes: the member's refresh tokens are revoked so the change
     * takes effect on their next session at the latest (acceptance
     * criterion 5). The last ORG_ADMIN cannot be demoted away from admin;
     * the locked find serializes admin-affecting mutations per
     * organization so two concurrent changes cannot both pass the guard.
     */
    @Transactional
    public MemberView changeMemberRole(UUID organizationId, UUID targetUserId, OrgRole newRole, UUID actorId) {
        OrganizationEntity organization = mustFindLocked(organizationId);
        if (organization.getStatus() == OrganizationEntity.Status.DEACTIVATED) {
            throw new OrganizationException(
                    OrganizationException.Code.ORGANIZATION_DEACTIVATED,
                    "cannot manage members of a deactivated organization");
        }
        OrganizationMemberEntity membership = members.findByOrganizationIdAndUserId(organizationId, targetUserId)
                .orElseThrow(() -> new OrganizationException(OrganizationException.Code.NOT_FOUND, "member not found"));
        OrgRole previous = membership.getRole();
        requireNotLastAdmin(organizationId, previous, newRole);
        membership.setRole(newRole);
        members.save(membership);
        boolean demotion =
                !RolePermissions.lostOnOrgRoleChange(previous, newRole).isEmpty();
        if (demotion) {
            sessions.getObject().revokeRefreshTokens(targetUserId, "role_demoted");
        }
        audit(
                AuditEventType.ORG_MEMBER_ROLE_CHANGED,
                actorId,
                organizationId,
                true,
                Map.of(
                        "userId",
                        targetUserId.toString(),
                        "role.before",
                        previous.name(),
                        "role.after",
                        newRole.name(),
                        "sessionsRevoked",
                        Boolean.toString(demotion)));
        return new MemberView(targetUserId, null, null, newRole, membership.getCreatedAt());
    }

    /**
     * Removes a membership (issue #5). Removal always revokes the member's
     * refresh tokens. The last ORG_ADMIN cannot be removed; the locked
     * find serializes admin-affecting mutations per organization so two
     * concurrent removals cannot both pass the guard.
     */
    @Transactional
    public void removeMember(UUID organizationId, UUID targetUserId, UUID actorId) {
        OrganizationEntity organization = mustFindLocked(organizationId);
        if (organization.getStatus() == OrganizationEntity.Status.DEACTIVATED) {
            throw new OrganizationException(
                    OrganizationException.Code.ORGANIZATION_DEACTIVATED,
                    "cannot manage members of a deactivated organization");
        }
        OrganizationMemberEntity membership = members.findByOrganizationIdAndUserId(organizationId, targetUserId)
                .orElseThrow(() -> new OrganizationException(OrganizationException.Code.NOT_FOUND, "member not found"));
        requireNotLastAdmin(organizationId, membership.getRole(), null);
        members.delete(membership);
        sessions.getObject().revokeRefreshTokens(targetUserId, "membership_removed");
        audit(
                AuditEventType.ORG_MEMBER_REMOVED,
                actorId,
                organizationId,
                true,
                Map.of(
                        "userId",
                        targetUserId.toString(),
                        "role",
                        membership.getRole().name()));
    }

    /** An organization must keep at least one ORG_ADMIN (defense against lockout). */
    private void requireNotLastAdmin(UUID organizationId, OrgRole previous, OrgRole next) {
        boolean losesAdmin = previous == OrgRole.ORG_ADMIN && next != OrgRole.ORG_ADMIN;
        if (losesAdmin && members.countByOrganizationIdAndRole(organizationId, OrgRole.ORG_ADMIN) <= 1) {
            throw new OrganizationException(
                    OrganizationException.Code.LAST_ORG_ADMIN, "an organization must keep at least one administrator");
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private OrganizationEntity mustFind(UUID organizationId) {
        return organizations
                .findById(organizationId)
                .orElseThrow(() ->
                        new OrganizationException(OrganizationException.Code.NOT_FOUND, "organization not found"));
    }

    /**
     * Like {@link #mustFind}, but takes a row-level lock (SELECT ... FOR
     * UPDATE) on the organization: admin-affecting member mutations
     * serialize per organization, so the last-admin guard's count cannot
     * race between two concurrent demotions or removals (review finding
     * on issue #5).
     */
    private OrganizationEntity mustFindLocked(UUID organizationId) {
        return organizations
                .findWithLockingById(organizationId)
                .orElseThrow(() ->
                        new OrganizationException(OrganizationException.Code.NOT_FOUND, "organization not found"));
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank() || name.trim().length() > 200) {
            throw new OrganizationException(
                    OrganizationException.Code.INVALID_REQUEST, "name is required (max 200 characters)");
        }
        return name.trim();
    }

    private static String requireProviderType(String providerType) {
        if (providerType == null
                || providerType.isBlank()
                || providerType.trim().length() > 64) {
            throw new OrganizationException(
                    OrganizationException.Code.INVALID_REQUEST, "providerType is required (max 64 characters)");
        }
        return providerType.trim();
    }

    /** null stays null (no mapping); blank is rejected; value is trimmed. */
    private static String validateExternalId(String externalId) {
        if (externalId == null) {
            return null;
        }
        if (externalId.isBlank() || externalId.trim().length() > 100) {
            throw new OrganizationException(
                    OrganizationException.Code.INVALID_REQUEST, "externalId must be 1-100 characters when provided");
        }
        return externalId.trim();
    }

    /** Only the issue #4 lifecycle values; DISABLED stays reserved-unused. */
    private static OrganizationEntity.Status parseLifecycleStatus(String status) {
        if ("ACTIVE".equals(status) || "DEACTIVATED".equals(status)) {
            return OrganizationEntity.Status.valueOf(status);
        }
        throw new OrganizationException(
                OrganizationException.Code.INVALID_REQUEST, "status must be ACTIVE or DEACTIVATED");
    }

    private String serializeConfig(JsonNode config) {
        if (config == null || config.isMissingNode() || !config.isObject()) {
            throw new OrganizationException(OrganizationException.Code.INVALID_REQUEST, "config must be a JSON object");
        }
        return mapper.writeValueAsString(config);
    }

    private static OrganizationException externalIdTaken() {
        return new OrganizationException(
                OrganizationException.Code.EXTERNAL_ID_TAKEN, "externalId is already mapped to another organization");
    }

    /** Before/after pair for audit detail; a null "before" means unset. */
    private static void putChange(Map<String, String> changes, String field, String before, String after) {
        changes.put(field + ".before", before == null ? "" : before);
        changes.put(field + ".after", after == null ? "" : after);
    }

    private void audit(AuditEventType type, UUID actorId, UUID orgId, boolean success, Map<String, String> detailMap) {
        audit.publish(new AuditEvent(type, Instant.now(clock), actorId, orgId, null, success, detailMap));
    }

    /** Stored IdP configuration view (S14 placeholder). */
    public record IdpConfig(String providerType, JsonNode config, Instant updatedAt) {}

    /**
     * Organization member view (issue #5); display fields are null when
     * returned from a role change (the caller already knows the target).
     */
    public record MemberView(UUID userId, String email, String fullName, OrgRole role, Instant createdAt) {}
}
