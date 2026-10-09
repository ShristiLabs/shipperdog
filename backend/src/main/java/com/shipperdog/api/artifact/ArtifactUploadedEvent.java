package com.shipperdog.api.artifact;

import java.time.Instant;
import java.util.UUID;

/**
 * One registered artifact, published in-process (issue #51, S12-01,
 * PRD 51's ARTIFACT_UPLOADED): the upload-completion hook every later
 * consumer of the artifact stream subscribes to -- S12-01's outbox
 * recorder is the first (it makes the transition a durable bus event
 * in the same transaction), and the notification subscriptions route
 * on it from there.
 *
 * <p>The {@code ReleaseTransitionedEvent} discipline: self-contained
 * facts, identifiers and stable vocabularies (never internal enums),
 * published inside the registering transaction through Spring's
 * {@code ApplicationEventPublisher} so in-process listeners see the
 * event with the row that produced it.
 *
 * @param artifactId     the registered artifact
 * @param versionId      the version the artifact belongs to
 * @param applicationId  the owning application
 * @param organizationId the owning organization
 * @param tenantId       the owning tenant when the application is tenant-owned
 * @param platform       the artifact's platform (IOS or ANDROID)
 * @param fileName       the uploaded file's name
 * @param size           the stored object's byte size
 * @param sha256         the server-computed content hash
 * @param actorId        the uploading user
 * @param occurredAt     the registration moment
 */
public record ArtifactUploadedEvent(
        UUID artifactId,
        UUID versionId,
        UUID applicationId,
        UUID organizationId,
        UUID tenantId,
        String platform,
        String fileName,
        long size,
        String sha256,
        UUID actorId,
        Instant occurredAt) {}
