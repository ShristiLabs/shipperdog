package com.shipperdog.api.otabundle;

import java.util.Optional;
import java.util.UUID;

/**
 * The release-side seam over authored bundles (S16-05, issue #167,
 * ADR-0010 workstream 3): the port the release module and the
 * evaluation seams resolve bundle facts through — the exactly-one-bundle
 * rule's create-time resolution, the RELEASED linkage's writes, and the
 * read the crash-free gate's bundle-scoped arithmetic needs.
 *
 * <p><strong>Bundle facts are tuples, never rows</strong> (the
 * {@code ApplicationAccess} discipline): everything this port answers
 * crosses the module boundary as the public record below, so the
 * release module can never hold a reference into this module's
 * persistence. Reads resolve inside the caller's bound scope through
 * the seam like every other bundle read; the linkage writes join the
 * caller's transaction (release creation and its rollback crossing own
 * them atomically).
 *
 * <p><strong>The eligibility composition note</strong> (the #182 review
 * carry-forward): callers resolving device-facing admission (S16-07's
 * check route) compose this port's reads with the application's own
 * standing — {@code ota_enabled} and the application status are the
 * caller's first gates, exactly as {@code requireOtaAuthorable} orders
 * them on the authoring side. This port deliberately answers only
 * bundle facts so that policy stays one composable check away.
 */
public interface OtaBundleAccess {

    /**
     * The bundle behind an artifact id, whatever its status (the
     * release create surface's resolution: an OTA-channel release's
     * one artifact slot must be a SIGNED bundle's bytes, and a store
     * release must not carry a bundle's bytes at all). Empty when the
     * artifact is no bundle's, or a foreign application's.
     */
    Optional<BundleFact> bundleOfArtifact(UUID applicationId, UUID artifactId);

    /**
     * The RELEASED bundle of a release (the crash-free gate's
     * bundle-scoped resolution and the rollback crossing's revert
     * target). Empty when the release carries no live bundle link.
     */
    Optional<BundleFact> releasedBundleOfRelease(UUID releaseId);

    /**
     * The publish crossing: SIGNED to RELEASED with the {@code
     * release_id} backfill, inside the caller's transaction. FALSE —
     * never an exception of this module's own — when the bundle is no
     * longer a SIGNED unattached bundle at write time (the lost
     * revoke/second-publish race); the caller translates the conflict
     * in its own vocabulary.
     */
    boolean attachToRelease(UUID bundleId, UUID releaseId);

    /**
     * The rollback crossing: RELEASED back to SIGNED, the live link
     * cleared — only when THIS release still owns the bundle (the
     * coherence guard; a foreign or already-returned link is a no-op
     * answered FALSE). Idempotent for the owning release's retry.
     */
    boolean detachFromRelease(UUID bundleId, UUID releaseId);

    /**
     * One bundle's cross-module facts: identity, ownership, the D2/D5
     * linkage, the manifest's platform tag, and the D7 per-bundle
     * defaults. The status is the stable string vocabulary (the events
     * discipline: cross-module facts never leak the internal enum).
     */
    record BundleFact(
            UUID id,
            UUID applicationId,
            UUID organizationId,
            UUID versionId,
            UUID artifactId,
            UUID releaseId,
            String bundleVersion,
            String platformTag,
            boolean forced,
            String minAppVersion,
            String status) {}
}
