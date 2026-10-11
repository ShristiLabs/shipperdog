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
 * Maps the {@code staged_rollout_stages} table (V48, issue #66,
 * S13-06, AC1): one ordered stage of a release's staged tenant
 * rollout plan — a {@link Kind#PERCENT PERCENT} of the target
 * population (the seeded selection takes the first ceil(p% * N / 100)
 * tenants of the release's hash order) or an {@link Kind#EXPLICIT
 * EXPLICIT} tenant list, with the resolved cohort's size denormalized
 * at authoring so the view is inspectable before advancement.
 *
 * <p>The stage's own standing: {@link Status#PENDING PENDING} until
 * its execution, {@link Status#REACHED REACHED} after the stage
 * executed across the channels, {@link Status#ROLLED_BACK ROLLED_BACK}
 * when the orchestrated rollback pulled a reached stage.
 */
@Entity
@Table(name = "staged_rollout_stages")
public class StagedRolloutStageEntity {

    /** The stage's shape: how its cohort was resolved. */
    public enum Kind {
        PERCENT,
        EXPLICIT
    }

    /** The stage's standing. */
    public enum Status {
        PENDING,
        REACHED,
        ROLLED_BACK
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID planId;

    /** The stage's order, 1-based: the widening order. */
    private int position;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private Kind kind;

    /** The percentage for PERCENT stages; null for EXPLICIT. */
    private Integer percent;

    /** The resolved cohort's size (denormalized at authoring). */
    private int cohortSize;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private Status status;

    /** The moment the stage was reached; null while PENDING. */
    private Instant reachedAt;

    private Instant createdAt;

    private Instant updatedAt;

    protected StagedRolloutStageEntity() {}

    /** One stage born PENDING at authoring, its cohort resolved beside it. */
    public StagedRolloutStageEntity(UUID planId, int position, Kind kind, Integer percent, int cohortSize) {
        this.planId = planId;
        this.position = position;
        this.kind = kind;
        this.percent = percent;
        this.cohortSize = cohortSize;
        this.status = Status.PENDING;
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

    public UUID getPlanId() {
        return planId;
    }

    public int getPosition() {
        return position;
    }

    public Kind getKind() {
        return kind;
    }

    public Integer getPercent() {
        return percent;
    }

    public int getCohortSize() {
        return cohortSize;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getReachedAt() {
        return reachedAt;
    }

    /** The stage executes: REACHED, its moment stamped. */
    public void reach(Instant at) {
        this.status = Status.REACHED;
        this.reachedAt = at;
    }

    /** The orchestrated rollback pulls a reached stage. */
    public void pull() {
        this.status = Status.ROLLED_BACK;
    }
}
