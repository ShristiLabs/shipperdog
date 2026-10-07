package com.shipperdog.api.workflow.internal.entity;

import com.shipperdog.api.workflow.internal.entity.ReleaseApprovalRoundEntity.Status;
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
 * Maps the {@code release_approval_rounds} table (V28, issue #40,
 * S10-01): one approval ROUND of a release. Round 1 instantiates
 * inside the READY to APPROVAL_PENDING crossing's transaction (the
 * S9-02 event seam); the rework path after a rejection is a NEW round
 * — the number increments, the rejected round stays for the audit
 * trail (PRD P5). The {@code workflowId} freezes WHICH definition the
 * round ran: later definition changes never rewrite a round already
 * run (the round is the snapshot; the per-step records are its copy).
 *
 * <p>A pure child of the seam-served release (the V25 doctrine): no
 * owning-dimension snapshot, served by no seam repository — every
 * caller resolves the release through the release module's scoped port
 * first, so isolation is enforced at the parent and never re-derived
 * here.
 */
@Entity
@Table(name = "release_approval_rounds")
public class ReleaseApprovalRoundEntity {

    /**
     * The round vocabulary (the {@code release_approval_round_status}
     * enum, V28): PENDING while the chain runs, SATISFIED when the
     * last step approved (the crossing's key), REJECTED from the first
     * rejection on — sequential chains stop at the first no.
     */
    public enum Status {
        PENDING,
        SATISFIED,
        REJECTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID releaseId;

    private Integer roundNumber;

    private UUID workflowId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private Status status;

    private UUID createdBy;

    private Instant createdAt;

    private Instant updatedAt;

    protected ReleaseApprovalRoundEntity() {}

    /**
     * One round as instantiated (S10-01): the release, the number
     * (1 from the crossing, N+1 from the restart), the definition
     * frozen, and the actor whose act created it.
     */
    public ReleaseApprovalRoundEntity(UUID releaseId, int roundNumber, UUID workflowId, UUID createdBy) {
        this.releaseId = releaseId;
        this.roundNumber = roundNumber;
        this.workflowId = workflowId;
        this.createdBy = createdBy;
        this.status = Status.PENDING;
    }

    /** The round's crossing to a terminal status (SATISFIED/REJECTED); the compare-and-set caller owns the guard. */
    public void setStatus(Status status) {
        this.status = status;
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

    public UUID getReleaseId() {
        return releaseId;
    }

    public Integer getRoundNumber() {
        return roundNumber;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public Status getStatus() {
        return status;
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
}
