package com.shipperdog.api.identity.internal.service;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.internal.AuthException;
import com.shipperdog.api.identity.internal.crypto.SecureTokens;
import com.shipperdog.api.identity.internal.entity.ScimConfigEntity;
import com.shipperdog.api.identity.internal.entity.ScimGroupRoleMappingEntity;
import com.shipperdog.api.identity.internal.entity.ScimGroupTenantMappingEntity;
import com.shipperdog.api.identity.internal.entity.ScimTokenEntity;
import com.shipperdog.api.identity.internal.repository.ScimConfigRepository;
import com.shipperdog.api.identity.internal.repository.ScimGroupRoleMappingRepository;
import com.shipperdog.api.identity.internal.repository.ScimGroupTenantMappingRepository;
import com.shipperdog.api.identity.internal.repository.ScimTokenRepository;
import com.shipperdog.api.organization.OrgRole;
import com.shipperdog.api.organization.OrganizationAccess;
import com.shipperdog.api.tenant.TenantAccess;
import com.shipperdog.api.tenant.TenantRole;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The organization's SCIM provisioning configuration (S14-03, issue
 * #69, PRD 25): the {@code /api/v1/organizations/{id}/scim-config}
 * surface — enabled state, the default (floor) role a provisioned
 * user receives, the group mapping rules, and the connector bearer
 * token's lifecycle (generate/rotate, revoke, metadata read).
 * Permission enforcement stays at the controller ({@code
 * org:idp-configs:read/write}, the identity-provider configuration
 * family).
 *
 * <p>The token follows the PAT posture: server-generated
 * ({@code sds_} prefix), stored as its SHA-256 hex hash only, and
 * returned as plaintext ONCE at generation — never on any later
 * read. Rotation replaces the live row atomically (ONE live token
 * per organization), so a rotated-away token dies immediately.
 */
@Service
public class ScimConfigService {

    private final ScimConfigRepository configs;
    private final ScimGroupRoleMappingRepository roleMappings;
    private final ScimGroupTenantMappingRepository tenantMappings;
    private final ScimTokenRepository tokens;
    private final OrganizationAccess organizations;
    private final TenantAccess tenants;
    private final AuditEventPublisher audit;
    private final Clock clock;

    ScimConfigService(
            ScimConfigRepository configs,
            ScimGroupRoleMappingRepository roleMappings,
            ScimGroupTenantMappingRepository tenantMappings,
            ScimTokenRepository tokens,
            OrganizationAccess organizations,
            TenantAccess tenants,
            AuditEventPublisher audit,
            Clock clock) {
        this.configs = configs;
        this.roleMappings = roleMappings;
        this.tenantMappings = tenantMappings;
        this.tokens = tokens;
        this.organizations = organizations;
        this.tenants = tenants;
        this.audit = audit;
        this.clock = clock;
    }

    /** The authored configuration for the read route; uniform 404 when none. */
    @Transactional(readOnly = true)
    public ScimConfig view(UUID organizationId) {
        ScimConfigEntity config = configs.findByOrganizationId(organizationId).orElseThrow(ScimConfigService::notFound);
        return viewOf(config, organizationId);
    }

    /**
     * Upserts the configuration and replaces the mapping rules
     * wholesale (the write is the rules' complete declarative state —
     * the same no-partial-echo discipline the SSO configuration
     * write holds).
     */
    @Transactional
    public ScimConfig update(UUID actorId, UUID organizationId, UpdateRequest request) {
        mustFindOrganization(organizationId);
        boolean enabled = request.enabled() != null && request.enabled();
        OrgRole defaultRole = requireRole(request.defaultRole(), "defaultRole");
        Map<String, OrgRole> parsedRoleMappings = new LinkedHashMap<>();
        for (RoleMapping mapping : safeList(request.groupRoleMappings())) {
            String group = requireGroupName(mapping.group());
            OrgRole role = requireRole(mapping.orgRole(), "groupRoleMappings.orgRole");
            if (parsedRoleMappings.putIfAbsent(group, role) != null) {
                throw invalid("groupRoleMappings lists group '" + group + "' twice");
            }
        }
        Set<String> tenantPairs = new LinkedHashSet<>();
        List<ScimGroupTenantMappingEntity> parsedTenantMappings = new ArrayList<>();
        for (TenantMapping mapping : safeList(request.groupTenantMappings())) {
            String group = requireGroupName(mapping.group());
            UUID tenantId = requireTenantId(mapping.tenantId());
            TenantRole role = requireTenantRole(mapping.tenantRole());
            if (!tenantPairs.add(group + ":" + tenantId)) {
                throw invalid("groupTenantMappings lists group '" + group + "' with tenant " + tenantId + " twice");
            }
            if (!organizationId.equals(tenants.owningOrganizationId(tenantId).orElse(null))) {
                throw invalid("groupTenantMappings references a tenant outside this organization: " + tenantId);
            }
            parsedTenantMappings.add(new ScimGroupTenantMappingEntity(organizationId, group, tenantId, role));
        }

        ScimConfigEntity config = configs.findByOrganizationId(organizationId).orElse(null);
        boolean previouslyEnabled;
        if (config == null) {
            previouslyEnabled = false;
            config = new ScimConfigEntity(organizationId, enabled, defaultRole);
        } else {
            previouslyEnabled = config.isEnabled();
            config.setEnabled(enabled);
            config.setDefaultRole(defaultRole);
        }
        configs.save(config);
        applyRoleMappings(organizationId, parsedRoleMappings);
        applyTenantMappings(organizationId, parsedTenantMappings);

        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("enabled.before", Boolean.toString(previouslyEnabled));
        detail.put("enabled.after", Boolean.toString(enabled));
        detail.put("defaultRole", defaultRole.name());
        detail.put("groupRoleMappings", Integer.toString(parsedRoleMappings.size()));
        detail.put("groupTenantMappings", Integer.toString(parsedTenantMappings.size()));
        audit.publish(new AuditEvent(
                AuditEventType.SCIM_CONFIGURED, Instant.now(clock), actorId, organizationId, null, true, detail));
        return viewOf(config, organizationId);
    }

