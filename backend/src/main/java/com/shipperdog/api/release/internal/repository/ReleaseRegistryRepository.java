package com.shipperdog.api.release.internal.repository;

import com.shipperdog.api.release.internal.entity.ReleaseEntity;
import com.shipperdog.api.release.internal.entity.ReleaseEntity.Status;
import com.shipperdog.api.tenant.CrossTenantByDesign;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The rolled-back download cutoff's point probe over {@code releases}
 * (issue #56, S9-02, AC4): deliberately cross-scope, explicitly marked
 * {@link CrossTenantByDesign} per ADR-0002 (the registry-repository
 * pattern the version and application modules established).
 *
 * <p>Exactly one sanctioned question spans scopes, declared here,
 * review-critical and in one place: whether the addressed version
 * carries a release in the cutoff state. The authorization engine's
 * distribution layer asks it through a policy contributor while
 * deciding a TESTER's download — inside the tester's bound scope, which
 * is by construction not the release's owning scope (a shared
 * application's release rows serve the organization's scope while the
 * downloading tester sits in a target tenant's), so the seam-served
 * repository cannot answer it and must not be asked to. The question is
 * authorization data — a boolean over the version grain, never a row to
 * a caller, never an endpoint data path: reads and writes of release
 * rows go through {@code ReleaseScopedRepository}, the tenant-isolation
 * seam. An architecture test pairs this marker with the seam rule.
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
}
