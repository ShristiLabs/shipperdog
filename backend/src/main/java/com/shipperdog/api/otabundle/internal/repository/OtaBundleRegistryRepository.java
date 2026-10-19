package com.shipperdog.api.otabundle.internal.repository;

import com.shipperdog.api.otabundle.internal.entity.OtaBundleEntity;
import com.shipperdog.api.tenant.CrossTenantByDesign;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The sanctioned cross-scope point probes over {@code ota_bundles}
 * (S16-07, issue #169): deliberately cross-scope, explicitly marked
 * {@link CrossTenantByDesign} per ADR-0002 — the {@code
 * OtaRolloutRegistryRepository} pattern (S16-06, issue #168). The
 * device-check route's bundle resolutions run on a device surface
 * (the {@code sdc1.} filter's request) whose thread binds no operator
 * scope — by construction not the bundles' owning dimension's — so
 * the seam-served repository cannot answer and must not be asked to.
 *
 * <p>The probes are consumed ONLY through the check resolution
 * service, which answers manifest facts — never a row to a caller.
 * The caller's own guard is the {@code sdc1.} principal itself: the
 * token's claims fixed the organization and application before any of
 * these reads ran, and every probe here is application-addressed, so
 * a foreign application's bundles are unreachable through this path.
 * Reads and writes of bundle rows go through {@code
 * OtaBundleScopedRepository}, the tenant-isolation seam; an
 * architecture test pairs this marker with the seam rule.
 */
@CrossTenantByDesign
public interface OtaBundleRegistryRepository extends JpaRepository<OtaBundleEntity, UUID> {

    /**
     * The application's RELEASED bundles — the live linkage a manifest
     * can serve (V55's status vocabulary: RELEASED is the S16-05
     * publish crossing). Ordered deterministically (id ascending).
     */
    List<OtaBundleEntity> findByApplicationIdAndStatusOrderByIdAsc(UUID applicationId, OtaBundleEntity.Status status);

    /**
     * Every bundle row the application authored under one version
     * label, whatever its status — the check's content-hash comparison
     * resolves the device's REPORTED version through these (a revoked
     * or re-published label still names the bytes the device runs).
     */
    List<OtaBundleEntity> findByApplicationIdAndBundleVersionOrderByIdAsc(UUID applicationId, String bundleVersion);

    /**
     * The release's one RELEASED bundle (the S16-05 exactly-one rule):
     * the manifest's binary. Empty when the release carries no live
     * bundle linkage.
     */
    Optional<OtaBundleEntity> findByReleaseIdAndStatus(UUID releaseId, OtaBundleEntity.Status status);

    /**
     * One bundle by id, proven to belong to the named application,
     * whatever its status (the S16-11 alert rule's scope probe): the
     * rule-create surface resolves its optional bundle scope before
     * any operator scope is bound -- the create's probe-then-scope
     * order -- so the registry's application-addressed derived-query
     * surface is the sanctioned read (the seam's id-based reads
     * refuse without a bound context, by construction). Application-
     * addressed exactly like the check probes beside it: a foreign
     * application's bundles are unreachable through this path.
     */
    Optional<OtaBundleEntity> findByIdAndApplicationId(UUID id, UUID applicationId);
}
