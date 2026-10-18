package com.shipperdog.api.otabundle;

import java.time.Instant;
import java.util.UUID;

/**
 * One OTA bundle finalized and signed, published in-process (S16-03,
 * issue #165, ADR-0010 D8): the terminal write of bundle authoring —
 * the moment the manifest landed, the sha256 was signed under the
 * app's active Ed25519 key, and the row crossed to SIGNED. The S12-01
 * outbox recorder maps it to {@code OTA_BUNDLE_SIGNED} on the bus
 * (ADR-0007), so a bundle's readiness reaches webhook and CI
 * consumers durably, in the same transaction that committed the row.
 *
 * <p>The {@code ArtifactValidatedEvent} discipline: self-contained
 * facts, published inside the terminal write's transaction through
 * Spring's {@code ApplicationEventPublisher}.
 *
 * @param bundleId      the bundle (equals its artifact id, D5)
 * @param versionId     the bundle's dedicated versions row (D2)
 * @param artifactId    the bundle's binary (an ordinary artifact, D5)
 * @param applicationId the owning application
 * @param organizationId the owning organization
 * @param tenantId      the owning tenant when the application is tenant-owned
 * @param bundleVersion the version label
 * @param platformTag   the open-vocabulary platform tag (V55's doctrine)
 * @param sha256        the manifest checksum that was signed
 * @param sizeBytes     the manifest byte size
 * @param keyId         the signing key generation's public identifier
 * @param signature     Base64 Ed25519 signature over the sha256 hex
 * @param actorId       the authoring user
 * @param occurredAt    the terminal write's moment
 */
public record OtaBundleSignedEvent(
        UUID bundleId,
        UUID versionId,
        UUID artifactId,
        UUID applicationId,
        UUID organizationId,
        UUID tenantId,
        String bundleVersion,
        String platformTag,
        String sha256,
        long sizeBytes,
        String keyId,
        String signature,
        UUID actorId,
        Instant occurredAt) {}
