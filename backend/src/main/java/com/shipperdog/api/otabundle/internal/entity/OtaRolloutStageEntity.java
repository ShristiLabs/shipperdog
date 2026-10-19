package com.shipperdog.api.otabundle.internal.entity;

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

/**
 * One widening stage of an OTA rollout plan (V56
 * {@code ota_rollout_stages}, S16-06, ADR-0010 D6): a CUMULATIVE
 * basis-point threshold of the plan's device hash space -- a device is
 * served by the stage when {@code hash(device_id, plan_salt) mod
 * 10000} falls below the threshold, so stage N always contains
 * everything stage N-1 reached (the widening ladder; #164's judgment:
 * no per-stage rollback, PENDING/REACHED only -- a stop is the
 * plan-level halt, never a stage's retreat).
 *
 * <p><strong>Guarded by the parent, the V48 doctrine</strong>: like
 * V56's own mirror of V48's {@code staged_rollout_stages}, the row
 * carries no owning-dimension columns of its own -- the stage rows are
 * reachable only through the plan's id, which every caller holds only
 * after the plan's own org-scoped seam resolution has established
 * standing. The repository is therefore a plain repository addressed
 * by {@code planId}, never a seam repository (the entity is not
 * tenant-scoped data of its own; the isolation rides the parent).
 */
@Entity
@Table(name = "ota_rollout_stages")
public class OtaRolloutStageEntity {

    /** The stage's standing of V56 {@code ck_ota_rollout_stages_status}: not yet serving, or serving. */
    public enum Status {
        PENDING,
        REACHED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID planId;

    /** The stage's order, 1-based: the widening order ({@code uq_ota_rollout_stages_plan_position}). */
    private int position;

    /** The CUMULATIVE basis-point threshold (1..10000); the final stage carries 10000. */
    private int basisPoints;

    /** The operator's note for the stage (a checkpoint's rationale). */
    private String note;

    @Enumerated(EnumType.STRING)
    private Status status;

    /** The moment the stage was reached; null while PENDING. */
    private Instant reachedAt;

    private Instant createdAt;

    private Instant updatedAt;

    protected OtaRolloutStageEntity() {}

    /** One stage born at attachment: stage 1 REACHED (the commencement), every later one PENDING. */
    public OtaRolloutStageEntity(
            UUID planId, int position, int basisPoints, String note, boolean reached, Instant now) {
        this.planId = planId;
        this.position = position;
        this.basisPoints = basisPoints;
        this.note = note;
        this.status = reached ? Status.REACHED : Status.PENDING;
        this.reachedAt = reached ? now : null;
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

    public int getBasisPoints() {
        return basisPoints;
    }

    public String getNote() {
        return note;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getReachedAt() {
        return reachedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** The advancement's write: the stage crosses to REACHED at the given moment. */
    public void reach(Instant now) {
        this.status = Status.REACHED;
        this.reachedAt = now;
    }
}
