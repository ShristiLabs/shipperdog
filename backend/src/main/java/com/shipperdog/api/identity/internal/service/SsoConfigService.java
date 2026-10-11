package com.shipperdog.api.identity.internal.service;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.distribution.ChannelSecrets;
import com.shipperdog.api.distribution.SecretMaterial;
import com.shipperdog.api.identity.internal.AuthException;
import com.shipperdog.api.identity.internal.entity.OrganizationIdpConfigEntity;
import com.shipperdog.api.identity.internal.repository.OrganizationIdpConfigRepository;
import com.shipperdog.api.organization.OrgRole;
import com.shipperdog.api.organization.OrganizationAccess;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * The organization's OIDC relying-party configuration (S14-01, issue
 * #67): authoring and reading {@code PUT/GET
 * /api/v1/organizations/{id}/idp-config} -- the route the S1-04
 * placeholder introduced, now typed and real. Permission enforcement
 * stays at the controller ({@code org:idp-configs:write/read}).
 *
 * <p>The client secret follows the platform's standing credential
 * posture (PRD 25, ADR-0008): material handed to the write is stored
 * ONCE through the secret store and only the returned reference is
 * kept; rotation (a new secret on a later write) discards the replaced
 * reference's material. An operator who provisioned the secret
 * themselves may instead hand the reference directly -- exactly one of
 * the two may arrive per write, and neither keeps material in this
 * row, in a log, or on an audit line.
 */
@Service
public class SsoConfigService {

    /** The provider kinds the typed configuration understands. */
    private static final String PROVIDER_OIDC = "OIDC";

    /** The least-privilege default of JIT provisioning. */
    private static final OrgRole DEFAULT_JIT_ROLE = OrgRole.VIEWER;

    private final OrganizationIdpConfigRepository configs;
    private final OrganizationAccess organizations;
    private final ChannelSecrets secrets;
    private final AuditEventPublisher audit;
    private final Clock clock;
    private final ObjectMapper mapper;

    SsoConfigService(
            OrganizationIdpConfigRepository configs,
            OrganizationAccess organizations,
            ChannelSecrets secrets,
            AuditEventPublisher audit,
            Clock clock,
            ObjectMapper mapper) {
        this.configs = configs;
        this.organizations = organizations;
        this.secrets = secrets;
        this.audit = audit;
        this.clock = clock;
        this.mapper = mapper;
    }

    /** The authored configuration for the read route; uniform 404 when none. */
    @Transactional(readOnly = true)
    public SsoConfig view(UUID organizationId) {
        return viewOf(mustFindConfig(organizationId));
    }

    /**
     * Upserts the configuration. Every field is revalidated on every
     * write (no partial free-form echo); the secret binding follows the
     * store-reference doctrine described on the class.
     */
    @Transactional
    public SsoConfig update(UUID actorId, UUID organizationId, UpdateRequest request) {
        mustFindOrganization(organizationId);
        String providerType = requireProvider(request.providerType());
        String issuerUrl = requireIssuerUrl(request.issuerUrl());
        String clientId = requireClientId(request.clientId());
        String scopes = requireScopes(request.scopes());
        String groupClaimName = optionalClaimName(request.groupClaimName(), "groupClaimName");
        String inactiveClaimName = optionalClaimName(request.inactiveClaimName(), "inactiveClaimName");
        OrganizationIdpConfigEntity.ProvisioningMode mode = requireProvisioningMode(request.provisioningMode());
        OrgRole jitRole = requireJitRole(request.jitDefaultRole());
        boolean requireVerifiedEmail = request.requireVerifiedEmail() == null || request.requireVerifiedEmail();
        Map<String, OrgRole> mappings = requireRoleMappings(request.roleMappings());
        boolean enabled = request.enabled() != null && request.enabled();

        OrganizationIdpConfigEntity existing = configs.findByOrganizationIdAndProviderType(organizationId, providerType)
                .orElse(null);
        String previousSecretRef = existing == null ? null : existing.getClientSecretRef();
        boolean previouslyEnabled = existing != null && existing.isEnabled();
        String secretRef = resolveSecretRef(organizationId, request, previousSecretRef);

        if (existing == null) {
            existing = new OrganizationIdpConfigEntity(
                    organizationId,
                    providerType,
                    issuerUrl,
                    clientId,
                    secretRef,
                    scopes,
                    groupClaimName,
                    inactiveClaimName,
                    mode,
                    jitRole,
                    requireVerifiedEmail,
                    mapper.writeValueAsString(mappings),
                    enabled);
        } else {
            existing.setIssuerUrl(issuerUrl);
            existing.setClientId(clientId);
            existing.setClientSecretRef(secretRef);
            existing.setScopes(scopes);
            existing.setGroupClaimName(groupClaimName);
            existing.setInactiveClaimName(inactiveClaimName);
            existing.setProvisioningMode(mode);
            existing.setJitDefaultRole(jitRole);
            existing.setRequireVerifiedEmail(requireVerifiedEmail);
            existing.setRoleMappings(mapper.writeValueAsString(mappings));
            existing.setEnabled(enabled);
        }
        configs.save(existing);

        // The replaced reference's material must not linger decryptable
        // behind an unreferenced row (the slot doctrine).
        if (previousSecretRef != null && !previousSecretRef.equals(secretRef)) {
            secrets.delete(previousSecretRef);
        }

        Map<String, String> configured = new LinkedHashMap<>();
        configured.put("providerType", providerType);
        configured.put("issuerUrl", issuerUrl);
        configured.put("clientSecretRef", secretRef);
        configured.put("provisioningMode", mode.name());
        configured.put("jitDefaultRole", jitRole.name());
        configured.put("requireVerifiedEmail", Boolean.toString(requireVerifiedEmail));
        configured.put("groupClaimName", groupClaimName == null ? "" : groupClaimName);
        configured.put("inactiveClaimName", inactiveClaimName == null ? "" : inactiveClaimName);
        configured.put("roleMappingCount", Integer.toString(mappings.size()));
        configured.put("enabled.before", Boolean.toString(previouslyEnabled));
        configured.put("enabled.after", Boolean.toString(enabled));
        audit.publish(new AuditEvent(
                AuditEventType.ORG_IDP_CONFIGURED,
                Instant.now(clock),
                actorId,
                organizationId,
                null,
                true,
                Map.copyOf(configured)));
        return viewOf(existing);
    }

