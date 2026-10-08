package com.shipperdog.api.release.internal.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps the {@code release_promotions} table (V33, issue #44, S10-05,
 * FR-018, PRD 18/33): the append-only ring-hop timeline. One row per
 * promote ATTEMPT — a COMPLETED hop records the move (from ring, to
 * ring, the tenants moved); a BLOCKED refusal records the complete
 * blocker list the 409 answered, evidence links included (PRD 33's
 * "a failed gate blocks promotion with recorded evidence"). Rows are
 * never updated or deleted (PRD P5); the promotions route serves the
 * timeline the release dashboard renders.
 *
 * <p>INV-008 by construction: the row carries a ring move and nothing
 * else. No artifact, manifest, or configuration column exists here,
 * and the engine that writes these rows writes no other table of the
 * frozen release facts — the integration suite pins artifact
 * SHA-256s and the manifest digest unchanged across every hop.
 *
 * <p>A pure child of the seam-served release (the V25 doctrine, the
 * {@code ReleaseStatusHistoryEntity} pattern): no owning-dimension
 * snapshot and no seam repository — every caller resolves the release
 * through the release module's scoped port first, so isolation is
 * enforced at the parent and never re-derived here.
 */
@Entity
@Table(name = "release_promotions")
public class ReleasePromotionEntity {

    /** The attempt vocabulary (V33's database check): the hop happened, or it was refused with recorded evidence. */
    public enum Status {
        COMPLETED,
        BLOCKED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID releaseId;

    /** The ring the moved targets sat on (as stored on the target rows at attempt time). */
    private UUID fromRingId;

    /** The ring the hop entered; NULL on a pre-ring refusal (there was no target ring to name). */
    private UUID toRingId;

    /** The tenant ids the attempt moved (or attempted to move): the selective-promotion record. */
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> tenants;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private Status status;

    /**
     * The complete blocker list of a BLOCKED attempt, keyed exactly as
     * the 409's {@code errors} member answered it (gate type, approval
     * leg, per-tenant eligibility, policy contributor) — the recorded
     * evidence. NULL on COMPLETED.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> blockers;

    /** The actor whose promote attempt this row records. */
    private UUID actorId;

    private Instant occurredAt;

    private Instant createdAt;

    protected ReleasePromotionEntity() {}

    /**
     * One promotion attempt record (S10-05): the hop's ends when it
     * completed ({@code toRingId} required), the refusal's blockers
     * when it was blocked ({@code toRingId} may name the ring the hop
     * would have entered, or null when no ring was reached).
     */
    public ReleasePromotionEntity(
            UUID releaseId,
            UUID fromRingId,
            UUID toRingId,
            List<UUID> tenants,
            Status status,
            Map<String, Object> blockers,
            UUID actorId,
            Instant occurredAt) {
        this.releaseId = releaseId;
        this.fromRingId = fromRingId;
        this.toRingId = toRingId;
        this.tenants =
                tenants == null ? null : tenants.stream().map(UUID::toString).toList();
        this.status = status;
        this.blockers = blockers;
        this.actorId = actorId;
        this.occurredAt = occurredAt;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getReleaseId() {
        return releaseId;
    }

    public UUID getFromRingId() {
        return fromRingId;
    }

    public UUID getToRingId() {
        return toRingId;
    }

    public List<String> getTenants() {
        return tenants;
    }

    public Status getStatus() {
        return status;
    }

    public Map<String, Object> getBlockers() {
        return blockers;
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
