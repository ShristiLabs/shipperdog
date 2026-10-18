package com.shipperdog.api.otabundle.internal.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * One issued {@code sdc1.} app token (V55 {@code ota_app_tokens},
 * S16-04, ADR-0010 D3): the SHA-256 hex of the token material only —
 * write-only doctrine (the V39 webhook-secret / V49 code-hash
 * precedent; the token itself is returned exactly once at issue and
 * never stored), app-scoped through the owning application, and
 * revocable by row: {@code revoked_at} is NULL while live and the
 * authentication filter's hash lookup refuses it the moment it lands.
 *
 * <p>Deliberately NOT a {@code TenantScope} entity: the V55 registry
 * carries the owning organization and application, no tenant column —
 * the row's grain is the application (it must be resolvable by hash
 * from an anonymous device request, before any tenant is known; the
 * tenant dimension arrives from the application row at resolution).
 * Its repository is therefore a plain repository over
 * organization-owned data, the {@code sso_session_codes} shape, and
 * every console path constrains by the application the permission
 * check already resolved — a foreign application's tokens are
 * unreachable.
 */
@Entity
@Table(name = "ota_app_tokens")
public class OtaAppTokenEntity {

    @Id
    private UUID id;

    private UUID organizationId;

    private UUID applicationId;

    /** The human label the issue/revoke surfaces distinguish tokens by. */
    private String name;

    /** Write-only: the SHA-256 hex of the full {@code sdc1.} token string. */
    private String tokenHash;

    private UUID createdBy;

    private Instant createdAt;

    private Instant updatedAt;

    /** NULL while the token is live; the revoke action stamps it. */
    private Instant revokedAt;

    protected OtaAppTokenEntity() {}

    public OtaAppTokenEntity(
            UUID id, UUID organizationId, UUID applicationId, String name, String tokenHash, UUID createdBy) {
        this.id = id;
        this.organizationId = organizationId;
        this.applicationId = applicationId;
        this.name = name;
        this.tokenHash = tokenHash;
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

    public UUID getApplicationId() {
        return applicationId;
    }

    public String getName() {
        return name;
    }

    public String getTokenHash() {
        return tokenHash;
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

    public boolean isLive() {
        return revokedAt == null;
    }

    /**
     * Revocation's write: stamps the row dead (idempotent — a live row only). The stamp is
     * truncated to microseconds before persisting: the column's precision is microseconds, so
     * the first response and any replay render the identical value regardless of the host
     * clock's nanosecond granularity.
     */
    public void revoke() {
        if (revokedAt == null) {
            this.revokedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        }
    }
}
