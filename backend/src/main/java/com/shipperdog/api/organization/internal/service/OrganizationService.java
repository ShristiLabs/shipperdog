package com.shipperdog.api.organization.internal.service;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.PlatformAdmins;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Organization lifecycle and membership (issue #4): creation by platform
 * administrators (creator becomes ORG_ADMIN), admin-gated settings and
 * status changes, the stored IdP configuration record (S14 placeholder),
 * and the cross-module {@link OrganizationAccess} queries the identity
 * module uses for login blocks and token claims. Every mutation emits an
 * audit event carrying who/what and before/after detail.
 */
@Service
public class OrganizationService implements OrganizationAccess {

    private final OrganizationRepository organizations;
    private final OrganizationMemberRepository members;
    private final OrganizationIdpConfigRepository idpConfigs;
    private final PlatformAdmins platformAdmins;
    private final AuditEventPublisher audit;
    private final ObjectMapper mapper;
    private final Clock clock;

    OrganizationService(
            OrganizationRepository organizations,
            OrganizationMemberRepository members,
            OrganizationIdpConfigRepository idpConfigs,
            PlatformAdmins platformAdmins,
            AuditEventPublisher audit,
            ObjectMapper mapper,
            Clock clock) {
        this.organizations = organizations;
        this.members = members;
        this.idpConfigs = idpConfigs;
        this.platformAdmins = platformAdmins;
        this.audit = audit;
        this.mapper = mapper;
        this.clock = clock;
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

    /** Creates an organization; the creator becomes its ORG_ADMIN (AC1). */
    @Transactional
    public OrganizationEntity create(UUID actorId, String name, String externalId) {
        requirePlatformAdmin(actorId, "only platform administrators may create organizations");
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
        Map<String, String> created = new LinkedHashMap<>();
        created.put("name", validatedName);
        if (validatedExternalId != null) {
            created.put("externalId", validatedExternalId);
        }
        audit(AuditEventType.ORGANIZATION_CREATED, actorId, organization.getId(), true, Map.copyOf(created));
        return organization;
    }

    @Transactional(readOnly = true)
    public OrganizationEntity get(UUID actorId, UUID organizationId) {
        OrganizationEntity organization = mustFind(organizationId);
        requireOrganizationAdmin(organization.getId(), actorId);
        return organization;
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
     * fields are left unchanged; externalId changes are platform-admin
     * only because they map to billing/CRM records (AC2).
     */
    @Transactional
    public OrganizationEntity update(UUID actorId, UUID organizationId, String name, String status, String externalId) {
        OrganizationEntity organization = mustFind(organizationId);
        requireOrganizationAdmin(organization.getId(), actorId);
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
            requirePlatformAdmin(
                    actorId, "only platform administrators may change the external organization identifier");
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

    /** Stores (upserts) the IdP configuration record (S14 placeholder). */
    @Transactional
    public IdpConfig updateIdpConfig(UUID actorId, UUID organizationId, String providerType, JsonNode config) {
        OrganizationEntity organization = mustFind(organizationId);
        requireOrganizationAdmin(organization.getId(), actorId);
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

    @Transactional(readOnly = true)
    public IdpConfig getIdpConfig(UUID actorId, UUID organizationId) {
        mustFind(organizationId);
        requireOrganizationAdmin(organizationId, actorId);
        OrganizationIdpConfigEntity entity = idpConfigs
                .findByOrganizationId(organizationId)
                .orElseThrow(() -> new OrganizationException(
                        OrganizationException.Code.NOT_FOUND, "no identity provider configuration stored"));
        return new IdpConfig(entity.getProviderType(), mapper.readTree(entity.getConfig()), entity.getUpdatedAt());
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

    /** AC2 permission gate: ORG_ADMIN membership or platform administrator. */
    private void requireOrganizationAdmin(UUID organizationId, UUID actorId) {
        if (!platformAdmins.isPlatformAdmin(actorId) && !isOrganizationAdmin(organizationId, actorId)) {
            throw new OrganizationException(
                    OrganizationException.Code.FORBIDDEN, "organization administrator role required");
        }
    }

    private void requirePlatformAdmin(UUID actorId, String message) {
        if (!platformAdmins.isPlatformAdmin(actorId)) {
            throw new OrganizationException(OrganizationException.Code.FORBIDDEN, message);
        }
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
}
