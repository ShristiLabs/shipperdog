package com.shipperdog.api.identity.internal.entity;

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
 * Maps the {@code scim_tokens} table (V51, S14-03): the live bearer
 * token of an organization's SCIM connector. ONE live token per
 * organization (the UNIQUE constraint): generating a new one replaces
 * the row, so rotation kills the old token atomically. Only the
 * SHA-256 hex hash is stored — the plaintext exists once, in the
 * generation response, exactly like a personal access token.
 */
@Entity
@Table(name = "scim_tokens")
public class ScimTokenEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID organizationId;

    private String tokenHash;

    /** Optional operator label naming the connector (the provisioning log's "by which IdP"). */
    private String label;

    private Instant createdAt;

    private Instant lastUsedAt;

    private Instant updatedAt;

    protected ScimTokenEntity() {}

    public ScimTokenEntity(UUID organizationId, String tokenHash, String label) {
        this.organizationId = organizationId;
        this.tokenHash = tokenHash;
        this.label = label;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
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

    public String getTokenHash() {
        return tokenHash;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public void markUsed(Instant at) {
        this.lastUsedAt = at;
    }

    /**
     * Rotation in place: the row keeps its identity and the
     * one-live-token-per-organization constraint while the credential
     * it vouches for changes atomically — the old hash stops
     * authenticating with the same transaction.
     */
    public void regenerate(String tokenHash, String label, Instant at) {
        this.tokenHash = tokenHash;
        this.label = label;
        this.createdAt = at;
        this.lastUsedAt = null;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
