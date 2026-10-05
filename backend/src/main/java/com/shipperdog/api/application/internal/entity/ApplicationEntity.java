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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps the {@code applications} table (V1 baseline + the V9 registry
 * columns: {@code description}, {@code platforms}). Tenant-owned
 * applications carry {@code tenantId}; organization-owned applications
 * leave it null — the ownership discriminator arrives with S3-02, and
 * until then the write surface (S3-01) creates tenant-owned rows only.
 * Tenant-scoped data (issue #10): the seam filters by the request's
 * tenant context, so an organization-owned row (null tenant) can never
 * match a context and is invisible to tenant-scoped access by
 * construction. Identifier semantics (S3-01): {@code bundleId} is the
 * iOS platform identifier and {@code packageName} the Android one
 * (PRD 13), each required exactly when its platform is chosen and
 * unique per organization (V9 partial unique indexes).
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

    /**
     * The platform vocabulary of the registry (S3-01, PRD 14/15: iOS and
     * Android for V1). Stored as the entity's string array and pinned at
     * the database by {@code ck_applications_platforms_vocabulary}.
     */
    public enum Platform {
        IOS,
        ANDROID
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID organizationId;

    private UUID tenantId;

    private String name;

    private String description;

    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> platforms = new ArrayList<>();

    private String bundleId;

    private String packageName;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private Status status;

    private Instant createdAt;

    private Instant updatedAt;

    protected ApplicationEntity() {}

    /**
     * A new tenant-owned application (S3-01): born ACTIVE under the
     * tenant's owning organization with at least one platform and the
     * identifiers its platforms demand.
     */
    public ApplicationEntity(
            UUID organizationId,
            UUID tenantId,
            String name,
            String description,
            List<String> platforms,
            String bundleId,
            String packageName) {
        this.organizationId = organizationId;
        this.tenantId = tenantId;
        this.name = name;
        this.description = description;
        this.platforms = new ArrayList<>(platforms);
        this.bundleId = bundleId;
        this.packageName = packageName;
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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    /** The chosen platforms as registered (first-seen order), never empty. */
    public List<String> getPlatforms() {
        return List.copyOf(platforms);
    }

    public void setPlatforms(List<String> platforms) {
        this.platforms = new ArrayList<>(platforms);
    }

    public String getBundleId() {
        return bundleId;
    }

    public void setBundleId(String bundleId) {
        this.bundleId = bundleId;
    }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
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
