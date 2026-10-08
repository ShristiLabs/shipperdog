package com.shipperdog.api.release;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One committed ring promotion, published in-process (issue #44,
 * S10-05, PRD 51's RELEASE_PROMOTED): the event hook every later
 * consumer of the promotion engine subscribes to. S12-01's bus relays
 * these onto the messaging backbone when it lands; S10-06's policy
 * engine is the intended first governed consumer (an AUTOMATIC ring's
 * scheduler watches these to drive the next hop); E13's store-channel
 * destinations compose on the same facts without touching the release
 * module again.
 *
 * <p>Carried facts are the hop's complete self-contained record: the
 * release's uuid and quotable ref, the application and version it
 * distributes, the owning-dimension snapshot (the same snapshot the
 * row carries), the two rings' effective CODES (the stable string
 * vocabulary, never ids a listener must re-resolve and never the
 * internal rows), the tenants the hop moved (selective promotion's
 * record), whether the hop carried the release onto PRD 17's PROMOTED
 * crossing (the final ring, all targets), the actor, and the
 * occurrence time.
 *
 * <p>Published inside the committing transaction through Spring's
 * {@code ApplicationEventPublisher}, the {@link
 * ReleaseTransitionedEvent} discipline: in-process listeners see the
 * hop with the rows that produced it. The ACTIVE to PROMOTED crossing
 * a final-ring hop performs ALSO publishes the transition event (the
 * state machine's own ceremony, unchanged); this event is the ring
 * move's own, one per committed hop.
 */
public record ReleasePromotedEvent(
        UUID releaseId,
        String releaseRef,
        UUID applicationId,
        UUID versionId,
        UUID organizationId,
        UUID tenantId,
        String fromRing,
        String toRing,
        List<UUID> tenants,
        boolean crossedToPromoted,
        UUID actorId,
        Instant occurredAt) {}
