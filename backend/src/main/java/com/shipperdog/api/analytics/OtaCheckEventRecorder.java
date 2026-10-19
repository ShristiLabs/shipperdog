package com.shipperdog.api.analytics;

import java.time.Instant;
import java.util.UUID;

/**
 * The analytics module's public port for the server-side {@code
 * OTA_CHECK} event (S16-07, issue #169, ADR-0010 D9): the ONE OTA event
 * the platform inserts itself — at check time, when the outcome is
 * update-available and the release (and so the bundle) is known. Every
 * other OTA event is device-reported through the {@code sdt1.}-bound
 * ingest (S16-08); a no-update check inserts NOTHING because {@code
 * analytics_events.release_id} is NOT NULL (V38) and a no-update check
 * has no release to attribute — D9's funnel split, the meters carry
 * those outcomes instead.
 *
 * <p>The port sits at the analytics module's public root (the {@code
 * TelemetryTokenCodec} placement): the writer is the otabundle
 * module's device-check resolution, and the ArchUnit module-boundary
 * rule forbids cross-module {@code .internal.} imports. Everything
 * crosses as the public record below — tuples, never rows.
 *
 * <p>Rows this port lands are plain appends: no client event id (the
 * V54 dedup key governs client-carried ids — the server's own check
 * rows are one per served manifest by construction), no device or
 * tester attribution (the checking device is a fleet identifier the
 * analytics store does not carry until S16-08's {@code ota_payload}),
 * and the bundle attribution V57's {@code
 * ck_analytics_events_ota_shape} requires.
 */
public interface OtaCheckEventRecorder {

    /**
     * Lands one server-side check event. Idempotent by construction
     * (one row per served manifest, no dedup key) and transactional in
     * the caller's composition — the recorder never gates the check's
     * answer.
     *
     * @param event the check that served a manifest
     */
    void recordCheck(OtaCheckEvent event);

    /**
     * One server-side check event's facts: the release's owning
     * dimensions, the reporting tenant the funnel attributes through
     * (the check's deterministic target choice — the same tenant the
     * minted {@code sdt1.} grant binds, so the check row and the
     * device's later lifecycle rows land in ONE funnel grain), the
     * bundle attribution, and the outcome word ({@code
     * update_available}; the open row-value doctrine of V57).
     */
    record OtaCheckEvent(
            UUID organizationId,
            UUID tenantId,
            UUID applicationId,
            UUID versionId,
            UUID releaseId,
            UUID bundleId,
            String outcome,
            Instant occurredAt) {}
}
