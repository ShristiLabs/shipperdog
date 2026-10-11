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
 * Maps the {@code staged_rollout_plans} table (V48, issue #66, S13-06,
 * FR-018/§37): ONE staged TENANT rollout plan per release — the intent
 * model of ShipperDog's own rings axis. The plan carries the
 * advancement mode ({@link Mode#AUTOMATIC}: the policy engine decides
 * between stages; {@link Mode#CHECKPOINT}: a Release Manager approves
 * each stage) and its own standing ({@link Status#PLANNED} at
 * authoring, {@link Status#RUNNING} from the release's ACTIVE
 * crossing, {@link Status#COMPLETE} when the final stage reached,
 * {@link Status#ROLLED_BACK} by the orchestrated rollback).
 *
 * <p>The owning dimensions (application, version) are denormalized
 * from the release's own facts at authoring: the download gate
 * resolves a plan by VERSION (the download's addressing grain) without
 * a release-module read, exactly the record-addressing doctrine the
 * distribution records established.
 */
@Entity
@Table(name = "staged_rollout_plans")
public class StagedRolloutPlanEntity {

    /** The advancement mode: who decides between stages. */
    public enum Mode {
        AUTOMATIC,
        CHECKPOINT
    }

    /** The plan's own standing. */
    public enum Status {
        PLANNED,
        RUNNING,
        COMPLETE,
        ROLLED_BACK
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID organizationId;

    private UUID releaseId;

    /** The release's application, denormalized at authoring (the audit and view grain). */
    private UUID applicationId;

    /** The release's version, denormalized at authoring (the download gate's addressing grain). */
    private UUID versionId;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private Mode mode;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private Status status;

    /** The 1-based position of the serving stage (the highest REACHED); null while PLANNED. */
    private Integer currentStage;

    /**
     * The optimistic-lock stamp (the #51 subscription precedent, the
     * V47 channel_rollouts precedent): a stale concurrent action on
     * one plan refuses with the public 409 instead of
     * last-write-winning — two concurrent advances can never skip a
     * checkpoint or double-apply a stage.
     */
    @jakarta.persistence.Version
    private long version;

    private UUID createdBy;

    private Instant createdAt;

    private Instant updatedAt;

    protected StagedRolloutPlanEntity() {}

    /** One plan born PLANNED at authoring: the release's own dimensions beside its mode. */
    public StagedRolloutPlanEntity(
            UUID releaseId, UUID organizationId, UUID applicationId, UUID versionId, Mode mode, UUID createdBy) {
        this.releaseId = releaseId;
        this.organizationId = organizationId;
        this.applicationId = applicationId;
        this.versionId = versionId;
        this.mode = mode;
        this.status = Status.PLANNED;
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

    public UUID getReleaseId() {
        return releaseId;
    }

    public UUID getApplicationId() {
        return applicationId;
    }

    public UUID getVersionId() {
        return versionId;
    }

    public Mode getMode() {
        return mode;
    }

    public Status getStatus() {
        return status;
    }

    public Integer getCurrentStage() {
        return currentStage;
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

    /** The plan's standing moves (commencement, completion, rollback). */
    public void move(Status to) {
        this.status = to;
    }

    /** The serving stage moves (commencement sets 1; each advance sets its position). */
    public void serve(int position) {
        this.currentStage = position;
    }
}
