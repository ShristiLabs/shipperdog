package com.shipperdog.api.authorization;

import java.util.UUID;

/**
 * The distribution layer's policy seam (issue #30, S7-01, PRD 11's
 * last chain member): a download must clear every distribution policy
 * the release model attaches to the artifact. The engine asks every
 * registered contributor and denies {@code DISTRIBUTION_BLOCKED} when
 * any contributor blocks; with no contributor registered the layer
 * passes — nothing restricts the artifact.
 *
 * <p>The M1 kernel ships NO contributor: the {@code distribution}
 * table of the V1 baseline has no writer until the release epics (M2,
 * E9/E13) land, and a download grant itself (S7-02) is what the
 * version module's {@code VersionDistributionGuard} already counts as
 * a distribution record. This interface is the documented plug point
 * those epics register against — the same dependency inversion as
 * {@code VersionDistributionContributor}, so the engine never learns
 * their schemas.
 */
public interface AuthorizationDistributionPolicy {

    /**
     * Whether the distribution state blocks the download. Contributors
     * must stay read-only point lookups and never throw — an
     * unavailable source answers not-blocking (this layer restricts;
     * it does not authorize).
     *
     * @param artifactId  the artifact about to be downloaded
     * @param versionId   the version the artifact belongs to
     */
    boolean blocksDownload(UUID artifactId, UUID versionId);
}
