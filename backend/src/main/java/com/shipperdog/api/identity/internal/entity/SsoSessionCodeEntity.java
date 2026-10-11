package com.shipperdog.api.identity.internal.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps the {@code sso_session_codes} table (V49, S14-01): the one-time
 * code the SSO callback hands the console. The SPA exchanges it once
 * for the same access/refresh pair a local login receives, so token
 * material never rides a URL and no secret sits in a redirect. Stored
 * hashed; token minting happens at exchange time, never before.
 */
@Entity
@Table(name = "sso_session_codes")
public class SsoSessionCodeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String codeHash;

    private UUID userId;

    private UUID organizationId;

    private Instant createdAt;

    private Instant expiresAt;

    private Instant consumedAt;

    protected SsoSessionCodeEntity() {}

    public SsoSessionCodeEntity(String codeHash, UUID userId, UUID organizationId, Instant expiresAt) {
        this.codeHash = codeHash;
        this.userId = userId;
        this.organizationId = organizationId;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public void consume() {
        this.consumedAt = Instant.now();
    }
}
