package com.shipperdog.api.release.internal.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps the {@code release_artifacts} table (V24): the per-platform
 * artifact selection of a release — the creation API's artifact slots
 * (FR-015; PRD 8.6's "iOS 421, Android 842"). Exactly one artifact per
 * platform per release ({@code uq_release_artifacts_release_platform});
 * every artifact must be READY and belong to the release's version
 * (validated at creation; S5-05's vocabulary).
 *
 * <p>A plain child under the eighth amendment: every row hangs off
 * exactly one seam-served release and is never addressed by its own
 * route — no snapshot, no seam repository, parent-first data paths.
 */
@Entity
@Table(name = "release_artifacts")
@IdClass(ReleaseArtifactId.class)
public class ReleaseArtifactEntity {

    @Id
    private UUID releaseId;

    @Id
    private UUID artifactId;

    private String platform;

    private Instant createdAt;

    private Instant updatedAt;

    protected ReleaseArtifactEntity() {}

    /** One artifact slot of a new release (S9-01): the artifact, at its platform. */
    public ReleaseArtifactEntity(UUID releaseId, UUID artifactId, String platform) {
        this.releaseId = releaseId;
        this.artifactId = artifactId;
        this.platform = platform;
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

    public UUID getReleaseId() {
        return releaseId;
    }

    public UUID getArtifactId() {
        return artifactId;
    }

    public String getPlatform() {
        return platform;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
