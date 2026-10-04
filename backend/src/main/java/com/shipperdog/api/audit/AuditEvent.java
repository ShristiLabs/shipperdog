package com.shipperdog.api.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * An immutable audit record (PRD 27 shape: event, ids, timestamp, result).
 * Organization and tenant context is attached when already resolved for the
 * actor; before the organization module exists (S1-04) the fields stay
 * empty rather than carrying placeholder values.
 *
 * @param type       what happened; never null
 * @param occurredAt when it happened; never null
 * @param userId     the acting user, or null when the actor is anonymous
 * @param orgId      the resolved organization context, or null
 * @param tenantId   the resolved tenant context, or null
 * @param success    whether the event's outcome was successful
 * @param detail     machine-readable context (reason codes and similar);
 *                   never carries credentials, tokens, or raw secrets
 */
public record AuditEvent(
        AuditEventType type,
        Instant occurredAt,
        UUID userId,
        UUID orgId,
        UUID tenantId,
        boolean success,
        Map<String, String> detail) {

    public AuditEvent {
        detail = detail == null ? Map.of() : Map.copyOf(detail);
    }
}
