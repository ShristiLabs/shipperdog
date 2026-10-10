package com.shipperdog.api.artifact;

import java.time.Instant;
import java.util.UUID;

/**
 * One artifact's validation pipeline settled, published in-process
 * (issue #51, S12-01, PRD 51's ARTIFACT_VALIDATED and FR-023's
 * "Artifact failed"): the terminal write every later consumer of the
 * validation stream subscribes to -- S12-01's outbox recorder maps
 * {@code PASSED} to ARTIFACT_VALIDATED and {@code FAILED} to
 * ARTIFACT_FAILED on the bus; {@code BLOCKED} (the queue-refusal
 * skip, an operational state rather than a domain transition) is
 * deliberately not a bus event.
 *
 * <p>The {@code ReleaseTransitionedEvent} discipline: self-contained
 * facts, published inside the terminal write's transaction through
 * Spring's {@code ApplicationEventPublisher}.
 *
 * @param artifactId     the validated artifact
 * @param versionId      the version the artifact belongs to
 * @param applicationId  the owning application
 * @param organizationId the owning organization
 * @param tenantId       the owning tenant when the application is tenant-owned
 * @param platform       the artifact's platform (IOS or ANDROID)
 * @param outcome        PASSED, FAILED, or BLOCKED (the stable vocabulary)
 * @param failureCode    the first failing stage's code when the outcome is FAILED
 * @param uploaderId     the user whose upload is being validated
 * @param occurredAt     the terminal write's moment
 */
public record ArtifactValidatedEvent(
        UUID artifactId,
        UUID versionId,
        UUID applicationId,
        UUID organizationId,
        UUID tenantId,
        String platform,
        String outcome,
        String failureCode,
        UUID uploaderId,
        Instant occurredAt) {}
