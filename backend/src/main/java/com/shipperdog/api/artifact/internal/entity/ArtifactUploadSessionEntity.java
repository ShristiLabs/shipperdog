package com.shipperdog.api.artifact.internal.entity;

import com.shipperdog.api.artifact.internal.entity.ArtifactEntity.Platform;
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
 * Maps the {@code artifact_upload_sessions} table (V15, issue #20
 * AC5): the short-lived direct-to-storage handoff for large binaries.
 * A session is created after the same guards the multipart path
 * enforces; the client streams bytes straight to the session URL (a
 * GCS resumable session on the pre-allocated storage key); finalize
 * hashes the stored object, registers the durable artifact row, and
 * deletes this row — the session is bookkeeping, not data.
 *
 * <p>The row carries the owning-dimension snapshot and is served by
 * the seam: ephemeral is not exempt from INV-001, and finalize's
 * replay answer (session gone, artifact present) depends on the
 * scoped read. {@code artifactId} is the pre-allocated id the storage
 * key embeds and the artifact row will take — one session, one
 * future artifact, enforced unique.
 */
@Entity
@Table(name = "artifact_upload_sessions")
public class ArtifactUploadSessionEntity implements OrganizationScope {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID organizationId;

    private UUID tenantId;

    private UUID versionId;

    private UUID artifactId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private Platform platform;

    private String fileName;

    private Long buildNumber;

    /**
     * The provenance the creating client asserted (jsonb, V17, issue
     * #23): captured at upload time — the session-creation moment of
     * the resumable surface — and replayed by finalize into the
     * registered artifact row. The frozen-key document of
     * {@code internal.provenance.Provenance}, or null.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    private String provenance;

    private String storageKey;

    private String sessionUrl;

    private Instant expiresAt;

    private UUID createdBy;

    private Instant createdAt;

    private Instant updatedAt;

    protected ArtifactUploadSessionEntity() {}

    /**
     * A fresh session (issue #20): every guard already passed, the
     * artifact id and storage key allocated, the store's session URL
     * granted — the row records the handoff and its expiry.
     */
    public ArtifactUploadSessionEntity(
            UUID organizationId,
            UUID tenantId,
            UUID versionId,
            UUID artifactId,
            Platform platform,
            String fileName,
            Long buildNumber,
            String provenanceDocument,
            String storageKey,
            String sessionUrl,
            Instant expiresAt,
            UUID createdBy) {
        this.organizationId = organizationId;
        this.tenantId = tenantId;
        this.versionId = versionId;
        this.artifactId = artifactId;
        this.platform = platform;
        this.fileName = fileName;
        this.buildNumber = buildNumber;
        this.provenance = provenanceDocument;
        this.storageKey = storageKey;
        this.sessionUrl = sessionUrl;
        this.expiresAt = expiresAt;
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

    public UUID getVersionId() {
        return versionId;
    }

    public UUID getArtifactId() {
        return artifactId;
    }

    public Platform getPlatform() {
        return platform;
    }

    public String getFileName() {
        return fileName;
    }

    public Long getBuildNumber() {
        return buildNumber;
    }

    /** The session-captured provenance document finalize replays, or null. */
    public String getProvenance() {
        return provenance;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getSessionUrl() {
        return sessionUrl;
    }

    public Instant getExpiresAt() {
        return expiresAt;
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
}
