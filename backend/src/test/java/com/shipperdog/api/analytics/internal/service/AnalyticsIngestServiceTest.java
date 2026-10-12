package com.shipperdog.api.analytics.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shipperdog.api.analytics.AnalyticsException;
import com.shipperdog.api.analytics.TelemetryTokenPrincipal;
import com.shipperdog.api.analytics.internal.repository.AnalyticsEventRepository;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.device.DeviceAccess;
import com.shipperdog.api.release.ReleaseAccess;
import com.shipperdog.api.release.ReleaseAccess.ReleaseFacts;
import com.shipperdog.api.tester.TesterAccess;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The ingest's honest-race and deleted-tester unit legs (issue #72,
 * S15-01 review round 1): a flush-time collision on the V54 dedup
 * index cannot be retried inside the aborted PostgreSQL transaction,
 * so it must surface as the 503 {@code INGEST_WRITE_CONTENTION}
 * mapping — never a re-probe (a second read would fail with "current
 * transaction is aborted" and leak a 500), and never a landed-batch
 * audit for a batch that rolled back. And a token whose tester record
 * no longer resolves may not name ANY device: the ownership narrowing
 * refuses rather than silently widening to the tenant's whole active
 * fleet.
 */
class AnalyticsIngestServiceTest {

    private final ReleaseAccess releases = mock(ReleaseAccess.class);

    private final DeviceAccess devices = mock(DeviceAccess.class);

    private final TesterAccess testers = mock(TesterAccess.class);

    private final AnalyticsEventRepository events = mock(AnalyticsEventRepository.class);

    private final AuditEventPublisher audit = mock(AuditEventPublisher.class);

    private final AnalyticsIngestService service =
            new AnalyticsIngestService(releases, devices, testers, events, audit, Clock.systemUTC());

    @Test
    void aFlushTimeIndexRaceMapsToContentionNeverAnInTransactionReProbe() {
        ReleaseWorld world = stubRelease();
        // The concurrent batch landed our (device, event id) between
        // the probe and the flush: the unique index fires on flush.
        when(events.landedClientIds(any(UUID.class), any())).thenReturn(List.of());
        when(events.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new DataIntegrityViolationException("uq_analytics_events_client_id"))
                .when(events)
                .flush();

        AnalyticsException thrown = catchThrowableOfType(
                () -> service.ingest(UUID.randomUUID(), world.tenantId(), List.of(world.event())),
                AnalyticsException.class);

        assertThat(thrown).isNotNull();
        assertThat(thrown.code()).isEqualTo(AnalyticsException.Code.INGEST_WRITE_CONTENTION);
        assertThat(thrown.retryAfterSeconds()).isPositive();
        // The honest mapping's proof: the dedup probe ran EXACTLY once
        // — there is no second read inside the aborted transaction.
        verify(events, times(1)).landedClientIds(any(UUID.class), any());
        // A batch that rolled back is never audited as ingested.
        verify(audit, never()).publish(any());
    }

    @Test
    void aCleanLandingProbesOnceFlushesOnceAndAuditsTheSurvivors() {
        ReleaseWorld world = stubRelease();
        when(events.landedClientIds(any(UUID.class), any())).thenReturn(List.of());
        when(events.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AnalyticsIngestService.IngestReport report =
                service.ingest(UUID.randomUUID(), world.tenantId(), List.of(world.event()));

        assertThat(report.ingested()).isEqualTo(1);
        assertThat(report.deduplicated()).isZero();
        verify(events, times(1)).landedClientIds(any(UUID.class), any());
        verify(events, times(1)).flush();
        verify(audit, times(1)).publish(any());
    }

    @Test
    void aTokenWhoseTesterRecordDoesNotResolveMayNotNameAnyDevice() {
        ReleaseWorld world = stubRelease();
        // An ACTIVE device of the tenant owned by SOMEONE: under the
        // old guard this is exactly the row an unresolvable-token
        // batch could have landed against.
        UUID deviceOwner = UUID.randomUUID();
        when(devices.factsOf(any(UUID.class)))
                .thenReturn(Optional.of(new DeviceAccess.DeviceFacts(
                        UUID.randomUUID(), deviceOwner, world.tenantId(), "ACTIVE", "ANDROID", "Pixel 9", "16")));
        when(testers.testerIdOfUser(any(UUID.class))).thenReturn(Optional.empty());
        TelemetryTokenPrincipal telemetry = new TelemetryTokenPrincipal(
                world.tenantId(),
                UUID.randomUUID(),
                world.releaseId(),
                null,
                Instant.now().plusSeconds(60));

        AnalyticsException thrown = catchThrowableOfType(
                () -> service.ingest(
                        UUID.randomUUID(), world.tenantId(), telemetry, List.of(world.event(UUID.randomUUID()))),
                AnalyticsException.class);

        assertThat(thrown).isNotNull();
        assertThat(thrown.code()).isEqualTo(AnalyticsException.Code.INVALID_REQUEST);
        // The refusal is field-addressed and concealed: device_id
        // names the offender with the same answer a foreign device
        // gets — no oracle distinguishing the deleted tester.
        @SuppressWarnings("unchecked")
        java.util.Map<String, String> errors =
                (java.util.Map<String, String>) thrown.problem().getProperties().get("errors");
        assertThat(errors).containsEntry("device_id", "the device did not resolve for this tenant");
        verify(events, never()).saveAll(any());
    }

    /** One tenant, one resolvable release, and the event shapes that name it. */
    private ReleaseWorld stubRelease() {
        UUID tenantId = UUID.randomUUID();
        UUID releaseId = UUID.randomUUID();
        when(releases.telemetryFactsOf(releaseId, tenantId))
                .thenReturn(Optional.of(new ReleaseFacts(
                        releaseId,
                        "rel-" + releaseId,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        tenantId,
                        UUID.randomUUID(),
                        "READY",
                        null,
                        "qa",
                        null)));
        return new ReleaseWorld(tenantId, releaseId);
    }

    private record ReleaseWorld(UUID tenantId, UUID releaseId) {

        private AnalyticsIngestService.EventCommand event() {
            return event(null);
        }

        private AnalyticsIngestService.EventCommand event(UUID deviceId) {
            return new AnalyticsIngestService.EventCommand(
                    "INSTALL",
                    "SUCCEEDED",
                    releaseId,
                    deviceId,
                    UUID.randomUUID().toString(),
                    Instant.now(),
                    null,
                    null,
                    null);
        }
    }
}
