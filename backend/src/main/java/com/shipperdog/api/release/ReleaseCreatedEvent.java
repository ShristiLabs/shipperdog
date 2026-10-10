package com.shipperdog.api.release;

import java.time.Instant;
import java.util.UUID;

/**
 * One DRAFT release created with its full target set, published
 * in-process (issue #51, S12-01, PRD 51's RELEASE_CREATED): the
 * creation hook every later consumer of the release stream subscribes
 * to. S12-01's outbox recorder is the first (the durable bus event in
 * the same transaction); the notification subscriptions route on it
 * from there. The state machine's own crossings keep the {@link
 * ReleaseTransitionedEvent} ceremony, unchanged.
 *
 * <p>The {@code ReleaseTransitionedEvent} discipline: self-contained
 * facts -- the release's uuid and quotable ref, the application and
 * version it distributes, the owning-dimension snapshot (the same
 * snapshot the row carries), the ring it starts on -- published
 * inside the creating transaction through Spring's {@code
 * ApplicationEventPublisher}.
 *
 * @param releaseId      the created release
 * @param releaseRef     the release's quotable ref
 * @param applicationId  the application the release distributes
 * @param versionId      the version the release ships
 * @param organizationId the owning organization
 * @param tenantId       the owning tenant when the application is tenant-owned
 * @param ring           the ring the release starts on (the stable code)
 * @param actorId        the creating user
 * @param occurredAt     the creation moment
 */
public record ReleaseCreatedEvent(
        UUID releaseId,
        String releaseRef,
        UUID applicationId,
        UUID versionId,
        UUID organizationId,
        UUID tenantId,
        String ring,
        UUID actorId,
        Instant occurredAt) {}
