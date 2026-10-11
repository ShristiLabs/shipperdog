package com.shipperdog.api.identity.internal.service;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.distribution.ChannelSecrets;
import com.shipperdog.api.distribution.SecretMaterial;
import com.shipperdog.api.identity.internal.AuthException;
import com.shipperdog.api.identity.internal.entity.OrganizationIdpConfigEntity;
import com.shipperdog.api.identity.internal.repository.OrganizationIdpConfigRepository;
import com.shipperdog.api.identity.internal.saml.SamlIdpMetadata;
import com.shipperdog.api.identity.internal.saml.SamlMetadataParser;
import com.shipperdog.api.identity.internal.saml.SamlValidationException;
import com.shipperdog.api.organization.OrgRole;
import com.shipperdog.api.organization.OrganizationAccess;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * The organization's SSO provider configuration (S14-01 OIDC, S14-02
 * SAML -- issue #68): authoring and reading {@code PUT/GET
 * /api/v1/organizations/{id}/idp-config}, one row per provider kind.
 * Permission enforcement stays at the controller
 * ({@code org:idp-configs:write/read}).
 *
 * <p><strong>OIDC rows</strong> carry the typed relying-party fields
 * with the client secret behind the platform's standing credential
 * posture (PRD 25, ADR-0008): material handed to the write is stored
 * ONCE through the secret store and only the returned reference is
 * kept; rotation (a new secret on a later write) discards the replaced
 * reference's material. An operator who provisioned the secret
 * themselves may instead hand the reference directly -- exactly one of
 * the two may arrive per write, and neither keeps material in this
 * row, in a log, or on an audit line.
 *
 * <p><strong>SAML rows</strong> carry the uploaded IdP metadata XML
 * (validated at write time: parseable EntityDescriptor, entityID, an
 * HTTP-Redirect SSO endpoint, at least one signing certificate), the
 * signature policy (at least one of assertion/response signatures
 * REQUIRED), and the optional SAML mandate ({@code enforceSsoLogin}:
 * password login refused for the organization's members while the
 * configuration is enabled; platform administrators keep their
 * break-glass local login). The provisioning/linkage/mapping columns
 * are shared by both kinds.
 */
@Service
public class SsoConfigService {

    /** The provider kinds the typed configuration understands. */
    static final String PROVIDER_OIDC = "OIDC";

    static final String PROVIDER_SAML = "SAML";

    /** The least-privilege default of JIT provisioning. */
    private static final OrgRole DEFAULT_JIT_ROLE = OrgRole.VIEWER;

