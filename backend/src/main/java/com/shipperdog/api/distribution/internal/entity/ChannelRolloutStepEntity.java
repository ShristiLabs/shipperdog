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
 * Maps the {@code channel_rollout_steps} table (V47, issue #65,
 * S13-05, AC3): the rollout's append-only timeline — every action on
 * the curve with its actor, its from/to percentages, and its moment.
 * The dashboard renders this table as the rollout's own timeline (the
 * audit trail carries the same facts as the platform's record; this
 * table is the timeline's first-class read, the promotion-timeline
 * precedent).
 */
@Entity
@Table(name = "channel_rollout_steps")
public class ChannelRolloutStepEntity {

    /** The timeline action's vocabulary (the ACs' own words). */
    public enum Action {
        /** The production release's landing at its configured initial percentage. */
        START,
        /** A percentage increase applied to the provider. */
        ADVANCE,
        /** The halt: distribution stops at the current percentage. */
        HALT,
        /** The resume: distribution continues from the halted percentage. */
        RESUME,
        /** The explicit re-apply of intent after drift (never silent). */
        REAPPLY
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID rolloutId;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private Action action;

    private Integer fromPercent;

    private int toPercent;

    private UUID actorId;

    private Instant occurredAt;

    protected ChannelRolloutStepEntity() {}

    /** One timeline row: the action, the curve's two ends, the actor, the moment. */
    public ChannelRolloutStepEntity(
            UUID rolloutId, Action action, Integer fromPercent, int toPercent, UUID actorId, Instant occurredAt) {
        this.rolloutId = rolloutId;
        this.action = action;
        this.fromPercent = fromPercent;
        this.toPercent = toPercent;
        this.actorId = actorId;
        this.occurredAt = occurredAt;
    }

    @PrePersist
    void onCreate() {
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getRolloutId() {
        return rolloutId;
    }

    public Action getAction() {
        return action;
    }

    public Integer getFromPercent() {
        return fromPercent;
    }

    public int getToPercent() {
        return toPercent;
    }

    public UUID getActorId() {
        return actorId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
