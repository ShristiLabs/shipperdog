package com.shipperdog.api.version.internal.entity;

import com.shipperdog.api.tenant.OrganizationScope;
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
 * Maps the {@code versions} table (V1 baseline + the V12 ownership
 * snapshot and sort key). A version is the logical layer between an
 * application and its artifacts (PRD 8.4): {@code Banking 7.12.0}
 * holding one artifact per platform, existing without being released
 * (PRD 8.6).
 *
 * <p>The row carries the same owning-dimension snapshot as its
 * application (V12): {@code organizationId} always, {@code tenantId}
 * exactly when the application is tenant-owned — null-tenant rows are
 * the organization-shared case (PRD 14). The entity therefore
 * implements {@link OrganizationScope} exactly like the application
 * entity: under a tenant-scope context the seam serves the owner
 * tenant's rows, under an organization-scope context the organization's
 * null-tenant rows, and neither mode can reach the other's (ADR-0002
 * third amendment). The snapshot is written from the application's
 * profile at creation and carried along by the audited scope
 * transition; the write guard of the seam is the final authority that a
 * new row belongs to the request's bound scope.
 *
 * <p>Ordering (issue #17 technical note): {@code versionName} is the
 * human string; {@code sortKey} is the fixed-width numeric encoding
 * (five-digit zero-padded segments joined by dots) whose lexicographic
 * order is exactly semver numeric order.
 */
@Entity
@Table(name = "versions")
public class VersionEntity implements OrganizationScope {

    /** Lifecycle vocabulary of the V1 baseline {@code version_status}. */
    public enum Status {
        DRAFT,
        ACTIVE,
        ARCHIVED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID applicationId;

    private UUID organizationId;

    private UUID tenantId;

    private String versionName;

    private String sortKey;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private Status status;

    private Instant createdAt;

    private Instant updatedAt;

    protected VersionEntity() {}

    /**
     * A new version (S4-01): born DRAFT (lifecycle transitions are
     * S4-03) under the owning-dimension snapshot of its application.
     */
    public VersionEntity(UUID applicationId, UUID organizationId, UUID tenantId, String versionName, String sortKey) {
        this.applicationId = applicationId;
        this.organizationId = organizationId;
        this.tenantId = tenantId;
        this.versionName = versionName;
        this.sortKey = sortKey;
        this.status = Status.DRAFT;
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

    public UUID getApplicationId() {
        return applicationId;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    @Override
    public UUID organizationId() {
        return organizationId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    @Override
    public UUID tenantId() {
        return tenantId;
    }

    public String getVersionName() {
        return versionName;
    }

    public String getSortKey() {
        return sortKey;
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
