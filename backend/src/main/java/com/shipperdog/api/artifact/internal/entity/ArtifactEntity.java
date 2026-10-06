package com.shipperdog.api.artifact.internal.entity;

import com.shipperdog.api.tenant.OrganizationScope;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * Maps the {@code artifacts} table (V1 baseline + the V15 ownership
 * snapshot). An artifact is a concrete immutable binary (PRD 8.5):
 * bytes in object storage under the server-derived {@code storageKey}
 * (PRD 24 layout), metadata here — never bytes in PostgreSQL (FR-007).
 *
 * <p>The row carries the same owning-dimension snapshot as its version
 * (V15, the V12 shape): {@code organizationId} always, {@code tenantId}
 * exactly when the version hangs under a tenant-owned application.
 * Artifacts join the seam — ADR-0002's ninth amendment — because they
 * break the eighth amendment's plain-child invariant twice: the
 * artifact collection route addresses them directly ({@code POST
 * /api/v1/artifacts}, FR-004), and the same-content detection spans
 * every version of the application.
 *
 * <p>INV-003 at the row level: nothing ever updates the binary fields
 * ({@code storageKey}, {@code sha256}, {@code size}) — the entity has
 * no setters at all; a re-upload is a NEW row with a NEW id and a NEW
 * key. {@code buildNumber} is null until S5-02/03 extract it (or the
 * uploader pinned it); NULLs are distinct in {@code
 * uq_artifacts_version_platform_build}, which is exactly the
 * re-upload-means-new-artifact rule, while a pinned build triple
 * lands once.
 */
@Entity
@Table(name = "artifacts")
public class ArtifactEntity implements OrganizationScope, Persistable<UUID> {

    /**
     * The id is allocated server-side BEFORE any byte moves (the
     * story's stable-path rule), so Spring Data's {@code save} would
     * MERGE an id-carrying row; {@link Persistable} tells it this row
     * is new until persisted — a fresh insert, never a merge onto
     * existing bytes (INV-003's row-level shape).
     */
    @Transient
    private boolean isNew = true;

    /**
     * Lifecycle vocabulary of the V1 baseline {@code artifact_status}
     * (PRD 18). UPLOADING is the pre-registration state of the
     * resumable path's session row; a registered artifact lands as
     * VALIDATING — its bytes are stored and checksummed, and S5-05's
     * validation pipeline owns every later transition (READY, FAILED;
     * DISTRIBUTED and ARCHIVED follow distribution). S5-01 never moves
     * an artifact past VALIDATING: no validation pipeline exists yet,
     * and claiming READY without FR-006's checks would be a lie the
     * registry refuses.
     */
    public enum Status {
        UPLOADING,
        VALIDATING,
        READY,
        DISTRIBUTED,
        ARCHIVED,
        FAILED
    }

    /**
     * Assigned at construction, never generated: the id exists BEFORE
     * the storage key and the bytes (the story's stable-path rule), so
     * the database's {@code gen_random_uuid} default never fires — an
     * explicit generator would make Hibernate treat the id-carrying
     * new row as detached.
     */
    @Id
    private UUID id;

    private UUID versionId;

    private UUID organizationId;

    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private Platform platform;

    private String fileName;

    private String storageKey;

    /** The V1 column is {@code char(64)}: the fixed-width hex digest. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "sha256", length = 64, nullable = false)
    private String sha256;

    private long size;

    private Long buildNumber;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private Status status;

    private UUID uploadedBy;

    private Instant createdAt;

    private Instant updatedAt;

    protected ArtifactEntity() {}

    /**
     * A registered artifact (issue #20): the bytes are already in
     * object storage and the checksum is already computed — both
     * BEFORE this row exists, which is why {@code sha256} is NOT NULL
     * by construction (the V1 column, the #2 expand/contract note's
     * "hash before insert").
     *
     * @param id the pre-allocated id — the one the storage key already
     *     embeds, allocated server-side before any byte moved
     * @param buildNumber the uploader's pinned build, or null until
     *     S5-02/03 extract it
     */
    public ArtifactEntity(
            UUID id,
            UUID versionId,
            UUID organizationId,
            UUID tenantId,
            Platform platform,
            String fileName,
            String storageKey,
            String sha256,
            long size,
            Long buildNumber,
            Status status,
            UUID uploadedBy) {
        this.id = id;
        this.versionId = versionId;
        this.organizationId = organizationId;
        this.tenantId = tenantId;
        this.platform = platform;
        this.fileName = fileName;
        this.storageKey = storageKey;
        this.sha256 = sha256;
        this.size = size;
        this.buildNumber = buildNumber;
        this.status = status;
        this.uploadedBy = uploadedBy;
    }

    /** The platform vocabulary (V1 {@code platform}); one value per slot. */
    public enum Platform {
        IOS,
        ANDROID
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        isNew = false;
    }

    @PostLoad
    void onLoad() {
        isNew = false;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public UUID getId() {
        return id;
    }

    public UUID getVersionId() {
        return versionId;
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

    public Platform getPlatform() {
        return platform;
    }

    public String getFileName() {
        return fileName;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getSha256() {
        return sha256;
    }

    public long getSize() {
        return size;
    }

    public Long getBuildNumber() {
        return buildNumber;
    }

    public Status getStatus() {
        return status;
    }

    public UUID getUploadedBy() {
        return uploadedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
