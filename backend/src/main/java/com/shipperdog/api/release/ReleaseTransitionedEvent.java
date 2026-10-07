package com.shipperdog.api.release;

import java.time.Instant;
import java.util.UUID;

/**
 * One committed release transition, published in-process (issue #56,
 * S9-02, PRD 17): the event hook every later consumer of the state
 * machine subscribes to. S12-01's bus is the first planned listener —
 * it relays these onto the messaging backbone when that epic lands —
 * and E10's distribution execution and S9-05's dashboard compose on
 * the same facts without touching the release module again.
 *
 * <p>Carried facts are the transition's complete self-contained record:
 * the release's uuid and quotable ref, the application and version it
 * distributes, the owning-dimension snapshot (organization always,
 * tenant for a tenant-owned application — the same snapshot the row
 * carries, so a listener never re-reads the row to route), the source
 * and target states of PRD 17's vocabulary, the reason when one rode
 * the transition, the actor, and the occurrence time. Status values are
 * the stable string vocabulary (never the internal enum): a listener
 * must not depend on the module's internals (ArchUnit enforces the
 * same boundary on code; events carry the same discipline).
 *
 * <p>Published inside the committing transaction through Spring's
 * {@code ApplicationEventPublisher}: in-process listeners see the
 * transition with the row that produced it. A listener that must not
 * block the transition should observe asynchronously; the bus story
 * owns that policy when it registers.
 */
public record ReleaseTransitionedEvent(
        UUID releaseId,
        String releaseRef,
        UUID applicationId,
        UUID versionId,
        UUID organizationId,
        UUID tenantId,
        String fromStatus,
        String toStatus,
        String reason,
        UUID actorId,
        Instant occurredAt) {}
