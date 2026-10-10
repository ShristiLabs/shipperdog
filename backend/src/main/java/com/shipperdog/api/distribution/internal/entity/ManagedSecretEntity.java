package com.shipperdog.api.distribution.internal.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps the {@code managed_secrets} table (V43, ADR-0008): the OSS
 * envelope-encryption store's ciphertext. One row per stored secret --
 * the AEAD-encrypted material, the KEK-wrapped data key, and the key
 * id that identifies the KEK generation -- keyed by the opaque
 * reference the credential bindings hold. Neither payload column can
 * carry plaintext: the store encrypts before it writes (AES-256-GCM,
 * the reference and key id bound as AAD), and the schema test pins
 * the column set exactly (AC3's discipline applied to the store
 * itself).
 *
 * <p>Production posture is GCP Secret Manager + Cloud KMS (PRD 25),
 * where this table stays empty; the envelope store is the
 * self-hostable default that keeps the at-rest guarantee local, its
 * KEK sourced from the deployment environment and never committed.
 */
@Entity
@Table(name = "managed_secrets")
public class ManagedSecretEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** The opaque reference the binding holds; the row's own address for resolution. */
    private String secretReference;

    /** The AEAD ciphertext of the material (Base64); plaintext never reaches this column. */
    private String ciphertext;

    /** The data key wrapped by the KEK (Base64); the envelope's own shape. */
    private String wrappedDataKey;

    /** The KEK generation's identifier (its digest prefix; rotation's forward pointer). */
    private String keyId;

    /** The AEAD algorithm name, stored for forward evolution of the suite. */
    private String algorithm;

    private Instant createdAt;

    private Instant updatedAt;

    protected ManagedSecretEntity() {}

    /** One stored secret: reference, both ciphertexts, and the key facts. */
    public ManagedSecretEntity(
            String secretReference, String ciphertext, String wrappedDataKey, String keyId, String algorithm) {
        this.secretReference = secretReference;
        this.ciphertext = ciphertext;
        this.wrappedDataKey = wrappedDataKey;
        this.keyId = keyId;
        this.algorithm = algorithm;
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

    public String getSecretReference() {
        return secretReference;
    }

    public String getCiphertext() {
        return ciphertext;
    }

    public String getWrappedDataKey() {
        return wrappedDataKey;
    }

    public String getKeyId() {
        return keyId;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
