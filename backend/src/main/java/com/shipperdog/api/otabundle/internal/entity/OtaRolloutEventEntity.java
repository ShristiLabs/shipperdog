package com.shipperdog.api.otabundle.internal.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of a rollout plan's append-only operator timeline (V56
 * {@code ota_rollout_events}, widened by V60 to carry the PAUSED
 * action, S16-06, ADR-0010 D6/D10): the commencement (COMMENCED),
 * every manual control crossing (HALTED, PAUSED, RESUMED), every
 * stage's advancement (ADVANCED), and the final stage's completion
 * (COMPLETED) -- the timeline beside the audit-events entry the
 * service writes for the same act, and the bus event the outbox
 * recorder makes durable in the same transaction.
 *
 * <p><strong>Append-only, guarded by the parent</strong> (the V48
 * doctrine): rows are INSERTed by the control acts and never updated
 * or deleted; like the stage rows they carry no owning-dimension
 * columns of their own and are reachable only through the plan's id
 * after the plan's own org-scoped seam resolution -- a plain
 * repository addressed by {@code planId}, never a seam repository.
 */
@Entity
@Table(name = "ota_rollout_events")
public class OtaRolloutEventEntity {

    /** The timeline's vocabulary of V56's CHECK as widened by V60. */
    public enum Action {
        COMMENCED,
        HALTED,
        PAUSED,
        RESUMED,
        ADVANCED,
        COMPLETED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID planId;

    @Enumerated(EnumType.STRING)
    private Action action;

    /** The stage the action serves, when one does. */
    private Integer stagePosition;

    /** The machine-readable outcome/detail (a refusal's reason); never secret material. */
    private String detail;

    private UUID actorId;

    private Instant occurredAt;

    protected OtaRolloutEventEntity() {}

    public OtaRolloutEventEntity(
            UUID planId, Action action, Integer stagePosition, String detail, UUID actorId, Instant occurredAt) {
        this.planId = planId;
        this.action = action;
        this.stagePosition = stagePosition;
        this.detail = detail;
        this.actorId = actorId;
        this.occurredAt = occurredAt;
    }

    @PrePersist
    void onCreate() {
        // The timeline row is born complete: no updated_at column to
        // maintain, because nothing ever updates it (append-only).
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
