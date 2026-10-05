package com.shipperdog.api.application.internal.entity;

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
 * Maps the {@code applications} table (V1 baseline). Tenant-owned
 * applications carry {@code tenantId}; organization-owned applications
 * leave it null — the ownership discriminator arrives with S3-02.
 * Tenant-scoped data (issue #10): the seam filters by the request's
 * tenant context, so an organization-owned row (null tenant) can never
 * match a context and is invisible to tenant-scoped listings by
 * construction. Application creation and metadata management arrive
 * with E3 (S3-01); this mapping exists for the tenant-owned read
 * surface (issue #11, FR-002 "assign applications" read side).
 */
@Entity
@Table(name = "applications")
public class ApplicationEntity implements TenantScope {

    /** Lifecycle vocabulary of the V1 baseline {@code application_status}. */
    public enum Status {
        ACTIVE,
        DISABLED,
        ARCHIVED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID organizationId;

    private UUID tenantId;

    private String name;

    private String bundleId;

    private String packageName;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private Status status;

    private Instant createdAt;

    private Instant updatedAt;

    protected ApplicationEntity() {}

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

    public String getBundleId() {
        return bundleId;
    }

    public String getPackageName() {
        return packageName;
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
}
