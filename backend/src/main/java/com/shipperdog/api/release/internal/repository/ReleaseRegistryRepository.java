package com.shipperdog.api.release.internal.repository;

import com.shipperdog.api.release.internal.entity.ReleaseEntity;
import com.shipperdog.api.release.internal.entity.ReleaseEntity.Status;
import com.shipperdog.api.tenant.CrossTenantByDesign;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The sanctioned cross-scope point probes over {@code releases}
 * (issue #56, S9-02; issue #46, S11-01): deliberately cross-scope,
 * explicitly marked {@link CrossTenantByDesign} per ADR-0002 (the
 * registry-repository pattern the version and application modules
 * established).
 *
 * <p>Every sanctioned question that spans scopes is declared here,
 * review-critical and in one place. The authorization engine's
 * distribution layer asks the cutoff probe while deciding a TESTER's
 * download; the feedback submission path asks the (application,
 * version) resolution while attaching a report's release context — in
 * both cases inside the caller's bound scope, which is by
 * construction not the release's owning scope (a shared
 * application's release rows serve the organization's scope while
 * the tester sits in a target tenant's), so the seam-served
 * repository cannot answer and must not be asked to. Both questions
 * are authorization data — never a row to a caller, never an
 * endpoint data path: reads and writes of release rows go through
 * {@code ReleaseScopedRepository}, the tenant-isolation seam. The
 * submission resolution's answers carry the target-set check beside
 * them ({@code ReleaseAccess.submissionFactsOf} refuses the facts
 * unless the submitter's tenant is one of the release's targets), and
 * an architecture test pairs this marker with the seam rule.
 */
@CrossTenantByDesign
public interface ReleaseRegistryRepository extends JpaRepository<ReleaseEntity, UUID> {

    /**
     * Whether the version's release is in the cutoff state (AC4): a
     * ROLLED_BACK direct-channel release denies new downloads of the
     * version's artifacts while every artifact and the version stay
     * readable — INV-006/007's posture, applied at the release grain.
     * One release exists per application and version
     * (V1's uq_releases_application_version), so the version grain is
     * exact: the probe is true for at most one release's worth of
     * cutoff.
     */
    @Query("select count(r) > 0 from ReleaseEntity r where r.versionId = :versionId and r.status = :cutoff")
    boolean existsByVersionIdAndStatus(@Param("versionId") UUID versionId, @Param("cutoff") Status cutoff);

    /**
     * The one release of the application and version (S11-01, issue
     * #46): the feedback submission's resolution probe, consumed ONLY
     * through {@code ReleaseAccess.submissionFactsOf}, which refuses
     * the answer unless the submitter's tenant is one of the release's
     * targets — the same character as the cutoff probe (a tester-side
     * decision whose bound scope is by construction not the release's
     * owning scope; the row itself never crosses scopes, only the
     * facts do, after the target-set check).
     */
    Optional<ReleaseEntity> findByApplicationIdAndVersionId(UUID applicationId, UUID versionId);

    /**
     * The id-keyed telemetry resolution probe (S11-04, issue #49): a
     * DECLARED query, exactly like the (application, version) probe —
     * the inherited {@code findById} rides the seam's scoped CRUD
     * surface and cannot answer for a reporter's bound tenant context
     * (a shared application's release rows serve the organization's
     * scope while the reporting device sits in a target tenant's).
     * Consumed ONLY through {@code ReleaseAccess.telemetryFactsOf},
     * which refuses the answer unless the reporting tenant is one of
     * the release's targets.
     */
    @Query("select r from ReleaseEntity r where r.id = :releaseId")
    Optional<ReleaseEntity> probeById(@Param("releaseId") UUID releaseId);

    /**
     * The application's LIVE releases (S16-07, issue #169): a DECLARED
     * query over the statuses the rollout attachment's own vocabulary
     * names distributing (ACTIVE or PROMOTED). Consumed ONLY through
     * {@code ReleaseAccess.otaChannelReleaseOf} — the device-check
     * surface's channel resolution, which filters the answer by the
     * effective ring and owns the organization guard (the {@code
     * distribution*Of} character: the caller binds no operator scope,
     * so the seam-served repository cannot answer and must not be
     * asked to).
     */
    @Query("select r from ReleaseEntity r where r.applicationId = :applicationId and r.status in :live "
            + "order by r.releaseNumber desc, r.id desc")
    List<ReleaseEntity> probeLiveByApplication(
            @Param("applicationId") UUID applicationId, @Param("live") Collection<Status> live);
}
