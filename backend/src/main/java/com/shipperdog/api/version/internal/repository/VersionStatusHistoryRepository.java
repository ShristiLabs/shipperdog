package com.shipperdog.api.version.internal.repository;

import com.shipperdog.api.version.internal.entity.VersionStatusHistoryEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Storage for the per-version status timeline (issue #19, S4-03,
 * FR-008/FR-024). A plain {@link JpaRepository} by design —
 * {@code version_status_history} is not a tenant-scoped surface but the
 * private child store of one: every caller resolves the version through
 * the seam first ({@code VersionScopedRepository}, inside the request's
 * bound scope) and then reads exactly that version's rows, so isolation
 * is enforced at the parent and never re-derived here (V14 header; the
 * entity's javadoc records the same standing — the second landed child
 * of a seam-served version after {@code VersionTagRepository}).
 *
 * <p>Rows are append-only history (PRD P5): creation writes the DRAFT
 * row, archive and revoke-distribution append theirs, and nothing ever
 * updates or deletes a row — the declared operations are the whole
 * vocabulary.
 *
 * <p>The lifecycle's compare-and-set lives here for the same reason the
 * tags path's advisory lock lives on the tag repository: the seam
 * forbids declared methods on scoped repositories and the registry
 * repository is contract-bound off endpoint data paths, and a
 * serialization point is not a data path — it needs no seam standing
 * of its own (#18 precedent).
 */
public interface VersionStatusHistoryRepository extends JpaRepository<VersionStatusHistoryEntity, UUID> {

    /** One version's timeline, chronological (id ascending as the tiebreak). */
    List<VersionStatusHistoryEntity> findByVersionIdOrderByOccurredAtAscIdAsc(UUID versionId);

    /**
     * The lifecycle transition's compare-and-set (issue #19): moves the
     * version to {@code to} only while its committed status is still
     * {@code from} — 1 when this call made the transition, 0 when a
     * concurrent writer moved it first. Without it, two transitions
     * racing on one version both read the same source state under READ
     * COMMITTED and both commit: duplicate audits and timeline rows in
     * the harmless case, and in the worst case a stale archive landing
     * after a revoke — silently un-revoking a version whose
     * distribution was pulled, breaking the terminal-state invariant.
     * The row's {@code updated_at} moves with the transition (a bulk
     * update bypasses the entity's {@code @PreUpdate} hook).
     *
     * <p>The caller answers a 0-row result by re-reading the committed
     * state and either retrying a still-legal transition (ARCHIVED to
     * REVOKED is the one sanctioned crossing) or failing with the
     * precise conflict — statuses only ever move forward, so the
     * retry loop terminates.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            value = "update versions set status = cast(:to as version_status), updated_at = now() "
                    + "where id = :versionId and status = cast(:from as version_status)",
            nativeQuery = true)
    int transitionStatusIfCurrent(
            @Param("versionId") UUID versionId, @Param("from") String from, @Param("to") String to);
}
