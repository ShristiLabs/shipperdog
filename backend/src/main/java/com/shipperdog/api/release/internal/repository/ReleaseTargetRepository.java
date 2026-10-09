package com.shipperdog.api.release.internal.repository;

import com.shipperdog.api.release.internal.entity.ReleaseTargetEntity;
import com.shipperdog.api.release.internal.entity.ReleaseTargetId;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Storage for the release-target rows (issue #55). A plain
 * {@link JpaRepository} by design — {@code release_targets} is not a
 * tenant-scoped surface but the private child store of one: every caller
 * resolves the release through the seam first
 * ({@link ReleaseScopedRepository}, inside the request's bound scope)
 * and then asks about exactly that release's rows, so isolation is
 * enforced at the parent and never re-derived here (the eighth
 * amendment's plain-child doctrine; the entity's javadoc records the
 * same standing). Rows are immutable once written — S9-01 creates the
 * whole set atomically with its release, and no story deletes one.
 */
public interface ReleaseTargetRepository extends JpaRepository<ReleaseTargetEntity, ReleaseTargetId> {

    /** The target rows of the given releases, in bulk: the listing view's single query per page. */
    List<ReleaseTargetEntity> findByReleaseIdIn(Collection<UUID> releaseIds);

    /** The target rows of one release, tenant-ordered for the stable detail view. */
    List<ReleaseTargetEntity> findByReleaseIdOrderByTenantIdAsc(UUID releaseId);

    /**
     * Whether the release targets the tenant (S11-01, issue #46): the
     * feedback submission's release-link check — the target-set
     * membership that must hold before a submission's facts resolve
     * ({@code ReleaseAccess.submissionFactsOf}). The composite primary
     * key serves the probe.
     */
    boolean existsByReleaseIdAndTenantId(UUID releaseId, UUID tenantId);

    /**
     * The ring move's compare-and-set (issue #44, S10-05, the V14 CAS
     * discipline): moves the named tenants to {@code to} only while
     * their committed ring is still {@code from} AND the release still
     * serves live distribution ({@code ACTIVE} or {@code PROMOTED} —
     * PRD 17's promotable statuses) — the affected-rows count is the
     * guard (the promotion expects exactly the move set's size;
     * anything less means a concurrent writer moved a target first or
     * the release left the promotable statuses between the promote
     * read and this hop — an operator's rollback committing mid-hop is
     * the race the status condition closes, never a hop that lands
     * after a rollback — and the caller distinguishes the two by
     * re-reading the committed status, never a partial move). The
     * rows' {@code updated_at} moves with the hop (a bulk update
     * bypasses the entity's {@code @PreUpdate} hook).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            value = "update release_targets set ring_id = :to, updated_at = now() "
                    + "where release_id = :releaseId and ring_id = :from and tenant_id in (:tenantIds) "
                    + "and exists (select 1 from releases r where r.id = :releaseId "
                    + "and r.status in ('ACTIVE', 'PROMOTED'))",
            nativeQuery = true)
    int moveRingIfCurrent(
            @Param("releaseId") UUID releaseId,
            @Param("from") UUID from,
            @Param("to") UUID to,
            @Param("tenantIds") Collection<UUID> tenantIds);
}
