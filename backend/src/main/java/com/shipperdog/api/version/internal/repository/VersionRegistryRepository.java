package com.shipperdog.api.version.internal.repository;

import com.shipperdog.api.tenant.CrossTenantByDesign;
import com.shipperdog.api.version.VersionAccess;
import com.shipperdog.api.version.VersionAccess.VersionCatalogEntry;
import com.shipperdog.api.version.VersionAccess.VersionOwnership;
import com.shipperdog.api.version.VersionAccess.VersionRecord;
import com.shipperdog.api.version.internal.entity.VersionEntity;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The scope-transition guard over {@code versions} (issue #17):
 * deliberately cross-scope, explicitly marked
 * {@link CrossTenantByDesign} per ADR-0002 (third amendment pattern).
 *
 * <p>Exactly one sanctioned operation spans scopes, declared here,
 * review-critical and in one place: the ownership-scope transition of
 * an application (issue #14, S3-02) moves the application's rows
 * between owning dimensions by definition — no single scoped context
 * can own both sides of the write, so the seam's write guard must
 * refuse it everywhere. The single declared, application-parameterized
 * update below is the one sanctioned write, called only from the
 * application module's audited transition (through {@link VersionAccess})
 * and from the version service's create-vs-transition race repair,
 * both after their checks, inside the caller's transaction. It moves
 * every version of the application in lockstep with the application
 * row, so a version can never strand in a scope its application has
 * left.
 *
 * <p>Like every {@link CrossTenantByDesign} repository, this interface
 * uses only its own declared operations — the inherited
 * {@code JpaRepository} surface stays seam-enforced for
 * {@code TenantScope} entities and would refuse without a bound
 * context. Beyond the sanctioned transition update, this repository
 * must never serve endpoint data paths: reads and writes of version
 * rows go through {@link VersionScopedRepository}, the tenant-isolation
 * seam. An architecture test pairs this marker with the seam rule.
 */
@CrossTenantByDesign
public interface VersionRegistryRepository extends JpaRepository<VersionEntity, UUID>, VersionAccess {

    /**
     * Registry lookup by id for ownership resolution (issue #20): a
     * declared query, deliberately not the inherited (seam-enforced)
     * {@code findById} — the artifact module's body-addressed routes
     * and the RBAC evaluator's {@code version} targets resolve the
     * owning dimension before any scope is bound, the same
     * authorization-data character as the application registry's
     * {@code findApplicationById}. Never an endpoint data path: the
     * seam-served read follows once the scope binds.
     */
    Optional<VersionEntity> findVersionById(UUID id);

    /**
     * The sanctioned ownership carry-along (issue #14 transition +
     * issue #17): sets the owning tenant of every version of the
     * application, or nulls it when the application becomes
     * organization-shared. Returns the rows carried along.
     */
    @Override
    @Modifying
    @Query("update VersionEntity v set v.tenantId = :tenantId, v.updatedAt = :updatedAt"
            + " where v.applicationId = :applicationId")
    int reassignOwnership(
            @Param("applicationId") UUID applicationId,
            @Param("tenantId") UUID tenantId,
            @Param("updatedAt") Instant updatedAt);

    /** Ownership resolution for the evaluator and artifact scope binding; tuples only. */
    @Override
    default Optional<VersionOwnership> ownershipOf(UUID versionId) {
        return findVersionById(versionId)
                .map(version -> new VersionOwnership(
                        version.getId(),
                        version.getApplicationId(),
                        version.getOrganizationId(),
                        version.getTenantId()));
    }

    /** Registry lookup by application (issue #20): ids only, never rows. */
    @Override
    default List<UUID> versionIdsOfApplication(UUID applicationId) {
        return findIdsByApplicationId(applicationId);
    }

    /** The version-record name for the validation tier (issue #24): the name only, never the row. */
    @Override
    default Optional<VersionRecord> recordOf(UUID versionId) {
        return findVersionById(versionId).map(version -> new VersionRecord(version.getId(), version.getVersionName()));
    }

    /**
     * The version's resolved decision inputs (issue #30, S7-01): one
     * point lookup for the E7 chain's version layer — ownership plus
     * lifecycle. Authorization data, the same character as
     * {@link #ownershipOf}; never an endpoint data path.
     */
    @Override
    default Optional<VersionState> stateOf(UUID versionId) {
        return findVersionById(versionId)
                .map(version -> new VersionState(
                        version.getId(),
                        version.getApplicationId(),
                        version.getOrganizationId(),
                        version.getTenantId(),
                        version.getStatus().name()));
    }

    /** The version ids of one application, for cross-version artifact reads. */
    @Query("select v.id from VersionEntity v where v.applicationId = :applicationId")
    List<UUID> findIdsByApplicationId(@Param("applicationId") UUID applicationId);

    /**
     * The TAG-grant decision probe (issue #31, S7-02): whether the
     * addressed version carries the tag — one composite-key descent
     * over the tag junction.
     */
    @Override
    @Query("select count(t) > 0 from VersionTagEntity t where t.versionId = :versionId and t.tag = :tag")
    boolean versionHasTag(@Param("versionId") UUID versionId, @Param("tag") String tag);

    /**
     * The TAG-grant set resolution (issue #31, S7-02): the
     * application's versions currently carrying the tag — one
     * idx_version_tags_tag descent plus the application filter.
     */
    @Override
    default List<UUID> versionIdsOfApplicationWithTag(UUID applicationId, String tag) {
        return findVersionIdsByTag(tag).stream()
                .filter(id -> belongsToApplication(id, applicationId))
                .toList();
    }

    /** The versions carrying a tag, served by idx_version_tags_tag. */
    @Query("select t.versionId from VersionTagEntity t where t.tag = :tag")
    List<UUID> findVersionIdsByTag(@Param("tag") String tag);

    /** Whether the version belongs to the application (one registry lookup). */
    private boolean belongsToApplication(UUID versionId, UUID applicationId) {
        return findVersionById(versionId)
                .map(version -> applicationId.equals(version.getApplicationId()))
                .orElse(false);
    }

    /**
     * The LATEST_N-grant decision probe (issue #31, S7-02): the
     * version is within the N newest of its application's working
     * set (DRAFT and the M2-reserved ACTIVE) by the V12 sort key.
     * Terminal states never occupy a window slot. The sort key is
     * unique per application (uq_versions_application_version pins
     * the name it encodes), so the strict comparison is exact.
     */
    @Override
    default boolean isWithinLatestWorkingSetN(UUID versionId, int n) {
        return findVersionById(versionId)
                .map(version -> countByApplicationIdAndSortKeyGreaterThanAndStatusIn(
                                version.getApplicationId(), version.getSortKey(), VersionEntity.Status.WORKING_SET)
                        < n)
                .orElse(false);
    }

    /** The newer-working-set count behind {@link #isWithinLatestWorkingSetN}. */
    long countByApplicationIdAndSortKeyGreaterThanAndStatusIn(
            UUID applicationId, String sortKey, Collection<VersionEntity.Status> statuses);

    /**
     * The grant resolver's catalog read (issue #31, S7-02): every
     * version of the application, newest first by the sort key.
     */
    @Override
    default List<VersionCatalogEntry> catalogOfApplication(UUID applicationId) {
        return findByApplicationIdOrderBySortKeyDesc(applicationId).stream()
                .map(version -> new VersionCatalogEntry(
                        version.getId(),
                        version.getVersionName(),
                        version.getStatus().name(),
                        version.getSortKey()))
                .toList();
    }

    /** The application's versions, newest first by the normalized sort key. */
    List<VersionEntity> findByApplicationIdOrderBySortKeyDesc(UUID applicationId);
}