    /**
     * Generates the organization's connector token (rotation): the
     * plaintext appears exactly once, in this method's answer. A live
     * token's row is replaced atomically, so the old credential dies
     * with the same transaction.
     */
    @Transactional
    public TokenView generateToken(UUID actorId, UUID organizationId, TokenRequest request) {
        mustFindOrganization(organizationId);
        String label = optionalLabel(request == null ? null : request.label());
        String plaintext = ScimTokenAuthenticator.TOKEN_PREFIX + SecureTokens.randomToken();
        ScimTokenEntity existing = tokens.findByOrganizationId(organizationId).orElse(null);
        boolean rotation = existing != null;
        ScimTokenEntity token;
        if (rotation) {
            // In-place regeneration: the row keeps its identity and the
            // constraint, and the hash swap is one UPDATE — the old
            // credential dies with this transaction, atomically.
            existing.regenerate(SecureTokens.sha256Hex(plaintext), label, Instant.now(clock));
            token = tokens.save(existing);
        } else {
            token = tokens.save(new ScimTokenEntity(organizationId, SecureTokens.sha256Hex(plaintext), label));
        }
        audit.publish(new AuditEvent(
                AuditEventType.SCIM_TOKEN_ROTATED,
                Instant.now(clock),
                actorId,
                organizationId,
                null,
                true,
                Map.of("rotation", Boolean.toString(rotation), "label", label == null ? "" : label)));
        return new TokenView(plaintext, label, token.getCreatedAt());
    }

    /** Revokes the live token (SCIM freezes answering until a new one is generated). */
    @Transactional
    public void revokeToken(UUID actorId, UUID organizationId) {
        mustFindOrganization(organizationId);
        ScimTokenEntity existing = tokens.findByOrganizationId(organizationId).orElse(null);
        if (existing == null) {
            throw notFound();
        }
        tokens.delete(existing);
        audit.publish(new AuditEvent(
                AuditEventType.SCIM_TOKEN_ROTATED,
                Instant.now(clock),
                actorId,
                organizationId,
                null,
                true,
                Map.of("rotation", "false", "revoked", "true")));
    }

    /**
     * Replaces the role mapping rules as a DIFF (the insert-before-
     * delete ordering of a flush would otherwise trip the group
     * uniqueness constraint on unchanged rows): removed rules delete,
     * kept rules update in place, new rules insert.
     */
    private void applyRoleMappings(UUID organizationId, Map<String, OrgRole> declared) {
        Map<String, ScimGroupRoleMappingEntity> existing = new LinkedHashMap<>();
        for (ScimGroupRoleMappingEntity row : roleMappings.findByOrganizationId(organizationId)) {
            existing.put(row.getGroupName(), row);
        }
        for (ScimGroupRoleMappingEntity row : existing.values()) {
            if (!declared.containsKey(row.getGroupName())) {
                roleMappings.delete(row);
            }
        }
        declared.forEach((group, role) -> {
            ScimGroupRoleMappingEntity row = existing.get(group);
            if (row == null) {
                roleMappings.save(new ScimGroupRoleMappingEntity(organizationId, group, role));
            } else if (row.getOrgRole() != role) {
                row.setOrgRole(role);
                roleMappings.save(row);
            }
        });
    }

