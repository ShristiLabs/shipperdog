package com.shipperdog.api.application.internal.entity;

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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps the {@code applications} table (V1 baseline + the V9 registry
 * columns + the V10 ownership discriminator). The ownership scope
 * (issue #14, S3-02, PRD 14) is exactly one of: {@code TENANT} — the
 * application belongs to one tenant and carries its {@code tenantId} —
 * or {@code ORGANIZATION} — the application is shared at organization
 * level with a null tenant and no owner, and which tenants receive it
 * is decided by authorization (S3-03), never the other way around. The
 * pairing is pinned by {@code ck_applications_ownership_scope_owner}
 * (INV-002). The entity implements {@link OrganizationScope}: under an
 * organization-scope context the seam serves organization-owned rows of
 * that organization only, exactly as tenant-owned rows are served under
 * their tenant's context (ADR-0002 third amendment). Identifier
 * semantics (S3-01): {@code bundleId} is the iOS platform identifier
 * and {@code packageName} the Android one (PRD 13), each required
 * exactly when its platform is chosen and unique per organization (V9
 * partial unique indexes; the name guard is organization-wide since
 * V10).
 */
@Entity
@Table(name = "applications")
public class ApplicationEntity implements OrganizationScope {

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

    /**
     * The ownership vocabulary of PRD 14 (issue #14, V10 native enum
     * {@code application_ownership_scope}): TENANT rows carry exactly one
     * owning tenant, ORGANIZATION rows are shared at organization level
     * with a null tenant.
     */
    public enum OwnershipScope {
        TENANT,
        ORGANIZATION
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID organizationId;

    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private OwnershipScope ownershipScope;

    private String name;

    private String description;

    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> platforms = new ArrayList<>();

    private String bundleId;

    private String packageName;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private Status status;

    /**
     * The OTA bundle-updates opt-in (V55, S16-02, ADR-0010): false for
     * every application until its owner enables OTA; gates bundle
     * authoring and the device-check resolution. Read-only from this
     * module's perspective -- the toggle surface arrives with the OTA
     * console work (S16-15).
     */
    private boolean otaEnabled;

    private Instant createdAt;

    private Instant updatedAt;

    protected ApplicationEntity() {}

    /**
     * A new application (S3-01/S3-02): born ACTIVE with at least one
     * platform and the identifiers its platforms demand. TENANT scope
     * carries the owning tenant under its owning organization;
     * ORGANIZATION scope carries a null tenant — the database CHECK
     * (INV-002) is the pairing's final authority.
     */
    public ApplicationEntity(
            UUID organizationId,
            OwnershipScope ownershipScope,
            UUID tenantId,
            String name,
            String description,
            List<String> platforms,
            String bundleId,
            String packageName) {
        this.organizationId = organizationId;
        this.ownershipScope = ownershipScope;
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

    /** The ownership mode (PRD 14); never null once persisted. */
    public OwnershipScope getOwnershipScope() {
        return ownershipScope;
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

    /** The OTA opt-in flag (V55): false until the owner enables it. */
    public boolean isOtaEnabled() {
        return otaEnabled;
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
