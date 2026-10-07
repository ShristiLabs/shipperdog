package com.shipperdog.api.environment.internal.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps the {@code environment_tenants} table (V27, issue #58, AC4): one
 * assignment of one tenant to one environment — the set of tenants an
 * environment's releases MAY target, validated at release creation
 * (S9-01's surface) the moment a request names the environment. Empty
 * set means no tenant may be targeted (deny-by-default, PRD P3);
 * assignment is an allow-list, never a mirror of organization
 * membership.
 *
 * <p>A plain junction child of the organization-grain environment (the
 * {@code release_targets} shape): composite key over the two foreign
 * keys, created_at only (rows are replaced by the assignment write,
 * never individually updated), no owning-dimension snapshot, no seam
 * repository — the environment is resolved through its own
 * organization-checked path first.
 */
@Entity
@Table(name = "environment_tenants")
@IdClass(EnvironmentTenantId.class)
public class EnvironmentTenantEntity {

    @Id
    private UUID environmentId;

    @Id
    private UUID tenantId;

    private Instant createdAt;

    protected EnvironmentTenantEntity() {}

    /** One assignment: the environment admits the tenant as a release target. */
    public EnvironmentTenantEntity(UUID environmentId, UUID tenantId) {
        this.environmentId = environmentId;
        this.tenantId = tenantId;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getEnvironmentId() {
        return environmentId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
