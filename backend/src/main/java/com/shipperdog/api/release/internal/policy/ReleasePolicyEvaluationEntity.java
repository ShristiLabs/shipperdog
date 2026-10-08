package com.shipperdog.api.release.internal.policy;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps the {@code release_policy_evaluations} table (V34, issue #45,
 * S10-06 AC5): one row per promote attempt's policy evaluation — the
 * attempt's verdict and the winning rule (the first BLOCK when one
 * fired; null otherwise), with the actor and the moment. The per-rule
 * outcomes ride the child rows ({@code ReleasePolicyEvaluationRuleEntity}).
 *
 * <p>Append-only history (PRD P5): rows are never updated or deleted.
 * Only real attempts write here ({@code trigger} PROMOTE); the precheck
 * read and the dry-run surface answer the same evaluation WITHOUT
 * side effects (AC3).
 */
@Entity
@Table(name = "release_policy_evaluations")
public class ReleasePolicyEvaluationEntity {

    /** What asked for the evaluation: the promote act (the reads never log). */
    public enum Trigger {
        PROMOTE
    }

    /** The attempt's standing: no refusal, blocked by a rule, or held for acknowledge. */
    public enum Verdict {
        ALLOWED,
        BLOCKED,
        HELD
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID releaseId;

    private UUID organizationId;

    /** The ring the evaluated hop would have entered. */
    private String ring;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private Trigger trigger;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private Verdict verdict;

    /** The winning (first-firing) BLOCK rule's id when the verdict is BLOCKED; else null. */
    private UUID winningRuleId;

    private UUID actorId;

    private Instant occurredAt;

    private Instant createdAt;

    protected ReleasePolicyEvaluationEntity() {}

    /** One attempt's evaluation record (AC5): the ring, the verdict, the winning rule, the actor, the moment. */
    public ReleasePolicyEvaluationEntity(
            UUID releaseId,
            UUID organizationId,
            String ring,
            Verdict verdict,
            UUID winningRuleId,
            UUID actorId,
            Instant occurredAt) {
        this.releaseId = releaseId;
        this.organizationId = organizationId;
        this.ring = ring;
        this.trigger = Trigger.PROMOTE;
        this.verdict = verdict;
        this.winningRuleId = winningRuleId;
        this.actorId = actorId;
        this.occurredAt = occurredAt;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getReleaseId() {
        return releaseId;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getRing() {
        return ring;
    }

    public Trigger getTrigger() {
        return trigger;
    }

    public Verdict getVerdict() {
        return verdict;
    }

    public UUID getWinningRuleId() {
        return winningRuleId;
    }

    public UUID getActorId() {
        return actorId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
