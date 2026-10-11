package com.shipperdog.api.distribution.internal.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps the {@code staged_rollout_cohorts} table (V48, issue #66,
 * S13-06, AC1): the deterministic cohort resolution — one row per
 * (stage, tenant) SELECTED, membership IS the row. The selection's
 * seeded rank (the tenant's 0-based position in the release's hash
 * order) is carried beside the membership so the resolution is not
 * just inspectable but reproducible: the documented algorithm's own
 * ordering, stored beside its output (the story's technical note —
 * cohort selection needs stability across retries; re-planning is
 * explicit, a new authoring).
 */
@Entity
@Table(name = "staged_rollout_cohorts")
public class StagedRolloutCohortEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID stageId;

    private UUID tenantId;

    /** The tenant's 0-based position in the release's seeded hash order. */
    private int rank;

    private Instant createdAt;

    private Instant updatedAt;

    protected StagedRolloutCohortEntity() {}

    /** One membership row: the stage's cohort holds the tenant, at the order's rank. */
    public StagedRolloutCohortEntity(UUID stageId, UUID tenantId, int rank) {
        this.stageId = stageId;
        this.tenantId = tenantId;
        this.rank = rank;
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

    public UUID getStageId() {
        return stageId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public int getRank() {
        return rank;
    }
}
