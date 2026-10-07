package com.shipperdog.api.release.internal.entity;

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
 * Maps the {@code releases} table (V1 baseline + the V24 ownership
 * snapshot, quotable number, and starting ring). A release is the
 * controlled distribution event everything in M2 orbits (PRD 8.6,
 * FR-015): one application, one version, one artifact per platform, and
 * an explicit target set — "upload and hope" made governable.
 *
 * <p>The row carries the same owning-dimension snapshot as its
 * application (the V12 versions shape): {@code organizationId} always,
 * {@code tenantId} exactly when the application is tenant-owned. The
 * entity implements {@link OrganizationScope} and is served by the seam
 * because releases are addressed by their own top-level routes
 * ({@code POST/GET /api/v1/releases}) — the ninth amendment's rule for
 * addressable children — so neither seam mode can reach the other's
 * rows, and the write guard is the final authority that a new row
 * belongs to the request's bound scope.
 *
 * <p>The lifecycle is V1's {@code release_status} vocabulary (PRD 17);
 * S9-01 writes exactly one value, DRAFT — the state machine and its
 * transitions are S9-02, and this module never moves a release out of
 * DRAFT. The human-quotable id (AC5) is {@code R-}<i>release_number</i>
 * (one global sequence); the API keeps uuid addressing, the number is
 * the vocabulary people quote.
 */
@Entity
@Table(name = "releases")
public class ReleaseEntity implements OrganizationScope {

    /**
     * The V1 {@code release_status} vocabulary (PRD 17's state machine).
     * Only DRAFT is written by S9-01; the rest are read fidelity for the
     * status filter and for rows S9-02 will move.
     */
    public enum Status {
        DRAFT,
        VALIDATING,
        READY,
        APPROVAL_PENDING,
        APPROVED,
        DEPLOYING,
        ACTIVE,
        PROMOTED,
        ROLLED_BACK,
        COMPLETED,
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID applicationId;

    private UUID versionId;

    private UUID organizationId;

    private UUID tenantId;

    private UUID startingRingId;

    /**
     * The environment the release targets (V27, S9-04, issue #58 AC4):
     * optional — the release surface predates environments and creation
     * accepts one "when environment specified". When present, the
     * release's targets must sit inside the environment's tenant
     * assignments, and the S9-03 manifest freezes the environment's
     * CODE and RESOLVED configuration at APPROVED.
     */
    private UUID environmentId;

    private Long releaseNumber;

    private UUID createdBy;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private Status status;

    private Instant createdAt;

    private Instant updatedAt;

    protected ReleaseEntity() {}

    /**
     * A new release (S9-01; the environment dimension since S9-04): born
     * DRAFT under the application's owning-dimension snapshot, starting
     * at the resolved ring, optionally targeting a validated environment
     * (null when creation named none), with its quotable number
     * allocated from the V24 sequence and its creator recorded for the
     * audit trail.
     */
    public ReleaseEntity(
            UUID applicationId,
            UUID versionId,
            UUID organizationId,
            UUID tenantId,
            UUID startingRingId,
            UUID environmentId,
            long releaseNumber,
            UUID createdBy) {
        this.applicationId = applicationId;
        this.versionId = versionId;
        this.organizationId = organizationId;
        this.tenantId = tenantId;
        this.startingRingId = startingRingId;
        this.environmentId = environmentId;
        this.releaseNumber = releaseNumber;
        this.createdBy = createdBy;
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

    public UUID getStartingRingId() {
        return startingRingId;
    }

    /** The targeted environment, or null when the release names none (S9-04). */
    public UUID getEnvironmentId() {
        return environmentId;
    }

    public Long getReleaseNumber() {
        return releaseNumber;
    }

    public UUID getCreatedBy() {
        return createdBy;
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

    /** The human-quotable id (AC5): {@code R-1001}. */
    public String releaseRef() {
        return "R-" + releaseNumber;
    }
}