    /**
     * The enabled configuration the login flow resolves, or empty when
     * SSO is off or unconfigured (the options route's uniform miss).
     */
    @Transactional(readOnly = true)
    public java.util.Optional<OrganizationIdpConfigEntity> enabledConfig(UUID organizationId) {
        return configs.findByOrganizationIdAndProviderType(organizationId, PROVIDER_OIDC)
                .filter(OrganizationIdpConfigEntity::isEnabled)
                .filter(config -> config.getIssuerUrl() != null
                        && config.getClientId() != null
                        && config.getClientSecretRef() != null);
    }

    /** Parsed group-claim -> role mapping of a configuration. */
    @SuppressWarnings("unchecked")
    public Map<String, OrgRole> mappingsOf(OrganizationIdpConfigEntity config) {
        return requireRoleMappings((Map<String, String>) mapper.readValue(config.getRoleMappings(), Map.class));
    }

    private String resolveSecretRef(UUID organizationId, UpdateRequest request, String previousRef) {
        boolean hasMaterial =
                request.clientSecret() != null && !request.clientSecret().isBlank();
        boolean hasReference =
                request.clientSecretRef() != null && !request.clientSecretRef().isBlank();
        if (hasMaterial && hasReference) {
            throw invalid("provide either clientSecret or clientSecretRef, not both");
        }
        if (hasMaterial) {
            return secrets.store(
                    organizationId,
                    "oidc",
                    new SecretMaterial(request.clientSecret().trim()));
        }
        if (hasReference) {
            String reference = request.clientSecretRef().trim();
            if (reference.length() > 512) {
                throw invalid("clientSecretRef is too long (max 512 characters)");
            }
            return reference;
        }
        if (previousRef != null) {
            return previousRef;
        }
        throw invalid("clientSecret (or a pre-provisioned clientSecretRef) is required");
    }

    private OrganizationIdpConfigEntity mustFindConfig(UUID organizationId) {
        return configs.findByOrganizationIdAndProviderType(organizationId, PROVIDER_OIDC)
                .orElseThrow(() ->
                        new AuthException(AuthException.Code.NOT_FOUND, "no identity provider configuration stored"));
    }

    private void mustFindOrganization(UUID organizationId) {
        if (!organizations.organizationExists(organizationId)) {
            throw new AuthException(AuthException.Code.NOT_FOUND, "organization not found");
        }
    }

    private static String requireProvider(String providerType) {
        if (providerType == null || !"oidc".equalsIgnoreCase(providerType.trim())) {
            throw invalid("providerType must be OIDC");
        }
        return PROVIDER_OIDC;
    }

