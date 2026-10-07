package com.shipperdog.api.release.internal.repository;

import com.shipperdog.api.release.internal.entity.ReleaseStatusHistoryEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Storage for the per-release status timeline (issue #56, S9-02,
 * FR-024/PRD 17). A plain {@link JpaRepository} by design —
 * {@code release_status_history} is not a tenant-scoped surface but the
 * private child store of one: every caller resolves the release through
 * the seam first ({@code ReleaseScopedRepository}, inside the request's
 * bound scope) and then reads exactly that release's rows, so isolation
 * is enforced at the parent and never re-derived here (the V25 header;
 * the {@code version_status_history} doctrine applied verbatim).
 *
 * <p>Rows are append-only history (PRD P5): creation writes the DRAFT
 * row, every committed transition appends its own, and nothing ever
 * updates or deletes a row — the declared operations are the whole
 * vocabulary.
 *
 * <p>The lifecycle's compare-and-set lives here for the same reason the
 * version module's lives on its history repository: the seam forbids
 * declared methods on scoped repositories, and a serialization point is
 * not a data path — it needs no seam standing of its own (the #18/#19
 * precedent, now the standing CAS doctrine).
 */
public interface ReleaseStatusHistoryRepository extends JpaRepository<ReleaseStatusHistoryEntity, UUID> {

    /** One release's timeline, chronological (id ascending as the tiebreak). */
    List<ReleaseStatusHistoryEntity> findByReleaseIdOrderByOccurredAtAscIdAsc(UUID releaseId);

    /**
     * The release transition's compare-and-set (issue #56, the V14 CAS
     * discipline applied to PRD 17's machine): moves the release to
     * {@code to} only while its committed status is still {@code from}
     * — 1 when this call made the transition, 0 when a concurrent
     * writer moved it first. Without it, two transitions racing on one
     * release both read the same source state under READ COMMITTED and
     * both commit: duplicate audits and timeline rows in the harmless
     * case, and in the worst case a stale transition landing after a
     * terminal one — silently un-rolling-back a pulled release or
     * un-failing a dead one, breaking the terminal-state invariant
     * exactly the way the version CAS prevents for REVOKED. The row's
     * {@code updated_at} moves with the transition (a bulk update
     * bypasses the entity's {@code @PreUpdate} hook).
     *
     * <p>The caller answers a 0-row result by re-reading the committed
     * state and either retrying a still-legal crossing or failing with
     * the precise conflict — PRD 17's graph is forward-only with
     * terminal walls, so the retry loop terminates in at most two
     * attempts against any interleaving.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            value = "update releases set status = cast(:to as release_status), updated_at = now() "
                    + "where id = :releaseId and status = cast(:from as release_status)",
            nativeQuery = true)
    int transitionStatusIfCurrent(
            @Param("releaseId") UUID releaseId, @Param("from") String from, @Param("to") String to);
}
