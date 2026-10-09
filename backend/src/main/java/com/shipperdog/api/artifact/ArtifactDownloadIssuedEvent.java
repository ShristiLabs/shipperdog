package com.shipperdog.api.artifact;

import java.time.Instant;
import java.util.UUID;

/**
 * One artifact download URL issued to a caller the authorization
 * engine allowed, published in-process (issue #51, S12-01, PRD 51's
 * TESTER_DOWNLOADED): the platform-side download fact -- the issuance
 * beside the DOWNLOAD_ISSUED audit line (S8-04) -- which S12-01's
 * outbox recorder makes a durable bus event. The §51 chain's
 * TESTER_NOTIFIED and TESTER_INSTALLED siblings stay deferred until
 * distribution execution and the SDK stream land.
 *
 * <p>The {@code ReleaseTransitionedEvent} discipline: self-contained
 * facts, identifiers only (the signed URL itself is recipient-private
 * and deliberately absent), published inside the issuing transaction.
 *
 * @param artifactId     the artifact whose download was issued
 * @param versionId      the version the artifact belongs to
 * @param applicationId  the owning application
 * @param organizationId the owning organization
 * @param tenantId       the owning tenant when the application is tenant-owned
 * @param platform       the artifact's platform (IOS or ANDROID)
 * @param actorId        the allowed caller (the tester's linked user on the portal path)
 * @param occurredAt     the issuance moment
 */
public record ArtifactDownloadIssuedEvent(
        UUID artifactId,
        UUID versionId,
        UUID applicationId,
        UUID organizationId,
        UUID tenantId,
        String platform,
        UUID actorId,
        Instant occurredAt) {}
