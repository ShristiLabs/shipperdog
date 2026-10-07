package com.shipperdog.api.workflow;

import java.time.Instant;
import java.util.UUID;

/**
 * One approval request, published in-process (issue #40, S10-01,
 * FR-016): the event every instantiated approval step emits — the hook
 * S12-01's notification bus consumes when that epic lands (who must
 * sign, for which release, in which round), exactly the way
 * {@code ReleaseTransitionedEvent} is the state machine's hook. One
 * event per step of the round: a round of {@code [QA, Security]}
 * publishes two, in chain order, each self-contained.
 *
 * <p>Carried facts mirror the transition event's discipline: the
 * release's uuid and quotable ref, the owning-dimension snapshot (the
 * round's rows carry the same snapshot, so a listener never re-reads
 * storage to route), the round and position, the approval type (the
 * stable role vocabulary — never an internal enum), the request's
 * actor and moment. Published inside the round-instantiating
 * transaction through Spring's {@code ApplicationEventPublisher}: the
 * bus story owns the delivery policy when it registers.
 */
public record ApprovalRequestedEvent(
        UUID releaseId,
        String releaseRef,
        UUID applicationId,
        UUID organizationId,
        UUID tenantId,
        int roundNumber,
        int position,
        String approvalType,
        UUID actorId,
        Instant requestedAt) {}
