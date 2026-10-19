package com.shipperdog.api.otabundle;

import java.time.Instant;
import java.util.UUID;

/**
 * One committed OTA rollout control act, published in-process (S16-06,
 * issue #168, ADR-0010 D6/D10): the commencement, every manual
 * crossing (halt, pause, resume), every stage advancement, and the
 * completion -- one event per act that changed the plan, in the same
 * transaction that committed the plan row, the timeline row, and the
 * audit entry. The S12-01 outbox recorder maps it to {@code
 * OTA_ROLLOUT_TRANSITIONED} on the bus (ADR-0007), the {@code
 * ReleaseTransitionedEvent} doctrine: one name for the machine's
 * crossings, the {@code action} in the payload carrying which.
 *
 * <p>The {@code ArtifactValidatedEvent} discipline: self-contained
 * facts, published inside the terminal write's transaction through
 * Spring's {@code ApplicationEventPublisher}.
 *
 * @param planId        the plan the act crossed
 * @param releaseId     the release whose rollout the plan is
 * @param applicationId the release's application
 * @param versionId     the release's version (denormalized at attachment)
 * @param organizationId the owning organization (the plan's own grain)
 * @param tenantId      the release's owning tenant when it has one (fan-out routing's grain)
 * @param action        the timeline vocabulary: COMMENCED, HALTED, PAUSED, RESUMED, ADVANCED, COMPLETED
 * @param fromStatus    the plan's status before the act
 * @param toStatus      the plan's status after the act
 * @param stagePosition the stage the act served, when one did
 * @param detail        the machine-readable detail (a halt's reason, a stage's basis points)
 * @param actorId       the operator (null only for a platform-driven act; every act here is an operator's)
 * @param occurredAt    the act's moment
 */
public record OtaRolloutTransitionedEvent(
        UUID planId,
        UUID releaseId,
        UUID applicationId,
        UUID versionId,
        UUID organizationId,
        UUID tenantId,
        String action,
        String fromStatus,
        String toStatus,
        Integer stagePosition,
        String detail,
        UUID actorId,
        Instant occurredAt) {}