    /** The ceiling on an uploaded IdP metadata document. */
    private static final int MAX_METADATA_CHARS = 256 * 1024;

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
     * write (no partial free-form echo); the OIDC secret binding
     * follows the store-reference doctrine, and the SAML metadata is
     * parsed and pinned at write time (nothing unparseable or
     * anchor-less is ever stored).
     */
    @Transactional
    public SsoConfig update(UUID actorId, UUID organizationId, UpdateRequest request) {
        mustFindOrganization(organizationId);
        String providerType = requireProvider(request.providerType());
        boolean isSaml = PROVIDER_SAML.equals(providerType);
        OrganizationIdpConfigEntity.ProvisioningMode mode = requireProvisioningMode(request.provisioningMode());
        OrgRole jitRole = requireJitRole(request.jitDefaultRole());
        boolean requireVerifiedEmail = request.requireVerifiedEmail() == null || request.requireVerifiedEmail();
        String groupClaimName =
                optionalClaimName(request.groupClaimName(), isSaml ? "saml attribute name" : "groupClaimName");
        Map<String, OrgRole> mappings = requireRoleMappings(request.roleMappings());
        boolean enabled = request.enabled() != null && request.enabled();

        String issuerUrl;
        String clientId;
        String secretRef;
        String scopes;
        String inactiveClaimName;
        String metadataXml;
        String idpEntityId;
        boolean wantAssertionsSigned;
        boolean wantResponseSigned;
        boolean enforceSsoLogin;
        if (isSaml) {
            if (request.inactiveClaimName() != null
                    && !request.inactiveClaimName().isBlank()) {
                throw invalid("inactiveClaimName applies to OIDC providers only");
            }
            metadataXml = requireMetadataXml(request.idpMetadataXml());
            SamlIdpMetadata metadata = parseMetadata(metadataXml);
            idpEntityId = metadata.entityId();
            issuerUrl = idpEntityId;
            clientId = null;
            secretRef = null;
            scopes = null;
            inactiveClaimName = null;
            wantAssertionsSigned = request.wantAssertionsSigned() == null || request.wantAssertionsSigned();
            wantResponseSigned = request.wantResponseSigned() != null && request.wantResponseSigned();
            if (!wantAssertionsSigned && !wantResponseSigned) {
                throw invalid("at least one of wantAssertionsSigned or wantResponseSigned is required");
            }
            enforceSsoLogin = request.enforceSsoLogin() != null && request.enforceSsoLogin();
        } else {
            issuerUrl = requireIssuerUrl(request.issuerUrl());
            clientId = requireClientId(request.clientId());
            scopes = requireScopes(request.scopes());
            inactiveClaimName = optionalClaimName(request.inactiveClaimName(), "inactiveClaimName");
            metadataXml = null;
            idpEntityId = null;
            secretRef = null;
            wantAssertionsSigned = false;
            wantResponseSigned = false;
            enforceSsoLogin = false;
        }

        OrganizationIdpConfigEntity existing = configs.findByOrganizationIdAndProviderType(organizationId, providerType)
                .orElse(null);
        String previousSecretRef = existing == null ? null : existing.getClientSecretRef();
        boolean previouslyEnabled = existing != null && existing.isEnabled();
        boolean previousEnforce = existing != null && existing.isEnforceSsoLogin();
        if (!isSaml) {
            secretRef = resolveSecretRef(organizationId, request, previousSecretRef);
        }

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
                    enabled,
                    metadataXml,
                    idpEntityId,
                    wantAssertionsSigned,
                    wantResponseSigned,
                    enforceSsoLogin);
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
            existing.setSamlMetadataXml(metadataXml);
            existing.setSamlIdpEntityId(idpEntityId);
            existing.setSamlWantAssertionsSigned(wantAssertionsSigned);
            existing.setSamlWantResponseSigned(wantResponseSigned);
            existing.setEnforceSsoLogin(enforceSsoLogin);
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
        if (isSaml) {
            configured.put("idpEntityId", idpEntityId);
            configured.put("wantAssertionsSigned", Boolean.toString(wantAssertionsSigned));
            configured.put("wantResponseSigned", Boolean.toString(wantResponseSigned));
            configured.put("enforceSsoLogin", Boolean.toString(enforceSsoLogin));
            configured.put("enforceSsoLogin.before", Boolean.toString(previousEnforce));
        } else {
            configured.put("clientSecretRef", secretRef);
        }
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
     * The enabled OIDC configuration the OIDC login flow resolves, or
     * empty when SSO is off or unconfigured for that kind.
     */
    @Transactional(readOnly = true)
    public Optional<OrganizationIdpConfigEntity> enabledOidcConfig(UUID organizationId) {
        return configs.findByOrganizationIdAndProviderType(organizationId, PROVIDER_OIDC)
                .filter(OrganizationIdpConfigEntity::isEnabled)
                .filter(config -> config.getIssuerUrl() != null
                        && config.getClientId() != null
                        && config.getClientSecretRef() != null);
    }

    /**
     * The enabled SAML configuration the SAML flow resolves, or empty
     * when SAML is off, unconfigured, or missing its pinned metadata.
     */
    @Transactional(readOnly = true)
    public Optional<OrganizationIdpConfigEntity> enabledSamlConfig(UUID organizationId) {
        return configs.findByOrganizationIdAndProviderType(organizationId, PROVIDER_SAML)
                .filter(OrganizationIdpConfigEntity::isEnabled)
                .filter(config -> config.getSamlMetadataXml() != null && config.getSamlIdpEntityId() != null);
    }

    /**
     * The enabled configuration of ANY provider kind (the options
     * probe and the authorize dispatch ask this).
     */
    @Transactional(readOnly = true)
    public Optional<OrganizationIdpConfigEntity> enabledProviderConfig(UUID organizationId) {
        Optional<OrganizationIdpConfigEntity> oidc = enabledOidcConfig(organizationId);
        return oidc.isPresent() ? oidc : enabledSamlConfig(organizationId);
    }

