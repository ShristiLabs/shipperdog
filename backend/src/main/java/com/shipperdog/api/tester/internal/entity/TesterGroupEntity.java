package com.shipperdog.api.tester.internal.entity;

import com.shipperdog.api.tenant.TenantScope;
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
 * Maps the {@code tester_groups} table (V1 baseline; PRD 13 and 8.9,
 * issue #27 / S6-02). A group is a logical tester collection the
 * release engine will target (E9) and ring eligibility will consume
 * (S10-04) — the targeting unit PRD FR-010 names "assign groups".
 *
 * <p>Tenant-scoped user data (ADR-0002, tenth amendment's reserved
 * landing): the entity implements {@link TenantScope}, its endpoint
 * repository extends the isolation seam, and names are unique per
 * tenant ({@code uq_tester_groups_tenant_name}). Groups never cross
 * tenants (org-level groups are out of scope by the issue); the
 * tester-to-group relationship itself lives in
 * {@link TesterGroupMemberEntity}.
 */
@Entity
@Table(name = "tester_groups")
public class TesterGroupEntity implements TenantScope {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID tenantId;

    private String name;

    private Instant createdAt;

    private Instant updatedAt;

    protected TesterGroupEntity() {}

    public TesterGroupEntity(UUID tenantId, String name) {
        this.tenantId = tenantId;
        this.name = name;
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

    public UUID getTenantId() {
        return tenantId;
    }

    @Override
    public UUID tenantId() {
        return tenantId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
