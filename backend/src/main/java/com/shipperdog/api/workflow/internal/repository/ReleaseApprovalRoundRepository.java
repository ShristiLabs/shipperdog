package com.shipperdog.api.workflow.internal.repository;

import com.shipperdog.api.workflow.internal.entity.ReleaseApprovalRoundEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Storage for approval rounds (V28, issue #40, S10-01). A plain
 * {@link JpaRepository} by design — rounds are the private child store
 * of the seam-served release: every caller resolves the release
 * through the release module's scoped port first (inside the request's
 * bound scope) and then reads exactly that release's rounds, so
 * isolation is enforced at the parent and never re-derived here (the
 * V25 doctrine).
 *
 * <p>The round's status crossing lives here for the standing CAS
 * reason (the {@code ReleaseStatusHistoryRepository} doctrine): a
 * serialization point is not a data path — the compare-and-set is the
 * single authority that a round changes status only while its
 * committed status is still the source this attempt read.
 */
public interface ReleaseApprovalRoundRepository extends JpaRepository<ReleaseApprovalRoundEntity, UUID> {

    /** One release's rounds, oldest first (the audit-trail order the read view renders). */
    List<ReleaseApprovalRoundEntity> findByReleaseIdOrderByRoundNumberAsc(UUID releaseId);

    /** The release's LATEST round — the round decisions act on and the transition policy evaluates. */
    Optional<ReleaseApprovalRoundEntity> findFirstByReleaseIdOrderByRoundNumberDesc(UUID releaseId);

    /**
     * The release's advisory serialization lock (S13-05, #65 — the
     * #62/#63/#64 flake root cause's product fix): the promotion
     * consult reads the ring-marked round and then the next number —
     * two statements a concurrent consult can straddle (the loser
     * reads no ring round, the winner commits one, the loser then
     * numbers past it and opens a REDUNDANT DISTINCT round the unique
     * index cannot see). The transaction-scoped advisory lock keys on
     * the release id: the second consult WAITS inside this
     * transaction's lifetime, then reads the state that won and opens
     * nothing — the consult's reads serialize exactly like its writes
     * (the unique-index path) already did. Locks release with the
     * transaction (commit or rollback alike); nothing persists.
     */
    @Query(value = "select pg_advisory_xact_lock(hashtextextended(cast(:releaseId as text), 0))", nativeQuery = true)
    void serializeRoundOpen(@Param("releaseId") UUID releaseId);

    /**
     * The release's LATEST promotion round FOR ONE RING (V33, S10-05):
     * the round the promotion engine's approval leg resolves — by the
     * ring marker, never the global latest (a promotion round can never
     * shadow the crossing's own round in the latest-round rules, and a
     * later hop's round never answers for an earlier ring's).
     */
    Optional<ReleaseApprovalRoundEntity> findFirstByReleaseIdAndPromotionRingIdOrderByRoundNumberDesc(
            UUID releaseId, UUID promotionRingId);

    /** The round a per-step record belongs to, for the read view's assembly. */
    Optional<ReleaseApprovalRoundEntity> findByIdAndReleaseId(UUID id, UUID releaseId);

    /**
     * The round's compare-and-set (the V14 discipline): moves the
     * round to {@code to} only while its committed status is still
     * {@code from} — 1 when this call made the crossing, 0 when a
     * concurrent writer moved it first. The caller answers a 0-row
     * result by re-reading the committed round and failing with the
     * precise conflict (round statuses are PENDING to terminal, never
     * back — no retry is ever legal).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            value =
                    "update release_approval_rounds set status = cast(:to as release_approval_round_status), "
                            + "updated_at = now() where id = :roundId and status = cast(:from as release_approval_round_status)",
            nativeQuery = true)
    int transitionStatusIfCurrent(@Param("roundId") UUID roundId, @Param("from") String from, @Param("to") String to);
}
