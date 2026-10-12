package com.shipperdog.api.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The audit store's org-scoped export feed (issue #71, S14-05, PRD
 * 27/38): the read seam the SIEM bucket exporter consumes -- bounded
 * pages of one organization's immutable audit rows in deterministic
 * occurrence order, advancing a keyset watermark. The port exists so
 * the delivery side never touches the {@code audit} module's
 * internals (ArchUnit) and the store keeps owning its reads (the
 * module-owns-the-store doctrine; the query API is the other face of
 * this same table).
 *
 * <p>Rows arrive exactly as the trail persisted them -- the
 * publisher binding already enriched and redacted every event before
 * the insert (docs/audit.md), so the feed performs no second scrub
 * and can never widen what the trail holds.
 */
public interface AuditEventFeed {

    /**
     * One bounded page of the organization's audit rows strictly
     * after the keyset cursor, oldest first, deterministically
     * ordered by {@code (occurredAt, id)} -- the tiebreak makes the
     * watermark safe when many events share one instant (a
     * timestamp-only cursor would skip the tail of such a burst).
     *
     * @param organizationId the owning organization
     * @param afterOccurredAt the cursor's occurrence instant, or null
     *                        to page from the beginning (the
     *                        full-backfill cursor)
     * @param afterId the cursor's row id (the tiebreak member), or
     *                null with a null instant
     * @param limit the page ceiling (1..1000; the batcher's bound)
     * @return the page, oldest first; empty when the cursor has
     *         reached the trail's end
     */
    List<AuditFeedRow> pageAfter(UUID organizationId, Instant afterOccurredAt, UUID afterId, int limit);

    /**
     * One immutable row of the feed: the trail's own facts under the
     * row's stable id. Mirrors {@link AuditEvent} plus that id -- the
     * bucket envelope's {@code event_id} (the dedup key of the
     * exported batch stream).
     */
    record AuditFeedRow(
            UUID id,
            AuditEventType eventType,
            Instant occurredAt,
            UUID userId,
            UUID orgId,
            UUID tenantId,
            boolean success,
            String detailJson,
            String ipAddress,
            UUID applicationId,
            UUID versionId,
            UUID artifactId,
            String beforeJson,
            String afterJson,
            String reason,
            String correlationId) {}
}
