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

/** Maps the {@code personal_access_tokens} table (V2 migration). */
@Entity
@Table(name = "personal_access_tokens")
public class PersonalAccessTokenEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID userId;

    private String name;

    private String tokenPrefix;

    private String tokenHash;

    private Instant expiresAt;

    private Instant revokedAt;

    private Instant lastUsedAt;

    private Instant createdAt;

    private Instant updatedAt;

    protected PersonalAccessTokenEntity() {}

    public PersonalAccessTokenEntity(
            UUID userId, String name, String tokenPrefix, String tokenHash, Instant expiresAt) {
        this.userId = userId;
        this.name = name;
        this.tokenPrefix = tokenPrefix;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
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

    public UUID getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public String getTokenPrefix() {
        return tokenPrefix;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Instant revokedAt) {
        this.revokedAt = revokedAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(Instant lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