    /**
     * The SAML mandate's password-login gate (S14-02): true when ANY
     * organization the user actively belongs to carries an enabled
     * {@code enforceSsoLogin} configuration. Platform administrators
     * never consult this (break-glass).
     */
    @Transactional(readOnly = true)
    public boolean passwordLoginBlockedFor(UUID userId) {
        return configs.existsByOrganizationIdInAndEnforceSsoLoginTrueAndEnabledTrue(
                organizations.activeOrganizationRoles(userId).keySet());
    }

    /** Parsed group-claim/attribute -> role mapping of a configuration. */
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
                .or(() -> configs.findByOrganizationIdAndProviderType(organizationId, PROVIDER_SAML))
                .orElseThrow(() ->
                        new AuthException(AuthException.Code.NOT_FOUND, "no identity provider configuration stored"));
    }

    private void mustFindOrganization(UUID organizationId) {
        if (!organizations.organizationExists(organizationId)) {
            throw new AuthException(AuthException.Code.NOT_FOUND, "organization not found");
        }
    }

    private static String requireProvider(String providerType) {
        if (providerType == null) {
            throw invalid("providerType is required (OIDC or SAML)");
        }
        String trimmed = providerType.trim();
        if ("oidc".equalsIgnoreCase(trimmed)) {
            return PROVIDER_OIDC;
        }
        if ("saml".equalsIgnoreCase(trimmed)) {
            return PROVIDER_SAML;
        }
        throw invalid("providerType must be OIDC or SAML");
    }

    /**
     * The OIDC issuer identifier: an https URL, or http for loopback
     * hosts (local development and the mock provider), with no query or
     * fragment. Paths stay (Entra's {@code /<tenant>/v2.0} shape).
     * SAML rows derive this from the ingested metadata instead.
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

    /**
     * The uploaded IdP metadata XML (SAML): present, size-capped, and
     * parseable into pinned anchors. The distinct parse failure is the
     * configuration error the administrator sees.
     */
    private static String requireMetadataXml(String metadataXml) {
        if (metadataXml == null || metadataXml.isBlank()) {
            throw invalid("idpMetadataXml is required for SAML providers");
        }
        if (metadataXml.length() > MAX_METADATA_CHARS) {
            throw invalid("idpMetadataXml is too large (max 256 KiB)");
        }
        return metadataXml.trim();
    }

    private static SamlIdpMetadata parseMetadata(String metadataXml) {
        try {
            return SamlMetadataParser.parseIdpMetadata(metadataXml);
        } catch (SamlValidationException e) {
            throw invalid("idpMetadataXml is not a usable IdP EntityDescriptor "
                    + "(entityID, an HTTP-Redirect SingleSignOnService, and a signing certificate are required)");
        }
    }

    private static String optionalClaimName(String claimName, String field) {
        if (claimName == null || claimName.isBlank()) {
            return null;
        }
        String trimmed = claimName.trim();
        if (trimmed.length() > 128 || !trimmed.matches("[A-Za-z0-9_.:\\-/]{1,128}")) {
            throw invalid(field + " must be 1-128 characters of [A-Za-z0-9_.:/-] when provided");
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
        List<String> fingerprints = entity.getSamlMetadataXml() == null
                ? List.of()
                : parseMetadata(entity.getSamlMetadataXml()).certificateFingerprints();
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
                fingerprints,
                entity.isSamlWantAssertionsSigned(),
                entity.isSamlWantResponseSigned(),
                entity.isEnforceSsoLogin(),
                entity.getUpdatedAt());
    }

    /**
     * The write shape; {@code clientSecret} is write-only and never
     * echoed. OIDC rows use the issuer/client fields; SAML rows carry
     * {@code idpMetadataXml} and the signature policy instead.
     */
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
            Boolean enabled,
            String idpMetadataXml,
            Boolean wantAssertionsSigned,
            Boolean wantResponseSigned,
            Boolean enforceSsoLogin) {}

    /**
     * The read shape: every auditable fact, never the secret material
     * and never the metadata XML re-echoed (the certificate
     * fingerprints are the audit-friendly projection).
     */
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
            List<String> samlCertificateFingerprints,
            boolean samlWantAssertionsSigned,
            boolean samlWantResponseSigned,
            boolean enforceSsoLogin,
            Instant updatedAt) {}
}
