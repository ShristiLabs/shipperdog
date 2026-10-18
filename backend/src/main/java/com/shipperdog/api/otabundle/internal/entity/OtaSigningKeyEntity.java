package com.shipperdog.api.otabundle.internal.entity;

import com.shipperdog.api.tenant.OrganizationScope;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One app-scoped OTA signing-key generation (V58 {@code
 * ota_signing_keys}, S16-03, ADR-0010 D8): the PUBLIC half and the
 * secret-store reference only — the private key lives behind the
 * {@code ChannelSecrets} port (ADR-0008) and never enters this row,
 * this table, or any log. One ACTIVE row per application (the partial
 * unique index {@code uq_ota_signing_keys_active}); rotation stamps
 * {@code revokedAt} on the old row in the same transaction a new
 * generation lands, so a device's embedded public key keeps verifying
 * old bundles while new ones sign under the new key id.
 */
@Entity
@Table(name = "ota_signing_keys")
public class OtaSigningKeyEntity implements OrganizationScope {

    @Id
    private UUID id;

    private UUID organizationId;

    /** Mirrors the owning application's dimension: null when shared. */
    private UUID tenantId;

    private UUID applicationId;

    /** The public identifier riding bundles and the check manifest. */
    private String keyId;

    /** Base64 of the raw 32-byte Ed25519 public key. */
    private String publicKey;

    /** The secret-store port's opaque reference to the private key. */
    private String secretReference;

    /** D8's scheme; Ed25519 via JCA only (ADR-0009). */
    private String algorithm;

    private UUID createdBy;

    private Instant createdAt;

    private Instant updatedAt;

    /** NULL while active; rotation stamps it. */
    private Instant revokedAt;

    protected OtaSigningKeyEntity() {}

    public OtaSigningKeyEntity(
            UUID id,
            UUID organizationId,
            UUID tenantId,
            UUID applicationId,
            String keyId,
            String publicKey,
            String secretReference,
            UUID createdBy) {
        this.id = id;
        this.organizationId = organizationId;
        this.tenantId = tenantId;
        this.applicationId = applicationId;
        this.keyId = keyId;
        this.publicKey = publicKey;
        this.secretReference = secretReference;
        this.algorithm = "Ed25519";
        this.createdBy = createdBy;
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

    @Override
    public UUID organizationId() {
        return organizationId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    @Override
    public UUID tenantId() {
        return tenantId;
    }

    public UUID getApplicationId() {
        return applicationId;
    }

    public String getKeyId() {
        return keyId;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public String getSecretReference() {
        return secretReference;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public boolean isActive() {
        return revokedAt == null;
    }

    /** Rotation's write: stamps the generation inactive. */
    public void revoke() {
        this.revokedAt = Instant.now();
    }
}
