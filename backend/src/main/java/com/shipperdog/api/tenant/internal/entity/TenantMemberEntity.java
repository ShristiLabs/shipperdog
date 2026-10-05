package com.shipperdog.api.tenant.internal.entity;

import com.shipperdog.api.tenant.TenantRole;
import com.shipperdog.api.tenant.TenantScope;
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

/**
 * Maps the {@code tenant_members} table (V4 migration; the status switch
 * arrives with V8, issue #11). Tenant-scoped data (issue #10): endpoint
 * access flows through the scoped repository seam, which filters by the
 * request's tenant context by construction; only the authorization
 * engine's role resolution reads across tenants.
 */
@Entity
@Table(name = "tenant_members")
public class TenantMemberEntity implements TenantScope {

    /**
     * The membership switch (issue #11): a member whose membership is
     * SUSPENDED holds no tenant role, independent of their user account
     * status — access needs both switches on. Suspension is reversible
     * and keeps the row, so the roster stays complete.
     */
    public enum Status {
        ACTIVE,
        SUSPENDED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID tenantId;

    private UUID userId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private TenantRole role;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private Status status;

    private Instant createdAt;

    private Instant updatedAt;

    protected TenantMemberEntity() {}

    public TenantMemberEntity(UUID tenantId, UUID userId, TenantRole role) {
        this.tenantId = tenantId;
        this.userId = userId;
        this.role = role;
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

    public UUID getTenantId() {
        return tenantId;
    }

    @Override
    public UUID tenantId() {
        return tenantId;
    }

    public UUID getUserId() {
        return userId;
    }

    public TenantRole getRole() {
        return role;
    }

    public void setRole(TenantRole role) {
        this.role = role;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
