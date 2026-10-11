package com.shipperdog.api.distribution.internal.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps the {@code staged_rollout_events} table (V48, issue #66,
 * S13-06, AC3/AC4): the staged rollout's append-only timeline — every
 * authoring, commencement, checkpoint stage approval, stage execution,
 * policy or dispatch-failure refusal, channel arm application, the
 * plan's completion, and the orchestrated rollback, each with its
 * actor and moment. The dashboard renders this beside the audit trail
 * (the audit carries the same facts; this table is the timeline's own
 * first-class read, the {@code channel_rollout_steps} precedent).
 */
@Entity
@Table(name = "staged_rollout_events")
public class StagedRolloutEventEntity {

    /** The timeline's action vocabulary. */
    public enum Action {
        PLANNED,
        STARTED,
        STAGE_APPROVED,
        STAGE_ADVANCED,
        STAGE_BLOCKED,
        CHANNEL_APPLIED,
        COMPLETED,
        ROLLED_BACK
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID planId;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private Action action;

    /** The stage the action serves, when one does. */
    private Integer stagePosition;

    /** The channel an arm applied to, when one did. */
    private String channel;

    /** The machine-readable outcome/detail; never secret material. */
    private String detail;

    private UUID actorId;

    private Instant occurredAt;

    protected StagedRolloutEventEntity() {}

    /** One timeline row: the action, its scope, its actor, its moment. */
    public StagedRolloutEventEntity(
            UUID planId,
            Action action,
            Integer stagePosition,
            String channel,
            String detail,
            UUID actorId,
            Instant at) {
        this.planId = planId;
        this.action = action;
        this.stagePosition = stagePosition;
        this.channel = channel;
        this.detail = detail == null || detail.isBlank() ? null : detail;
        this.actorId = actorId;
        this.occurredAt = at;
    }

    @PrePersist
    void onCreate() {
        // occurredAt is the caller's own clock fact (the shared Clock),
        // never the persistence moment: the timeline is ordered by when
        // things happened, not by when rows landed.
    }

    public UUID getId() {
        return id;
    }

    public UUID getPlanId() {
        return planId;
    }

    public Action getAction() {
        return action;
    }

    public Integer getStagePosition() {
        return stagePosition;
    }

    public String getChannel() {
        return channel;
    }

    public String getDetail() {
        return detail;
    }

    public UUID getActorId() {
        return actorId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
