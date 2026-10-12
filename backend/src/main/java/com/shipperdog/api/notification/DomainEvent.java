package com.shipperdog.api.notification;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One durable domain event on the bus (issue #51, S12-01, PRD 51):
 * the self-contained record every wired transition contributes to the
 * outbox and every consumer (the notification dispatcher, webhook
 * receivers, later CI and SIEM consumers) receives. Carries the
 * PRD 51 attribute set: the frozen {@link DomainEventName} vocabulary,
 * the entity context columns (organization always; tenant /
 * application / version / artifact / release when the transition
 * carries them), the originating request's correlation id for
 * end-to-end tracing, the event-specific payload, and the occurrence
 * time.
 *
 * <p>The payload discipline is the typed events' own (the {@code
 * ReleaseTransitionedEvent} posture): identifiers and stable string
 * vocabularies only -- never prose, never descriptions, never PII a
 * listener does not already hold the authority to read. Consumers
 * that need more read the owning module's surface under their own
 * authority.
 *
 * <p>{@code eventId} is the outbox row's id -- the at-least-once
 * idempotency key (PRD 51 note): the relay may publish an event more
 * than once after a crash between publish and mark; consumers
 * deduplicate on this id (the delivery ledger's unique key does it
 * for the notification service itself).
 *
 * @param eventId        the outbox row id; the consumer idempotency key; never null
 * @param name           the frozen public event name; never null
 * @param organizationId the owning organization; never null (routing grain)
 * @param tenantId       the owning tenant when the transition carries one
 * @param applicationId  the affected application when the transition carries one
 * @param versionId      the affected version when the transition carries one
 * @param artifactId     the affected artifact when the transition carries one
 * @param releaseId      the affected release when the transition carries one
 * @param correlationId  the originating request's correlation id; null off-request
 * @param payload        the event-specific facts (identifiers and stable vocabularies)
 * @param occurredAt     when the transition happened; never null
 */
public record DomainEvent(
        UUID eventId,
        DomainEventName name,
        UUID organizationId,
        UUID tenantId,
        UUID applicationId,
        UUID versionId,
        UUID artifactId,
        UUID releaseId,
        String correlationId,
        Map<String, Object> payload,
        Instant occurredAt) {

    public DomainEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        // Null-tolerant copy since S14-05 (issue #71): the SIEM
        // envelope's frozen schema carries nullable members (the
        // absent entity references render as JSON null), and
        // Map.copyOf rejects null values. The copy stays immutable
        // and insertion-ordered; consumers that need a fixed
        // serialization order build their own canonical map (the
        // webhook body and the export envelope both do).
        payload = payload == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(payload));
    }
}