    /**
     * The issuer identifier: an https URL, or http for loopback hosts
     * (local development and the mock provider), with no query or
     * fragment. Paths stay (Entra's {@code /<tenant>/v2.0} shape).
     */
    private static String requireIssuerUrl(String issuerUrl) {
        if (issuerUrl == null || issuerUrl.isBlank() || issuerUrl.trim().length() > 512) {
            throw invalid("issuerUrl is required (max 512 characters)");
        }
        URI uri;
        try {
            uri = URI.create(issuerUrl.trim());
        } catch (RuntimeException e) {
            throw invalid("issuerUrl must be a valid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        boolean loopback =
                host != null && (host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("::1"));
        if (!"https".equals(scheme) && !("http".equals(scheme) && loopback)) {
            throw invalid("issuerUrl must be https (http only for loopback hosts)");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw invalid("issuerUrl must carry no query or fragment");
        }
        String normalized = uri.toString();
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private static String requireClientId(String clientId) {
        if (clientId == null || clientId.isBlank() || clientId.trim().length() > 255) {
            throw invalid("clientId is required (max 255 characters)");
        }
        return clientId.trim();
    }

    private static String requireScopes(String scopes) {
        String value = scopes == null || scopes.isBlank() ? "openid email" : scopes.trim();
        if (value.length() > 512) {
            throw invalid("scopes is too long (max 512 characters)");
        }
        boolean openid = false;
        for (String scope : value.split("\\s+")) {
            if (scope.isBlank()) {
                continue;
            }
            if (!scope.matches("[\\x21-\\x7e]{1,128}")) {
                throw invalid("scopes contains an invalid token");
            }
            if (scope.equals("openid")) {
                openid = true;
            }
        }
        if (!openid) {
            throw invalid("scopes must contain openid");
        }
        return value;
    }

    private static String optionalClaimName(String claimName, String field) {
        if (claimName == null || claimName.isBlank()) {
            return null;
        }
        String trimmed = claimName.trim();
        if (trimmed.length() > 128 || !trimmed.matches("[A-Za-z0-9_.-]{1,128}")) {
            throw invalid(field + " must be 1-128 characters of [A-Za-z0-9_.-] when provided");
        }
        return trimmed;
    }

    private static OrganizationIdpConfigEntity.ProvisioningMode requireProvisioningMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return OrganizationIdpConfigEntity.ProvisioningMode.INVITED_ONLY;
        }
        try {
            return OrganizationIdpConfigEntity.ProvisioningMode.valueOf(
                    mode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw invalid("provisioningMode must be INVITED_ONLY or JIT");
        }
    }

    private static OrgRole requireJitRole(String role) {
        if (role == null || role.isBlank()) {
            return DEFAULT_JIT_ROLE;
        }
        try {
            return OrgRole.valueOf(role.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw invalid("jitDefaultRole must be a valid organization role");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, OrgRole> requireRoleMappings(Map<String, String> mappings) {
        if (mappings == null || mappings.isEmpty()) {
            return Map.of();
        }
        Map<String, OrgRole> parsed = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : mappings.entrySet()) {
            String group = entry.getKey();
            if (group == null || group.isBlank() || group.length() > 128) {
                throw invalid("roleMappings keys must be 1-128 characters");
            }
            try {
                parsed.put(group, OrgRole.valueOf(entry.getValue().trim().toUpperCase(Locale.ROOT)));
            } catch (RuntimeException e) {
                throw invalid("roleMappings values must be valid organization roles");
            }
        }
        return parsed;
    }

    private static AuthException invalid(String message) {
        return new AuthException(AuthException.Code.INVALID_REQUEST, message);
    }

    private SsoConfig viewOf(OrganizationIdpConfigEntity entity) {
        Map<String, String> mappings = new LinkedHashMap<>();
        mappingsOf(entity).forEach((group, role) -> mappings.put(group, role.name()));
        return new SsoConfig(
                entity.getProviderType(),
                entity.getIssuerUrl(),
                entity.getClientId(),
                entity.getClientSecretRef(),
                entity.getScopes(),
                entity.getGroupClaimName(),
                entity.getInactiveClaimName(),
                entity.getProvisioningMode().name(),
                entity.getJitDefaultRole() == null
                        ? null
                        : entity.getJitDefaultRole().name(),
                entity.isRequireVerifiedEmail(),
                mappings,
                entity.isEnabled(),
                entity.getUpdatedAt());
    }

    /** The write shape; {@code clientSecret} is write-only and never echoed. */
    public record UpdateRequest(
            String providerType,
            String issuerUrl,
            String clientId,
            String clientSecret,
            String clientSecretRef,
            String scopes,
            String groupClaimName,
            String inactiveClaimName,
            String provisioningMode,
            String jitDefaultRole,
            Boolean requireVerifiedEmail,
            Map<String, String> roleMappings,
            Boolean enabled) {}

    /** The read shape: every auditable fact, never the secret material. */
    public record SsoConfig(
            String providerType,
            String issuerUrl,
            String clientId,
            String clientSecretRef,
            String scopes,
            String groupClaimName,
            String inactiveClaimName,
            String provisioningMode,
            String jitDefaultRole,
            boolean requireVerifiedEmail,
            Map<String, String> roleMappings,
            boolean enabled,
            Instant updatedAt) {}
}
