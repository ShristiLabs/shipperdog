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
 * Maps the {@code sso_authorization_requests} table (V49, S14-01):
 * one in-flight OIDC authorization request. The state and nonce are
 * stored as SHA-256 digests (the raw values are bearer capabilities in
 * the browser URL); the PKCE code verifier must survive verbatim for
 * the token exchange. Single-use (consumed at callback) and
 * short-lived (TTL enforced at consume time; expired rows are inert).
 */
@Entity
@Table(name = "sso_authorization_requests")
public class SsoAuthorizationRequestEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID organizationId;

    private String stateHash;

    private String nonceHash;

    private String codeVerifier;

    private Instant createdAt;

    private Instant expiresAt;

    private Instant consumedAt;

    protected SsoAuthorizationRequestEntity() {}

    public SsoAuthorizationRequestEntity(
            UUID organizationId, String stateHash, String nonceHash, String codeVerifier, Instant expiresAt) {
        this.organizationId = organizationId;
        this.stateHash = stateHash;
        this.nonceHash = nonceHash;
        this.codeVerifier = codeVerifier;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getStateHash() {
        return stateHash;
    }

    public String getNonceHash() {
        return nonceHash;
    }

    public String getCodeVerifier() {
        return codeVerifier;
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