    /** The tenant mapping rules' diff, same discipline as the role rules. */
    private void applyTenantMappings(UUID organizationId, List<ScimGroupTenantMappingEntity> declared) {
        Map<String, ScimGroupTenantMappingEntity> existing = new LinkedHashMap<>();
        for (ScimGroupTenantMappingEntity row : tenantMappings.findByOrganizationId(organizationId)) {
            existing.put(row.getGroupName() + ":" + row.getTenantId(), row);
        }
        Map<String, ScimGroupTenantMappingEntity> declaredByKey = new LinkedHashMap<>();
        for (ScimGroupTenantMappingEntity row : declared) {
            declaredByKey.put(row.getGroupName() + ":" + row.getTenantId(), row);
        }
        for (ScimGroupTenantMappingEntity row : existing.values()) {
            if (!declaredByKey.containsKey(row.getGroupName() + ":" + row.getTenantId())) {
                tenantMappings.delete(row);
            }
        }
        for (ScimGroupTenantMappingEntity row : declaredByKey.values()) {
            ScimGroupTenantMappingEntity current = existing.get(row.getGroupName() + ":" + row.getTenantId());
            if (current == null) {
                tenantMappings.save(row);
            } else if (current.getTenantRole() != row.getTenantRole()) {
                current.setTenantRole(row.getTenantRole());
                tenantMappings.save(current);
            }
        }
    }

    private ScimConfig viewOf(ScimConfigEntity config, UUID organizationId) {
        List<RoleMapping> roleRules = roleMappings.findByOrganizationId(organizationId).stream()
                .map(mapping -> new RoleMapping(
                        mapping.getGroupName(), mapping.getOrgRole().name()))
                .toList();
        List<TenantMapping> tenantRules = tenantMappings.findByOrganizationId(organizationId).stream()
                .map(mapping -> new TenantMapping(
                        mapping.getGroupName(),
                        mapping.getTenantId().toString(),
                        mapping.getTenantRole().name()))
                .toList();
        ScimTokenEntity token = tokens.findByOrganizationId(organizationId).orElse(null);
        TokenMeta tokenMeta =
                token == null ? null : new TokenMeta(token.getLabel(), token.getCreatedAt(), token.getLastUsedAt());
        return new ScimConfig(
                config.isEnabled(),
                config.getDefaultRole().name(),
                roleRules,
                tenantRules,
                tokenMeta,
                config.getUpdatedAt());
    }

    private void mustFindOrganization(UUID organizationId) {
        if (!organizations.organizationExists(organizationId)) {
            throw new AuthException(AuthException.Code.NOT_FOUND, "organization not found");
        }
    }

    private static AuthException notFound() {
        return new AuthException(AuthException.Code.NOT_FOUND, "no SCIM configuration stored");
    }

    private static <T> List<T> safeList(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static OrgRole requireRole(String role, String field) {
        if (role == null || role.isBlank()) {
            if ("defaultRole".equals(field)) {
                return OrgRole.VIEWER;
            }
            throw invalid(field + " is required");
        }
        try {
            return OrgRole.valueOf(role.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw invalid(field + " must be a valid organization role");
        }
    }

    private static TenantRole requireTenantRole(String role) {
        if (role == null || role.isBlank()) {
            throw invalid("groupTenantMappings.tenantRole is required");
        }
        try {
            return TenantRole.valueOf(role.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw invalid("groupTenantMappings.tenantRole must be TENANT_ADMIN or TENANT_TESTER");
        }
    }

    private static UUID requireTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw invalid("groupTenantMappings.tenantId is required");
        }
        try {
            return UUID.fromString(tenantId.trim());
        } catch (IllegalArgumentException e) {
            throw invalid("groupTenantMappings.tenantId must be a UUID");
        }
    }

    private static String requireGroupName(String group) {
        if (group == null || group.isBlank()) {
            throw invalid("mapping group names are required");
        }
        String trimmed = group.trim();
        if (trimmed.length() > 255) {
            throw invalid("mapping group names must be 1-255 characters");
        }
        return trimmed;
    }

    private static String optionalLabel(String label) {
        if (label == null || label.isBlank()) {
            return null;
        }
        String trimmed = label.trim();
        if (trimmed.length() > 128) {
            throw invalid("label must be 1-128 characters when provided");
        }
        return trimmed;
    }

    private static AuthException invalid(String message) {
        return new AuthException(AuthException.Code.INVALID_REQUEST, message);
    }

    /** The write shape: the complete declarative state of the mapping rules. */
    public record UpdateRequest(
            Boolean enabled,
            String defaultRole,
            List<RoleMapping> groupRoleMappings,
            List<TenantMapping> groupTenantMappings) {}

    /** One group -> organization role rule. */
    public record RoleMapping(String group, String orgRole) {}

    /** One group -> tenant membership rule; the tenant must belong to the organization. */
    public record TenantMapping(String group, String tenantId, String tenantRole) {}

    /**
     * The read shape: every auditable fact, never the token (the
     * metadata block carries the label and the timestamps only).
     */
    public record ScimConfig(
            boolean enabled,
            String defaultRole,
            List<RoleMapping> groupRoleMappings,
            List<TenantMapping> groupTenantMappings,
            TokenMeta token,
            Instant updatedAt) {}

    /** The live token's metadata: label and timestamps, never the hash. */
    public record TokenMeta(String label, Instant createdAt, Instant lastUsedAt) {}

    /** The generation request: an optional operator label naming the connector. */
    public record TokenRequest(String label) {}

    /** The generation answer: the plaintext appears exactly once, here. */
    public record TokenView(String token, String label, Instant generatedAt) {}
}
