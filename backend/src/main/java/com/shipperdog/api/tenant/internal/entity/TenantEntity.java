package com.shipperdog.api.tenant.internal.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Maps the {@code tenants} table (V1 baseline; lifecycle since issue #9). */
@Entity
@Table(name = "tenants")
public class TenantEntity {

    /**
     * Lifecycle per issue #9: ACTIVE is the only operating state,
     * DEACTIVATED is the reversible off state (V7). V1's DISABLED label
     * stays reserved-unused, mirroring the org_status precedent (V3).
     */
    public enum Status {
        ACTIVE,
        DISABLED,
        DEACTIVATED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID organizationId;

    private String name;

    private String externalId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private Status status;

    private Instant createdAt;

    private Instant updatedAt;

    protected TenantEntity() {}

    /** New tenant (issue #9); status starts ACTIVE per the V1 default. */
    public TenantEntity(UUID organizationId, String name, String externalId) {
        this.organizationId = organizationId;
        this.name = name;
        this.externalId = externalId;
        this.status = Status.ACTIVE;
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

    public String getName() {
        return name;
    }

    public String getExternalId() {
        return externalId;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public void setStatus(Status status) {
        this.status = status;
    }
}
