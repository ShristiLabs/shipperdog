package com.shipperdog.api.version.internal.repository;

import com.shipperdog.api.version.internal.entity.VersionTagEntity;
import com.shipperdog.api.version.internal.entity.VersionTagId;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Storage for the per-version tag join rows (issue #18, S4-02, FR-009).
 * A plain {@link JpaRepository} by design — {@code version_tags} is not
 * a tenant-scoped surface but the private child store of one: every
 * caller resolves the version through the seam first
 * ({@link VersionScopedRepository}, inside the request's bound scope)
 * and then asks about exactly that version's rows, so isolation is
 * enforced at the parent and never re-derived here (V13 header; the
 * entity's javadoc records the same standing).
 *
 * <p>The declared operations are the whole tag mutation vocabulary of
 * S4-02 — the replace-set serialization lock, an insert-if-absent, and
 * a targeted delete — because replace-set semantics make them composable
 * into any transition without an update path: rows are immutable once
 * written.
 */
public interface VersionTagRepository extends JpaRepository<VersionTagEntity, VersionTagId> {

    /**
     * The tag rows of the given versions, in bulk: the listing view's
     * single query per page (no per-version fan-out) and the replace
     * path's before/after reads.
     */
    List<VersionTagEntity> findByVersionIdIn(Collection<UUID> versionIds);

    /**
     * Removes exactly the given tags of one version, leaving the rest
     * untouched — the replace path's removal arm. Deleting a tag that a
     * concurrent writer already removed is a no-op (0 rows), never a
     * failure.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from VersionTagEntity t where t.versionId = :versionId and t.tag in :tags")
    int removeTags(@Param("versionId") UUID versionId, @Param("tags") Collection<String> tags);

    /**
     * Claims one tag slot atomically: 1 when this call created the row,
     * 0 when the version already carries the tag. The ON CONFLICT DO
     * NOTHING form (the {@code IdempotencyKeyRepository} claim
     * precedent) keeps each individual claim race-free — no exception,
     * no rollback-only transaction, no window between check and insert;
     * the composite primary key is the conflict target and therefore
     * the duplicate authority.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            value = "insert into version_tags (version_id, tag) values (:versionId, :tag) "
                    + "on conflict (version_id, tag) do nothing",
            nativeQuery = true)
    int insertTagIfAbsent(@Param("versionId") UUID versionId, @Param("tag") String tag);

    /**
     * The replace-set serialization point (issue #18): takes the
     * transaction-scoped advisory lock on the version, ordering
     * concurrent replace-set transactions on the same version
     * strictly. The second writer blocks here until the first commits,
     * then reconciles against the committed baseline — last writer's
     * set wins exactly. Without it the two would read the same stale
     * {@code before} under READ COMMITTED and the insert-if-absent
     * claims would merge their sets into a union neither client
     * requested: the claim prevents duplicate rows, not lost-update
     * reconciliation.
     *
     * <p>The lock key is the version id hashed into the advisory
     * bigint space ({@code hashtextextended}); a collision with another
     * advisory key would only over-order two unrelated mutations
     * harmlessly. Being transaction-scoped, it releases automatically
     * at commit or rollback — no unlock path to forget. It lives on
     * this repository, the tags path's own store, because the tenant
     * seam forbids declared methods on scoped repositories and the
     * {@code VersionRegistryRepository} is contract-bound off endpoint
     * data paths.
     */
    @Query(value = "select pg_advisory_xact_lock(hashtextextended(cast(:versionId as text), 0))", nativeQuery = true)
    void lockVersionTagMutations(@Param("versionId") UUID versionId);
}
