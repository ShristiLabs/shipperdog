package com.shipperdog.api.version;

import java.util.UUID;

/**
 * The distribution-record seam of INV-004 (issue #19, S4-03): modules
 * that create distribution records for a version implement this port so
 * the {@link VersionDistributionGuard} can freeze the version's
 * artifact set the moment the first record lands — "once a version has
 * any distribution record (M2 releases, or M1 download grants), its
 * artifact set is frozen" (the story's AC1, technical note: a domain
 * guard, not direct table checks, so the check survives each later
 * distribution model).
 *
 * <p>The download-grant module (E7, S7-02) implements this for grants
 * on a version; the release module (M2) implements it for releases.
 * Implementations must be cheap, side-effect-free reads (the guard runs
 * inside the artifact-mutation transaction) and must answer only for
 * records that are genuinely distribution — an expired or revoked
 * authorization is still history (INV-006/INV-007: revocation never
 * deletes), and history is exactly what freezes the artifact set.
 *
 * <p>No implementation exists in the M1 kernel: E4 lands the guard
 * with the lifecycle leg alone (ARCHIVED/REVOKED status), and the
 * contributor list stays empty until the first distribution-record
 * writer lands with E7. Test fixtures register synthetic contributors
 * to prove the seam end to end.
 */
public interface VersionDistributionContributor {

    /**
     * Whether the version carries any distribution record. True makes
     * the version's artifact set frozen (INV-004) regardless of its
     * lifecycle status.
     *
     * @param versionId the version, already resolved through the
     *     caller's bound scope by the guard that asks
     */
    boolean hasDistributionRecords(UUID versionId);
}
