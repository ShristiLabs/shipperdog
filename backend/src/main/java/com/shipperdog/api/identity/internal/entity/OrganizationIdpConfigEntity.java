package com.shipperdog.api.identity.internal.entity;

import com.shipperdog.api.organization.OrgRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps the {@code organization_idp_configs} table: the organization's
 * OIDC relying-party configuration, real since V49 (S14-01, issue
 * #67). The V3-era jsonb placeholder is gone; every field is typed and
 * validated at write time by {@code SsoConfigService}. The client
 * secret's MATERIAL never touches this row -- only the opaque
 * reference the exchange resolves through the secret store (PRD 25).
 *
 * <p>Ownership note: the identity module owns this mapping since
 * S14-01 (authentication is its bounded context); the route stays
 * {@code PUT/GET /api/v1/organizations/{id}/idp-config} with the same
 * {@code org:idp-configs:*} permissions the S1-04 placeholder
 * introduced.
 */
@Entity
@Table(name = "organization_idp_configs")
public class OrganizationIdpConfigEntity {

    /** The provisioning policy (V49 {@code sso_provisioning_mode}). */
    public enum ProvisioningMode {
        INVITED_ONLY,
        JIT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID organizationId;

    /** Provider kind; {@code OIDC} since S14-01 (SAML joins with S14-02). */
    private String providerType;

    private String issuerUrl;

    private String clientId;

    /** The secret-store reference -- material lives only in the store. */
    private String clientSecretRef;

    /** Space-separated; must contain {@code openid}. */
    private String scopes;

    /** ID-token claim carrying enterprise groups; null disables mapping. */
    private String groupClaimName;

    /** ID-token claim asserting the account is inactive; null disables the leg. */
    private String inactiveClaimName;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private ProvisioningMode provisioningMode;

    /** The least-privilege role JIT provisioning grants (null: VIEWER). */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private OrgRole jitDefaultRole;

    private boolean requireVerifiedEmail;

    /** group claim value -> org_role, applied on every login. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String roleMappings;

    private boolean enabled;

    // ----- SAML columns (S14-02, V50); NULL/unused on OIDC rows -----

    /** Raw IdP metadata XML (EntityDescriptor) as uploaded; audit source of truth. */
    @Column(columnDefinition = "text")
    private String samlMetadataXml;

    /** The parsed IdP entityID: assertion Issuer, audience, and recipient pin on it. */
    private String samlIdpEntityId;

    /** Whether the Assertion must carry its own enveloped signature (default true). */
    private boolean samlWantAssertionsSigned;

    /** Whether the Response must be signed on top (Workspace shape; default false). */
    private boolean samlWantResponseSigned;

    /**
     * The SAML mandate: password login is refused for this organization's
     * members (platform administrators keep their break-glass local
     * login). Enforced only while the configuration is enabled.
     */
    private boolean enforceSsoLogin;

    private Instant createdAt;

    private Instant updatedAt;

    protected OrganizationIdpConfigEntity() {}

    public OrganizationIdpConfigEntity(
            UUID organizationId,
            String providerType,
            String issuerUrl,
            String clientId,
            String clientSecretRef,
            String scopes,
            String groupClaimName,
            String inactiveClaimName,
            ProvisioningMode provisioningMode,
            OrgRole jitDefaultRole,
            boolean requireVerifiedEmail,
            String roleMappings,
            boolean enabled,
            String samlMetadataXml,
            String samlIdpEntityId,
            boolean samlWantAssertionsSigned,
            boolean samlWantResponseSigned,
            boolean enforceSsoLogin) {
        this.organizationId = organizationId;
        this.providerType = providerType;
        this.issuerUrl = issuerUrl;
        this.clientId = clientId;
        this.clientSecretRef = clientSecretRef;
        this.scopes = scopes;
        this.groupClaimName = groupClaimName;
        this.inactiveClaimName = inactiveClaimName;
        this.provisioningMode = provisioningMode;
        this.jitDefaultRole = jitDefaultRole;
        this.requireVerifiedEmail = requireVerifiedEmail;
        this.roleMappings = roleMappings;
        this.enabled = enabled;
        this.samlMetadataXml = samlMetadataXml;
        this.samlIdpEntityId = samlIdpEntityId;
        this.samlWantAssertionsSigned = samlWantAssertionsSigned;
        this.samlWantResponseSigned = samlWantResponseSigned;
        this.enforceSsoLogin = enforceSsoLogin;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getProviderType() {
        return providerType;
    }

    public String getIssuerUrl() {
        return issuerUrl;
    }

    public void setIssuerUrl(String issuerUrl) {
        this.issuerUrl = issuerUrl;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecretRef() {
        return clientSecretRef;
    }

    public void setClientSecretRef(String clientSecretRef) {
        this.clientSecretRef = clientSecretRef;
    }

    public String getScopes() {
        return scopes;
    }

    public void setScopes(String scopes) {
        this.scopes = scopes;
    }

    public String getGroupClaimName() {
        return groupClaimName;
    }

    public void setGroupClaimName(String groupClaimName) {
        this.groupClaimName = groupClaimName;
    }

    public String getInactiveClaimName() {
        return inactiveClaimName;
    }

    public void setInactiveClaimName(String inactiveClaimName) {
        this.inactiveClaimName = inactiveClaimName;
    }

    public ProvisioningMode getProvisioningMode() {
        return provisioningMode;
    }

    public void setProvisioningMode(ProvisioningMode provisioningMode) {
        this.provisioningMode = provisioningMode;
    }

    public OrgRole getJitDefaultRole() {
        return jitDefaultRole;
    }

    public void setJitDefaultRole(OrgRole jitDefaultRole) {
        this.jitDefaultRole = jitDefaultRole;
    }

    public boolean isRequireVerifiedEmail() {
        return requireVerifiedEmail;
    }

    public void setRequireVerifiedEmail(boolean requireVerifiedEmail) {
        this.requireVerifiedEmail = requireVerifiedEmail;
    }

    public String getRoleMappings() {
        return roleMappings;
    }

    public void setRoleMappings(String roleMappings) {
        this.roleMappings = roleMappings;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSamlMetadataXml() {
        return samlMetadataXml;
    }

    public void setSamlMetadataXml(String samlMetadataXml) {
        this.samlMetadataXml = samlMetadataXml;
    }

    public String getSamlIdpEntityId() {
        return samlIdpEntityId;
    }

    public void setSamlIdpEntityId(String samlIdpEntityId) {
        this.samlIdpEntityId = samlIdpEntityId;
    }

    public boolean isSamlWantAssertionsSigned() {
        return samlWantAssertionsSigned;
    }

    public void setSamlWantAssertionsSigned(boolean samlWantAssertionsSigned) {
        this.samlWantAssertionsSigned = samlWantAssertionsSigned;
    }

    public boolean isSamlWantResponseSigned() {
        return samlWantResponseSigned;
    }

    public void setSamlWantResponseSigned(boolean samlWantResponseSigned) {
        this.samlWantResponseSigned = samlWantResponseSigned;
    }

    public boolean isEnforceSsoLogin() {
        return enforceSsoLogin;
    }

    public void setEnforceSsoLogin(boolean enforceSsoLogin) {
        this.enforceSsoLogin = enforceSsoLogin;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
