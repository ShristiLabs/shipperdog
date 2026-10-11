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
 * Maps the {@code saml_authorization_requests} table (V50, S14-02):
 * one in-flight SP-initiated SAML authorization request. The
 * AuthnRequest ID (the value the Response's {@code InResponseTo} must
 * repeat) and the RelayState are stored as SHA-256 digests -- the raw
 * values are bearer capabilities riding the browser URL. Single-use
 * (consumed atomically at the ACS, exactly like the OIDC state row)
 * and short-lived (TTL enforced at consume time; expired rows are
 * inert and swept with the OIDC flow state).
 */
@Entity
@Table(name = "saml_authorization_requests")
public class SamlAuthorizationRequestEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID organizationId;

    private String requestIdHash;

    private String relayStateHash;

    private Instant createdAt;

    private Instant expiresAt;

    private Instant consumedAt;

    protected SamlAuthorizationRequestEntity() {}

    public SamlAuthorizationRequestEntity(
            UUID organizationId, String requestIdHash, String relayStateHash, Instant expiresAt) {
        this.organizationId = organizationId;
        this.requestIdHash = requestIdHash;
        this.relayStateHash = relayStateHash;
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

    public String getRequestIdHash() {
        return requestIdHash;
    }

    public String getRelayStateHash() {
        return relayStateHash;
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
}
