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
import com.shipperdog.api.analytics.internal.entity.AnalyticsEventEntity;
import com.shipperdog.api.analytics.internal.entity.AnalyticsEventEntity.Type;
import com.shipperdog.api.analytics.internal.repository.AnalyticsEventRepository;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.device.DeviceAccess;
import com.shipperdog.api.otabundle.OtaBundleAccess;
import com.shipperdog.api.otabundle.OtaBundleAccess.BundleFact;
import com.shipperdog.api.release.ReleaseAccess;
import com.shipperdog.api.release.ReleaseAccess.ReleaseFacts;
import com.shipperdog.api.tester.TesterAccess;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.ObjectMapper;

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
 *
 * <p>The S16-08 legs (issue #170): the OTA family's acceptance — the
 * five lifecycle types land bundle-attributed with the payload and the
 * bundle's version dimension (the check row's own funnel grain), the
 * server-authoritative {@code OTA_CHECK} refusal (D9), the per-type
 * result vocabulary, the concealed unknown-or-foreign bundle answer,
 * and the legacy family's OTA-member refusal (V57's ota_shape
 * disjunction guarded in code, before the database has to).
 */
class AnalyticsIngestServiceTest {

    private final ReleaseAccess releases = mock(ReleaseAccess.class);

    private final DeviceAccess devices = mock(DeviceAccess.class);

    private final TesterAccess testers = mock(TesterAccess.class);

    private final OtaBundleAccess bundles = mock(OtaBundleAccess.class);

    private final AnalyticsEventRepository events = mock(AnalyticsEventRepository.class);

    private final AuditEventPublisher audit = mock(AuditEventPublisher.class);

    private final AnalyticsIngestService service = new AnalyticsIngestService(
            releases,
            devices,
            testers,
            bundles,
            events,
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            audit,
            new ObjectMapper(),
            Clock.systemUTC());

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

    // -----------------------------------------------------------------------
    // The S16-08 OTA family (issue #170)
    // -----------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void otaLifecycleEventsLandBundleAttributedWithPayloadAndTheBundlesVersionDimension() {
        ReleaseWorld world = stubRelease();
        UUID bundleId = UUID.randomUUID();
        UUID bundleVersionId = UUID.randomUUID();
        stubBundle(bundleId, world.applicationId, bundleVersionId);
        when(events.landedClientIds(any(UUID.class), any())).thenReturn(List.of());
        AtomicReference<List<AnalyticsEventEntity>> landed = new AtomicReference<>();
        when(events.saveAll(any())).thenAnswer(invocation -> {
            List<AnalyticsEventEntity> copy = new ArrayList<>();
            ((Iterable<AnalyticsEventEntity>) invocation.getArgument(0)).forEach(copy::add);
            landed.set(copy);
            return copy;
        });
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("device_id", "fleet-device-1");
        payload.put("network", "wifi");

        AnalyticsIngestService.IngestReport report = service.ingest(
                UUID.randomUUID(),
                world.tenantId,
                List.of(world.otaEvent("OTA_DOWNLOAD", "SUCCEEDED", bundleId.toString(), payload)));

