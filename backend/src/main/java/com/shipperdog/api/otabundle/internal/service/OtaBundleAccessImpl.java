package com.shipperdog.api.otabundle.internal.service;

import com.shipperdog.api.otabundle.OtaBundleAccess;
import com.shipperdog.api.otabundle.internal.entity.OtaBundleEntity;
import com.shipperdog.api.otabundle.internal.repository.OtaBundleRegistryRepository;
import com.shipperdog.api.otabundle.internal.repository.OtaBundleScopedRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@link OtaBundleAccess} seam's implementation (S16-05, issue
 * #167): every read composes through the tenant-scoped seam as one
 * {@code Specification} (the module's standing — derived queries on
 * seam repositories are rejected by the architecture tests), and the
 * two linkage writes are load-check-save inside the CALLER's
 * transaction ({@link Propagation#MANDATORY} below is deliberate: the
 * release create and its rollback crossing own the bundle crossing
 * atomically with their own rows — an unowned write is a wiring bug to
 * refuse, not a request to serve).
 *
 * <p><strong>Concurrency, honestly</strong>: the state check is
 * in-transaction Java over the seam's read, not a database CAS — the
 * realistic races are already walled off by the schema (V55's {@code
 * uq_ota_bundles_version} makes a second release of the same bundle
 * impossible: it would need the bundle's own versions row, and V1's
 * {@code uq_releases_application_version} refuses that release; V59's
 * {@code uq_ota_bundles_release} makes a second bundle on one release
 * impossible at the floor). The one window left — a revoke landing
 * between the release's validation read and this write — resolves
 * last-writer-wins on the bundle row; the losing side's row cannot
 * strand a release pointing at a nonexistent link because both writes
 * are single-row status crossings, and the check surface (S16-07)
 * re-resolves status at serve time.
 */
@Service
public class OtaBundleAccessImpl implements OtaBundleAccess {

    private final OtaBundleScopedRepository bundles;

    private final OtaBundleRegistryRepository registry;

    OtaBundleAccessImpl(OtaBundleScopedRepository bundles, OtaBundleRegistryRepository registry) {
        this.bundles = bundles;
        this.registry = registry;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BundleFact> bundleOfArtifact(UUID applicationId, UUID artifactId) {
        if (applicationId == null || artifactId == null) {
            return Optional.empty();
        }
        return bundles.findOneScoped(ofApplication(applicationId)
                        .and((root, query, builder) -> builder.equal(root.get("artifactId"), artifactId)))
                .map(OtaBundleAccessImpl::factOf);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BundleFact> bundleOf(UUID bundleId) {
        if (bundleId == null) {
            return Optional.empty();
        }
        // Registry-probed, not seam-served: the telemetry ingest's
        // reporter tenant is not the bundle's owning scope (the
        // telemetryFactsOf precedent), so the seam's bound filter
        // would conceal every legitimate read on this path.
        return registry.findById(bundleId).map(OtaBundleAccessImpl::factOf);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BundleFact> releasedBundleOfRelease(UUID releaseId) {
        if (releaseId == null) {
            return Optional.empty();
        }
        return bundles.findOneScoped((root, query, builder) -> builder.equal(root.get("releaseId"), releaseId))
                .filter(bundle -> bundle.getStatus() == OtaBundleEntity.Status.RELEASED)
                .map(OtaBundleAccessImpl::factOf);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean attachToRelease(UUID bundleId, UUID releaseId) {
        OtaBundleEntity bundle = bundles.findScoped(bundleId).orElse(null);
        if (bundle == null || bundle.getStatus() != OtaBundleEntity.Status.SIGNED || bundle.getReleaseId() != null) {
            return false;
        }
        bundle.markReleased(releaseId);
        bundles.saveScoped(bundle);
        bundles.flush();
        return true;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean detachFromRelease(UUID bundleId, UUID releaseId) {
        OtaBundleEntity bundle = bundles.findScoped(bundleId).orElse(null);
        if (bundle == null || bundle.getStatus() != OtaBundleEntity.Status.RELEASED) {
            return false;
        }
        if (!releaseId.equals(bundle.getReleaseId())) {
            return false;
        }
        bundle.unmarkReleased(releaseId);
        bundles.saveScoped(bundle);
        bundles.flush();
        return true;
    }

    private static Specification<OtaBundleEntity> ofApplication(UUID applicationId) {
        return (root, query, builder) -> builder.equal(root.get("applicationId"), applicationId);
    }

    /** The public tuple of a row: cross-module facts never leak the row. */
    private static BundleFact factOf(OtaBundleEntity bundle) {
        return new BundleFact(
                bundle.getId(),
                bundle.getApplicationId(),
                bundle.getOrganizationId(),
                bundle.getVersionId(),
                bundle.getArtifactId(),
                bundle.getReleaseId(),
                bundle.getBundleVersion(),
                bundle.getPlatformTag(),
                bundle.isForced(),
                bundle.getMinAppVersion(),
                bundle.getStatus().name());
    }
}
