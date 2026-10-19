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
 * Maps the {@code release_targets} table (V1 baseline + V24's real ring
 * reference): one target tenant of one release, at the ring the release
 * targets it through (PRD 13's shape — ring lives on the target; every
 * target starts at the release's starting ring, E10's promotion stories
 * move them). The legacy V1 {@code ring_code} column is retained by the
 * migration but unmapped: the uuid reference is the authority.
 *
 * <p>A plain child under ADR-0002's eighth amendment: every row hangs off
 * exactly one seam-served release and no route ever addresses a target
 * row by itself, so it carries no snapshot columns and joins no seam
 * repository — every data path resolves the release through the scoped
 * parent first, and the tenant filter of the release listing reaches
 * these rows only as a subquery composed with (never instead of) the
 * seam's forced scope predicate.
 */
@Entity
@Table(name = "release_targets")
@IdClass(ReleaseTargetId.class)
public class ReleaseTargetEntity {

    @Id
    private UUID releaseId;

    @Id
    private UUID tenantId;

    private UUID ringId;

    /**
     * ADR-0010 D7 (S16-05): this target's override of the bundle's
     * {@code forced} default. NULL — the default — means the bundle's
     * own value applies at manifest serve time; a stored FALSE is a
     * real override, distinct from no override. Set only on OTA-channel
     * releases (the create surface refuses the override elsewhere).
     */
    private Boolean forced;

    /**
     * ADR-0010 D7 (S16-05): this target's override of the bundle's
     * {@code min_app_version} default, same NULL-means-default
     * discipline as {@link #forced}.
     */
    private String minAppVersion;

    private Instant createdAt;

    private Instant updatedAt;

    protected ReleaseTargetEntity() {}

    /** One target of a new release (S9-01): the tenant, at the starting ring. */
    public ReleaseTargetEntity(UUID releaseId, UUID tenantId, UUID ringId) {
        this.releaseId = releaseId;
        this.tenantId = tenantId;
        this.ringId = ringId;
    }

    /**
     * One target of a new OTA-channel release (S16-05, D7): the tenant,
     * at the starting ring, carrying the target's overrides of the
     * bundle's per-bundle defaults (either may be null — the default).
     */
    public ReleaseTargetEntity(UUID releaseId, UUID tenantId, UUID ringId, Boolean forced, String minAppVersion) {
        this.releaseId = releaseId;
        this.tenantId = tenantId;
        this.ringId = ringId;
        this.forced = forced;
        this.minAppVersion = minAppVersion;
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

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getRingId() {
        return ringId;
    }

    /** The D7 override of the bundle's forced default; null means the default applies. */
    public Boolean getForced() {
        return forced;
    }

    /** The D7 override of the bundle's min_app_version default; null means the default applies. */
    public String getMinAppVersion() {
        return minAppVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