        assertThat(report.ingested()).isEqualTo(1);
        AnalyticsEventEntity row = landed.get().get(0);
        assertThat(row.eventType()).isEqualTo(Type.OTA_DOWNLOAD);
        assertThat(row.result()).isEqualTo("SUCCEEDED");
        assertThat(row.otaBundleId()).isEqualTo(bundleId);
        assertThat(row.otaPayload()).containsEntry("device_id", "fleet-device-1");
        // The funnel grain the server-side check row shares: the OTA
        // family's version dimension is the bundle's own.
        assertThat(row.versionId()).isEqualTo(bundleVersionId);
        assertThat(row.sessions()).isNull();
        assertThat(row.crashSessions()).isNull();
        assertThat(row.anrSessions()).isNull();
        verify(audit, times(1)).publish(any());
    }

    @Test
    void theCheckTypeStaysServerAuthoritativeOnTheIngestContract() {
        ReleaseWorld world = stubRelease();
        AnalyticsException thrown = catchThrowableOfType(
                () -> service.ingest(
                        UUID.randomUUID(),
                        world.tenantId,
                        List.of(world.otaEvent(
                                "OTA_CHECK",
                                "update_available",
                                UUID.randomUUID().toString(),
                                null))),
                AnalyticsException.class);

        assertThat(thrown).isNotNull();
        assertThat(thrown.code()).isEqualTo(AnalyticsException.Code.INVALID_REQUEST);
        // The byte-identical unknown-type refusal: no oracle teaching
        // the wire that the word exists but belongs to the server.
        assertThat(errorsOf(thrown)).containsEntry("type", "unknown type OTA_CHECK");
        verify(events, never()).saveAll(any());
    }

    @Test
    void theOtaResultVocabularyIsClosedPerType() {
        ReleaseWorld world = stubRelease();
        UUID bundleId = UUID.randomUUID();
        stubBundle(bundleId, world.applicationId, UUID.randomUUID());

        // OTA_FAILED: only the documented open row-value set.
        assertThat(errorsOf(refused(world, "OTA_FAILED", "exploded", bundleId)))
                .containsEntry("result", "exploded is not a OTA_FAILED result");
        // OTA_APPLIED / OTA_DOWNLOAD: only the closed outcome pair.
        assertThat(errorsOf(refused(world, "OTA_APPLIED", "MAYBE", bundleId)))
                .containsEntry("result", "MAYBE is not a OTA_APPLIED result");
        assertThat(errorsOf(refused(world, "OTA_DOWNLOAD", null, bundleId)))
                .containsEntry("result", "result is required for OTA_DOWNLOAD");
        // The contextless pair carries no result word at all.
        assertThat(errorsOf(refused(world, "OTA_SEEN", "SUCCEEDED", bundleId)))
                .containsEntry("result", "SUCCEEDED is not a OTA_SEEN result");
        verify(events, never()).saveAll(any());
    }

    @Test
    void anUnknownOrForeignBundleIsOneConcealedAnswerAndTheBundleIdIsRequired() {
        ReleaseWorld world = stubRelease();
        // Unknown: the seam answers empty.
        UUID unknown = UUID.randomUUID();
        when(bundles.bundleOf(unknown)).thenReturn(Optional.empty());
        assertThat(errorsOf(refused(world, "OTA_SEEN", null, unknown)))
                .containsEntry("ota_bundle_id", "the bundle did not resolve");
        // Foreign: a bundle of ANOTHER application — the same answer,
        // never an oracle distinguishing it from the unknown.
        UUID foreign = stubBundle(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        assertThat(errorsOf(refused(world, "OTA_SEEN", null, foreign)))
                .containsEntry("ota_bundle_id", "the bundle did not resolve");
        // Absent: the required-member refusal.
        assertThat(errorsOf(refused(world, "OTA_SEEN", null, null)))
                .containsEntry("ota_bundle_id", "ota_bundle_id is required for OTA_SEEN");
        // Malformed: not a UUID.
        assertThat(errorsOf(refused(world, "OTA_SEEN", null, "not-a-uuid", null)))
                .containsEntry("ota_bundle_id", "ota_bundle_id must be a UUID");
        verify(events, never()).saveAll(any());
    }

    @Test
    void legacyEventsRefuseTheOtaMembersAndThePayloadIsBounded() {
        ReleaseWorld world = stubRelease();
        // The legacy family carries no OTA members: the row it would
        // land can never violate the ota_shape disjunction.
        AnalyticsException legacy = catchThrowableOfType(
                () -> service.ingest(
                        UUID.randomUUID(),
                        world.tenantId,
                        List.of(new AnalyticsIngestService.EventCommand(
                                "INSTALL",
                                "SUCCEEDED",
                                world.releaseId,
                                null,
                                null,
                                Instant.now(),
                                null,
                                null,
                                null,
                                UUID.randomUUID().toString(),
                                null))),
                AnalyticsException.class);
        assertThat(legacy).isNotNull();
        assertThat(errorsOf(legacy))
                .containsEntry("ota_bundle_id", "ota_bundle_id belongs to the OTA event types only");
        // The payload twin: the legacy family carries NEITHER member.
        AnalyticsException legacyPayload = catchThrowableOfType(
                () -> service.ingest(
                        UUID.randomUUID(),
                        world.tenantId,
                        List.of(new AnalyticsIngestService.EventCommand(
                                "SESSION",
                                null,
                                world.releaseId,
                                null,
                                UUID.randomUUID().toString(),
                                Instant.now(),
                                1,
                                0,
                                0,
                                null,
                                Map.of("note", "belongs-to-no-legacy-row")))),
                AnalyticsException.class);
        assertThat(legacyPayload).isNotNull();
        assertThat(errorsOf(legacyPayload))
                .containsEntry("ota_payload", "ota_payload belongs to the OTA event types only");

        // The payload's device identity member is bounded by the check
        // surface's own vocabulary.
        UUID bundleId = UUID.randomUUID();
        stubBundle(bundleId, world.applicationId, UUID.randomUUID());
        Map<String, Object> overlong = Map.of("device_id", "d".repeat(129));
        assertThat(errorsOf(refused(world, "OTA_SEEN", null, bundleId, overlong)))
                .containsEntry("ota_payload", "device_id is a non-blank string of at most 128 characters");
        // The member ceiling: a detail object, never a document.
        Map<String, Object> crowded = new LinkedHashMap<>();
        for (int i = 0; i < 33; i++) {
            crowded.put("member-" + i, i);
        }
        assertThat(errorsOf(refused(world, "OTA_SEEN", null, bundleId, crowded)))
                .containsEntry("ota_payload", "ota_payload carries at most 32 members");
        verify(events, never()).saveAll(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theOtaPayloadIsOptionalAndEveryBoundPassesAtItsEdge() {
        ReleaseWorld world = stubRelease();
        UUID bundleId = UUID.randomUUID();
        stubBundle(bundleId, world.applicationId, UUID.randomUUID());
        when(events.landedClientIds(any(UUID.class), any())).thenReturn(List.of());
        AtomicReference<List<AnalyticsEventEntity>> landed = new AtomicReference<>();
        when(events.saveAll(any())).thenAnswer(invocation -> {
            List<AnalyticsEventEntity> copy = new ArrayList<>();
            ((Iterable<AnalyticsEventEntity>) invocation.getArgument(0)).forEach(copy::add);
            landed.set(copy);
            return copy;
        });

        // Optional (V57 admits NULL): a lifecycle report with no payload
        // at all lands its row with the member NULL — beside one AT the
        // bound: exactly 32 members, serialized exactly 4096 characters,
        // device_id exactly 128 characters, and one nested member inside
        // the same serialization (nested content counts at full weight).
        Map<String, Object> atTheBound = payloadAtTheFlatBound(0);
        assertThat(new ObjectMapper().writeValueAsString(atTheBound)).hasSize(4096);
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("device_id", "fleet-device-1");
        nested.put("context", Map.of("stage", "verify", "attempt", 2));
        AnalyticsIngestService.IngestReport report = service.ingest(
                UUID.randomUUID(),
                world.tenantId,
                List.of(
                        world.otaEvent("OTA_SEEN", null, bundleId.toString(), null),
                        world.otaEvent("OTA_DOWNLOAD", "SUCCEEDED", bundleId.toString(), atTheBound),
                        world.otaEvent("OTA_ROLLED_BACK", null, bundleId.toString(), nested),
                        world.otaEvent("OTA_FAILED", "apply_error", bundleId.toString(), Map.of())));
        assertThat(report.ingested()).isEqualTo(4);
        assertThat(landed.get().get(0).otaPayload()).isNull();
        assertThat(landed.get().get(1).otaPayload()).containsKey("pad");
        assertThat(landed.get().get(2).otaPayload()).containsKey("context");
        assertThat(landed.get().get(3).otaPayload()).isEmpty();
        verify(audit, times(1)).publish(any());

        // One character over the serialized bound: the field-addressed
        // refusal — and the nested escape is closed: one member whose
        // nested value alone dwarfs the bound.
        assertThat(errorsOf(refused(world, "OTA_SEEN", null, bundleId, payloadAtTheFlatBound(1))))
                .containsEntry("ota_payload", "ota_payload exceeds 4096 characters");
        assertThat(errorsOf(refused(world, "OTA_SEEN", null, bundleId, Map.of("a", Map.of("b", "x".repeat(5000))))))
                .containsEntry("ota_payload", "ota_payload exceeds 4096 characters");
        // The defensive catch: a payload the mapper cannot render (a
        // self-referencing map — only constructible on the
        // direct-construction path; a deserialized map carries JSON
        // types alone) is the field-addressed refusal, never a 500.
        Map<String, Object> cyclic = new LinkedHashMap<>();
        cyclic.put("self", cyclic);
        assertThat(errorsOf(refused(world, "OTA_SEEN", null, bundleId, cyclic)))
                .containsEntry("ota_payload", "ota_payload must be a JSON object");
        // The device identity member is the check surface's vocabulary:
        // a non-blank string — blank is not a member of it.
        assertThat(errorsOf(refused(world, "OTA_SEEN", null, bundleId, Map.of("device_id", "   "))))
                .containsEntry("ota_payload", "device_id is a non-blank string of at most 128 characters");
        verify(events, times(1)).saveAll(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void lowercaseOutcomeWordsNormalizeOntoTheClosedPair() {
        ReleaseWorld world = stubRelease();
        UUID bundleId = UUID.randomUUID();
        stubBundle(bundleId, world.applicationId, UUID.randomUUID());
        when(events.landedClientIds(any(UUID.class), any())).thenReturn(List.of());
        AtomicReference<List<AnalyticsEventEntity>> landed = new AtomicReference<>();
        when(events.saveAll(any())).thenAnswer(invocation -> {
            List<AnalyticsEventEntity> copy = new ArrayList<>();
            ((Iterable<AnalyticsEventEntity>) invocation.getArgument(0)).forEach(copy::add);
            landed.set(copy);
            return copy;
        });

        // The INSTALL precedent's own case-insensitivity: a lowercase
        // outcome word rides the closed pair and lands uppercase.
        service.ingest(
                UUID.randomUUID(),
                world.tenantId,
                List.of(
                        world.otaEvent("OTA_DOWNLOAD", "succeeded", bundleId.toString(), null),
                        world.otaEvent("OTA_APPLIED", "failed", bundleId.toString(), null)));

        assertThat(landed.get()).extracting(AnalyticsEventEntity::result).containsExactly("SUCCEEDED", "FAILED");
    }

    /**
     * One OTA payload sitting exactly at every bound at once: 32 members,
     * a JSON serialization of {@code 4096 + extra} characters, and a
     * {@code device_id} of exactly 128 characters.
     */
    private static Map<String, Object> payloadAtTheFlatBound(int extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("device_id", "d".repeat(128));
        for (int i = 0; i < 30; i++) {
            payload.put("m" + i, "x");
        }
        // The pad member joins the serialization as
        // {@code ,"pad":"<value>"} — sized so the whole object serializes
        // to exactly the bound (plus the requested overflow).
        int withoutPad = new ObjectMapper().writeValueAsString(payload).length();
        int padOverhead = 1 + "\"pad\":".length() + 2;
        payload.put("pad", "p".repeat(Math.max(0, 4096 - withoutPad - padOverhead + extra)));
        return payload;
    }

    /** Runs one OTA event through the ingest and returns the thrown exception (never null: every leg refuses). */
    private AnalyticsException refused(ReleaseWorld world, String type, String result, UUID bundleId) {
        return refused(world, type, result, bundleId == null ? null : bundleId.toString(), null);
    }

    private AnalyticsException refused(
            ReleaseWorld world, String type, String result, UUID bundleId, Map<String, Object> payload) {
        return refused(world, type, result, bundleId == null ? null : bundleId.toString(), payload);
    }

    private AnalyticsException refused(
            ReleaseWorld world, String type, String result, String bundleId, Map<String, Object> payload) {
        return catchThrowableOfType(
                () -> service.ingest(
                        UUID.randomUUID(), world.tenantId, List.of(world.otaEvent(type, result, bundleId, payload))),
                AnalyticsException.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> errorsOf(AnalyticsException thrown) {
        return (Map<String, String>) thrown.problem().getProperties().get("errors");
    }

    /** Stubs the bundle seam: a resolved bundle of the named application, answering its id. */
    private UUID stubBundle(UUID bundleId, UUID applicationId, UUID versionId) {
        when(bundles.bundleOf(bundleId))
                .thenReturn(Optional.of(new BundleFact(
                        bundleId,
                        applicationId,
                        UUID.randomUUID(),
                        versionId,
                        UUID.randomUUID(),
                        null,
                        "1.4.2",
                        "ios",
                        false,
                        null,
                        "RELEASED")));
        return bundleId;
    }

    /** One tenant, one resolvable release, and the event shapes that name it. */
    private ReleaseWorld stubRelease() {
        UUID tenantId = UUID.randomUUID();
        UUID releaseId = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        when(releases.telemetryFactsOf(releaseId, tenantId))
                .thenReturn(Optional.of(new ReleaseFacts(
                        releaseId,
                        "rel-" + releaseId,
                        applicationId,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        tenantId,
                        UUID.randomUUID(),
                        "READY",
                        null,
                        "qa",
                        null)));
        return new ReleaseWorld(tenantId, releaseId, applicationId);
    }

    private record ReleaseWorld(UUID tenantId, UUID releaseId, UUID applicationId) {

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
                    null,
                    null,
                    null);
        }

        private AnalyticsIngestService.EventCommand otaEvent(
                String type, String result, String bundleId, Map<String, Object> payload) {
            return new AnalyticsIngestService.EventCommand(
                    type,
                    result,
                    releaseId,
                    null,
                    UUID.randomUUID().toString(),
                    Instant.now(),
                    null,
                    null,
                    null,
                    bundleId,
                    payload);
        }
    }
}
