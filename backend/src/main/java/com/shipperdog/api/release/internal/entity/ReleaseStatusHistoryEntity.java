package com.shipperdog.api.release.internal.entity;

import com.shipperdog.api.release.internal.entity.ReleaseEntity.Status;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps the {@code release_status_history} table (V25, issue #56, S9-02,
 * FR-024): one append-only row per real transition of PRD 17's state
 * machine — the release timeline the history API returns and the S9-05
 * dashboard renders. The creation writes the row every release starts
 * with ({@code from} null, {@code to} DRAFT, the creator as actor);
 * every later row carries the crossing's source and target states, the
 * actor who moved the release (the issue's actor/timestamp demand), the
 * mandatory reason of the failure and rollback transitions, and the
 * service clock's timestamp. Nothing ever updates or deletes a row
 * (PRD P5: history is permanent).
 *
 * <p>A pure child of the seam-served release (the V14
 * {@code version_status_history} doctrine, the seventh amendment
 * standing): no owning-dimension snapshot, served by no seam
 * repository — every caller resolves the release through the scoped
 * parent first, so isolation is enforced at the parent and never
 * re-derived here.
 */
@Entity
@Table(name = "release_status_history")
public class ReleaseStatusHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID releaseId;

    /** The crossing's source state; null on exactly the creation's DRAFT row. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private Status fromStatus;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private Status toStatus;

    /** The transition's reason: mandatory for failure and rollback rows, optional elsewhere. */
    private String reason;

    /** Who moved the release (the creator on the DRAFT row); a bare uuid, the audit log keeps the full who. */
    private UUID actorId;

    private Instant occurredAt;

    protected ReleaseStatusHistoryEntity() {}

    /**
     * One timeline row: the transition (or, with {@code from == null},
     * the creation's DRAFT row), its reason when one was given or
     * required, the actor, and the occurrence time (the service clock,
     * so the timeline is deterministic under test).
     */
    public ReleaseStatusHistoryEntity(
            UUID releaseId, Status fromStatus, Status toStatus, String reason, UUID actorId, Instant occurredAt) {
        this.releaseId = releaseId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.reason = reason;
        this.actorId = actorId;
        this.occurredAt = occurredAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getReleaseId() {
        return releaseId;
    }

    public Status getFromStatus() {
        return fromStatus;
    }

    public Status getToStatus() {
        return toStatus;
    }

    public String getReason() {
        return reason;
    }

    public UUID getActorId() {
        return actorId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
