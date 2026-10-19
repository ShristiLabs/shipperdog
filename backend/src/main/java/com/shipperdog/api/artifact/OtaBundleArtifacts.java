package com.shipperdog.api.artifact;

import java.time.Instant;
import java.util.UUID;

/**
 * The artifact module's public port for OTA bundle binaries (S16-03,
 * issue #165, ADR-0010 D5): a bundle's bytes are an ordinary artifact
 * riding the existing {@code ArtifactStore} (ADR-0003) — no new
 * storage path — but they are NOT an installable platform package, so
 * they must not pass through the .ipa/.apk/.aab extension gate, the
 * IOS/ANDROID platform coherence rule, or the extraction/validation
 * pipeline those gates feed. This port is the bundle-shaped subset of
 * the upload machinery: the same resumable-session grant, the same
 * storage-key layout, the same finalize hash-back and replay
 * semantics, with the package-specific guards replaced by the
 * caller's own (the otabundle module validates the bundle surface;
 * this module still refuses archived applications and immutable
 * versions through the standing guards).
 *
 * <p>The artifact row a finalized session registers lands as
 * {@code READY}: its bytes are stored and checksummed at finalize, and
 * no structural package verdict exists for a platform-agnostic bundle
 * (the pipeline's FORMAT stage would fail it by construction). The
 * row's {@code platform} column carries the caller-derived enum value
 * the closed V1 vocabulary forces — inert metadata for OTA flows,
 * which resolve binaries through {@code ota_bundles.artifact_id}, never
 * through the platform slots.
 *
 * <p>The {@code claimsDocument} is the caller's opaque JSON captured
 * at session creation and replayed at finalize (the same
 * capture-point discipline the resumable artifact path applies to
 * build provenance): the otabundle module stores the bundle's
 * authoring claims there so the finalize that lands hours later needs
 * nothing re-asserted by the client.
 */
public interface OtaBundleArtifacts {

    /**
     * Grants one direct-to-storage upload session for a bundle binary:
     * allocates the artifact id and the PRD 24 storage key, mints the
     * resumable session URL, and records the session row with the
     * caller's claims document.
     *
     * @param actorId the authoring user
     * @param versionId the bundle's dedicated versions row (ADR-0010 D2)
     * @param fileName the bare bundle file name (any extension; generic
     *     bundles are platform-agnostic archives)
     * @param platform the closed-vocabulary storage-row value ("IOS" or
     *     "ANDROID"), derived by the caller
     * @param sizeHint the optional byte-count ceiling check
     * @param claimsDocument the caller's opaque JSON replayed at finalize
     */
    SessionGrant createBundleSession(
            UUID actorId, UUID versionId, String fileName, String platform, Long sizeHint, String claimsDocument);

    /**
     * Finalizes the bundle session: hashes the stored object back
     * through the store, registers the artifact row as {@code READY}
     * (no extraction or validation is scheduled — a bundle has no
     * package structure to parse), deletes the session, and answers
     * the checksum plus the claims captured at creation. Replay-safe:
     * a finalize against an already-registered artifact answers the
     * registered state, {@code created = false}.
     */
    BundleRegistration finalizeBundleSession(UUID actorId, UUID artifactId, UUID versionId);

    /**
     * Mints the bundle binary's download URL (S16-07, issue #169,
     * ADR-0010 D5): the SAME issuance machinery a tester download
     * rides — the short-lived signed URL (the emulator token the
     * {@code DownloadProxyController} serves, or the store's V4
     * signature in production), the download counter, the per-artifact
     * download count, and the {@code DOWNLOAD_ISSUED} audit line. The
     * authorization is the CALLER's: the device check's own resolution
     * chain (eligibility, rollout standing, bucketing) is the engine
     * ALLOW here, so this mint records rather than decides — the
     * tester-side engine decision, channel routing, and tester bus
     * event deliberately do not run.
     *
     * @param scopeActorId the acting record of the issuance (the
     *                     {@code sdc1.} token's registry-row id —
     *                     the identity the device surface
     *                     authenticated; names the audit line)
     * @param artifactId   the bundle's binary (the bundle id IS the
     *                     artifact id, D5)
     * @param versionId    the bundle's dedicated versions row (D2)
     * @return the URL (opaque, server-owned TTL) and its expiry
     */
    BundleDownloadUrl issueBundleDownloadUrl(UUID scopeActorId, UUID artifactId, UUID versionId);

    /** One minted bundle URL: the opaque address and the instant it stops answering. */
    record BundleDownloadUrl(String url, Instant expiresAt) {}

    /** The granted session: everything the client streams bytes with. */
    record SessionGrant(UUID sessionId, UUID artifactId, UUID versionId, String uploadUrl, Instant expiresAt) {}

    /**
     * The finalized bundle binary: the content address the OTA
     * manifest carries, the claims replayed from creation, and whether
     * this call registered the artifact or replayed an existing one.
     */
    record BundleRegistration(
            boolean created,
            UUID artifactId,
            UUID versionId,
            String fileName,
            String sha256Hex,
            long sizeBytes,
            String claimsDocument) {}
}
