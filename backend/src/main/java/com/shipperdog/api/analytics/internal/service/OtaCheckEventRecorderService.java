package com.shipperdog.api.analytics.internal.service;

import com.shipperdog.api.analytics.OtaCheckEventRecorder;
import com.shipperdog.api.analytics.internal.entity.AnalyticsEventEntity;
import com.shipperdog.api.analytics.internal.repository.AnalyticsEventRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@link OtaCheckEventRecorder} seam's implementation (S16-07,
 * issue #169, D9): the server-side {@code OTA_CHECK} row, inserted on
 * the store this module owns — the plain (non-seam) repository the
 * ingest itself writes through, the entity's own cross-tenant-by-
 * design doctrine. No audit line rides this write (the check surface
 * answers a device, not a console persona — the meters and the event
 * row ARE the record), and the write never gates the check's answer:
 * the caller composes it beside the manifest mints in one transaction.
 */
@Service
public class OtaCheckEventRecorderService implements OtaCheckEventRecorder {

    private final AnalyticsEventRepository events;
    private final Clock clock;

    OtaCheckEventRecorderService(AnalyticsEventRepository events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void recordCheck(OtaCheckEvent event) {
        Instant now = Instant.now(clock);
        events.saveAndFlush(new AnalyticsEventEntity(
                event.organizationId(),
                event.tenantId(),
                event.applicationId(),
                event.versionId(),
                event.releaseId(),
                AnalyticsEventEntity.Type.OTA_CHECK,
                event.outcome(),
                event.bundleId(),
                event.occurredAt(),
                now));
    }
}
