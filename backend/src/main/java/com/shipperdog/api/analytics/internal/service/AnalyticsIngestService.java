package com.shipperdog.api.analytics.internal.service;

import com.shipperdog.api.analytics.AnalyticsException;
import com.shipperdog.api.analytics.TelemetryTokenPrincipal;
import com.shipperdog.api.analytics.internal.entity.AnalyticsEventEntity;
import com.shipperdog.api.analytics.internal.entity.AnalyticsEventEntity.Result;
import com.shipperdog.api.analytics.internal.entity.AnalyticsEventEntity.Type;
import com.shipperdog.api.analytics.internal.repository.AnalyticsEventRepository;
import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.config.MetricNames;
import com.shipperdog.api.device.DeviceAccess;
import com.shipperdog.api.device.DeviceAccess.DeviceFacts;
import com.shipperdog.api.otabundle.OtaBundleAccess;
import com.shipperdog.api.otabundle.OtaBundleAccess.BundleFact;
import com.shipperdog.api.release.ReleaseAccess;
import com.shipperdog.api.release.ReleaseAccess.ReleaseFacts;
import com.shipperdog.api.tester.TesterAccess;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * The events API's ingest (issue #49, S11-04, FR-029): the frozen
 * S15-01 SDK contract's write side. One batch per call, each event
 * validated whole before anything lands — the release must exist and
 * target the reporting tenant (the telemetryFactsOf resolution), a
 * named device must be the reporter's own ACTIVE device in that
 * tenant (the feedback link's own discipline), and the type's shape
 * is checked in code beside V38's database checks.
 *
 * <p><strong>The S15-01 authority evolution (issue #72):</strong> the
 * SDK authenticates with the telemetry token the S8-04 download
 * issuance minted — the batch's authority becomes the TOKEN's
 * bindings, enforced here: the path tenant and every event's release
 * must be the token's own (a mismatch is the concealed
 * RELEASE_NOT_FOUND, byte-identical to the genuine miss — an
 * attacker learns nothing), a device-bound token accepts only its
 * device, and a named device must belong to the TOKEN's tester (the
 * downloading tester — the "spoofing another tester" acceptance
 * criterion: there is no client-supplied identity to forge, the
 * token IS the reporter). Tenant administrators keep the console
 * path unchanged (CI and operations); the CONTRACT body is
 * byte-identical for both authorities except the one additive
 * optional member S15-01 freezes in: the client {@code event_id},
 * the dedup key that makes at-least-once delivery idempotent by
 * (device, release, event id).
 *
 * <p>The audit records the batch's shape only — counts by type, the
 * release ref — never device identifiers (the identifier never
 * leaving the device module is the standing privacy posture).
 *
 * <p>Rows are append-only (corrections are new events); an event's
 * {@code occurredAt} may lag {@code receivedAt} arbitrarily — the
 * rollup job's lookback heals late reports inside it, and the
 * backfill trigger repairs history beyond.
 *
 * <p><strong>The S16-08 OTA family extension (issue #170, ADR-0010
 * workstream 5, D9):</strong> the five device lifecycle types —
 * {@code OTA_SEEN}, {@code OTA_DOWNLOAD}, {@code OTA_APPLIED},
 * {@code OTA_FAILED}, {@code OTA_ROLLED_BACK} — join the accepted
 * vocabulary, each attributing itself to the bundle its manifest
 * served ({@code ota_bundle_id}, proven against the release's own
 * application through the otabundle seam) and optionally carrying the
 * {@code ota_payload} object, whose {@code device_id} member is the
 * fleet device identity's carrier (a fleet device is not the device
 * registry's tester hardware — the S16-07 check-time {@code sdt1.}
 * grant is deliberately device-unbound, so identity rides the
 * payload, not the contract's {@code device_id} member).
 * {@code OTA_CHECK} stays server-authoritative (D9: the check surface
 * inserts the one row whose release is known) — the ingest refuses it
 * exactly like an unknown word, so no client can forge the platform's
 * own counting. The family's result vocabulary is validated per type
 * in code beside V57's widened CHECKs: closed words where the plan
 * names them ({@code SUCCEEDED}|{@code FAILED} on the download and
 * apply reports), the documented open row-value set on
 * {@code OTA_FAILED} (checksum_mismatch, apply_error,
 * signature_invalid — the V42 provider doctrine), and no result at
 * all on the two contextless types. The OTA rows share the funnel
 * grain the server-side check row lands in (the bundle's version
 * dimension — {@code OtaCheckEventRecorder}'s own choice) so
 * S16-09/10 compose one funnel from both writers; the V54 event-id
 * dedup and the whole-batch discipline above are byte-identical for
 * the family.
 */
@Service
public class AnalyticsIngestService {

    /** The batch ceiling: one call carries at most this many events (a bound on every write path). */
    private static final int MAX_BATCH = 500;

    /** The future skew tolerance: device clocks drift; anything beyond this is refused. */
    private static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(5);

    /**
     * The {@code Retry-After} hint a flush-time write collision answers
     * with: advisory for the SDK (whose retry cadence is the host's next
     * lifecycle moment), a bound for any other client.
     */
    private static final long CONTENTION_RETRY_AFTER_SECONDS = 5;

    /**
     * The {@code OTA_FAILED} reason vocabulary (the documented open
     * row-value set of V57/the plan — the V42 provider doctrine): the
     * ingest admits exactly these words, stored as given.
     */
    private static final Set<String> OTA_FAILURE_REASONS =
            Set.of("checksum_mismatch", "apply_error", "signature_invalid");

    /** The OTA payload's member ceiling: a detail object, never a document. */
    private static final int MAX_OTA_PAYLOAD_MEMBERS = 32;

    /**
     * The OTA payload's serialized size ceiling: the JSON serialization
     * of the detail object (characters), nested content counted at its
     * full serialized weight. The exact per-event byte bound on the
     * fleet write path — measured with the context's own mapper, the
     * same compact JSON the jsonb column stores.
     */
    private static final int MAX_OTA_PAYLOAD_FLAT_SIZE = 4096;

    /**
     * The payload's {@code device_id} member bound: the check surface's
     * own raw identifier vocabulary (a non-blank string of at most 128
     * characters, S16-07).
     */
    private static final int MAX_OTA_PAYLOAD_DEVICE_ID = 128;

    private final ReleaseAccess releases;

    private final DeviceAccess devices;

    private final TesterAccess testers;

    private final OtaBundleAccess bundles;

    private final AnalyticsEventRepository events;

    private final io.micrometer.core.instrument.MeterRegistry meters;

    private final AuditEventPublisher audit;

    private final ObjectMapper mapper;

    private final Clock clock;

    AnalyticsIngestService(
            ReleaseAccess releases,
            DeviceAccess devices,
            TesterAccess testers,
            OtaBundleAccess bundles,
            AnalyticsEventRepository events,
            io.micrometer.core.instrument.MeterRegistry meters,
            AuditEventPublisher audit,
            ObjectMapper mapper,
            Clock clock) {
        this.releases = releases;
        this.devices = devices;
        this.testers = testers;
        this.bundles = bundles;
        this.events = events;
        this.meters = meters;
        this.audit = audit;
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * Ingests one batch (the frozen contract) under the console
     * authority: whole-batch validation, whole-batch write — a batch
     * with one invalid event lands nothing and answers the
     * field-keyed report.
     */
    @Transactional
    public IngestReport ingest(UUID actorId, UUID tenantId, List<EventCommand> batch) {
        return ingest(actorId, tenantId, null, batch);
    }

    /**
     * Ingests one batch under either authority: the console path
     * (tenant administrator) or the SDK path (a parsed telemetry
     * token whose bindings are enforced below).
     */
    @Transactional
    public IngestReport ingest(
            UUID actorId, UUID tenantId, TelemetryTokenPrincipal telemetry, List<EventCommand> batch) {
        // The token's tenant binding: a token of another tenant on
        // this path is the concealed miss, byte-identical to the
        // release resolution's own.
        if (telemetry != null && !telemetry.tenantId().equals(tenantId)) {
            throw miss();
        }
        if (batch == null || batch.isEmpty()) {
            throw new AnalyticsException(
                    AnalyticsException.Code.INVALID_REQUEST,
                    "the batch carries no events",
                    Map.of("events", "at least one event is required"));
        }
        if (batch.size() > MAX_BATCH) {
            throw new AnalyticsException(
                    AnalyticsException.Code.INVALID_REQUEST,
                    "the batch exceeds " + MAX_BATCH + " events",
                    Map.of("events", "at most " + MAX_BATCH + " events per call"));
        }

        Instant receivedAt = Instant.now(clock);
        Instant futureBound = receivedAt.plus(MAX_FUTURE_SKEW);
        // The token's tester record, resolved once (the downloading
        // tester the device ownership check narrows to).
        UUID tokenTesterId = telemetry == null
                ? null
                : testers.testerIdOfUser(telemetry.testerUserId()).orElse(null);
        List<AnalyticsEventEntity> rows = new ArrayList<>(batch.size());
        ReleaseFacts facts = null;

        for (EventCommand command : batch) {
            Map<String, String> errors = new LinkedHashMap<>();
            Type type = validateType(command.type(), errors);
            // Every event names its release (the frozen contract); one
            // batch carries one release — the first event resolves it
            // through the tenant-targeted probe and the rest must
            // agree.
            if (command.releaseId() == null) {
                errors.put("release_id", "release_id is required");
            } else if (telemetry != null && !telemetry.releaseId().equals(command.releaseId())) {
                // The token's release binding: an event for any other
                // release — including one this token's tenant IS
                // targeted by — is the concealed miss, never a hint.
                throw miss();
            } else if (facts == null) {
                facts = resolveRelease(tenantId, command.releaseId());
            } else if (!facts.releaseId().equals(command.releaseId())) {
                errors.put("release_id", "one release per batch");
            }
            String result = validateResult(type, command.result(), errors);
            int[] sessionShape = validateSessions(type, command, errors);
            Instant occurredAt = validateOccurredAt(command.occurredAt(), futureBound, errors);
            UUID deviceId = validateDevice(tenantId, telemetry, tokenTesterId, command, errors);
            UUID eventId = validateEventId(command.eventId(), errors);
            BundleFact bundle = validateOtaAttribution(type, command, facts, errors);
            if (!errors.isEmpty()) {
                throw new AnalyticsException(
                        AnalyticsException.Code.INVALID_REQUEST, "an event of the batch is invalid", errors);
            }
            UUID testerId = deviceId == null
                    ? tokenTesterId
                    : devices.factsOf(deviceId).map(DeviceFacts::testerId).orElse(tokenTesterId);
            // The OTA family's version dimension is the BUNDLE's own —
            // the server-side check row's choice (S16-07), kept here so
            // both writers of one funnel land identical dimensions; the
            // legacy family keeps the release's version as always.
            UUID versionId = bundle != null ? bundle.versionId() : facts.versionId();
            rows.add(new AnalyticsEventEntity(
                    facts.organizationId(),
                    tenantId,
                    facts.applicationId(),
                    versionId,
                    facts.releaseId(),
                    deviceId,
                    testerId,
                    type,
                    result,
                    sessionShape[0] < 0 ? null : sessionShape[0],
                    sessionShape[1] < 0 ? null : sessionShape[1],
                    sessionShape[2] < 0 ? null : sessionShape[2],
                    occurredAt,
                    receivedAt,
                    eventId,
                    bundle == null ? null : bundle.id(),
                    command.otaPayload()));
        }

        int deduplicated = land(rows, facts);
        // land() filters the duplicate rows OUT in place: what remains
        // in rows is exactly what landed, and the audit's per-type
        // counts are computed from those survivors — never the
        // pre-dedup batch shape.
        int landed = rows.size();
        for (AnalyticsEventEntity row : rows) {
            if (row.eventType() == Type.OTA_FAILED) {
                // The apply-failure meter (S16-11, workstream 7): one
                // increment per LANDED failure report, tagged by the
                // failing bundle and its documented reason vocabulary
                // -- counted on the survivors so an SDK's at-least-once
                // resends never double-count, and live the moment the
                // report lands, before the hourly rollup.
                meters.counter(
                                MetricNames.OTA_APPLY_FAILURE,
                                "bundle",
                                row.otaBundleId() == null
                                        ? "unknown"
                                        : row.otaBundleId().toString(),
                                "reason",
                                row.result() == null ? "unknown" : row.result())
                        .increment();
            }
        }
        Map<String, Integer> typeCounts = new LinkedHashMap<>();
        for (AnalyticsEventEntity row : rows) {
            typeCounts.merge(row.eventType().name(), 1, Integer::sum);
        }

        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("releaseRef", facts.releaseRef());
        detail.put("count", Integer.toString(landed));
        if (deduplicated > 0) {
            detail.put("deduplicated", Integer.toString(deduplicated));
        }
        typeCounts.forEach(
                (type, count) -> detail.put(type.toLowerCase(java.util.Locale.ROOT), Integer.toString(count)));
        audit.publish(AuditEvent.builder(AuditEventType.ANALYTICS_EVENTS_INGESTED)
                .occurredAt(receivedAt)
                .userId(actorId)
                .orgId(facts.organizationId())
                .tenantId(tenantId)
                .applicationId(facts.applicationId())
                .versionId(facts.versionId())
                .success(true)
                .detail(detail)
                .build());

        return new IngestReport(landed, facts.releaseRef(), deduplicated);
    }

    /**
     * Lands the batch once: the client-id dedup re-filter (the
     * (device, release, event id) key of V54) ahead of the write. The
     * unique index stays the race backstop — a concurrent batch that
     * lands one of our keys between the probe and the flush aborts
     * THIS transaction, and the violation is mapped honestly: 503
     * with {@code Retry-After}, never an in-transaction retry (a
     * constraint violation poisons the PostgreSQL transaction, so any
     * re-probe inside it would fail with "current transaction is
     * aborted" and surface a 500). The SDK's offline queue keeps 5xx
     * batches and re-sends them — at-least-once delivery holds, and
     * the retried batch's probe dedups the rows that raced it.
     */
    private int land(List<AnalyticsEventEntity> rows, ReleaseFacts facts) {
        int deduplicated = dropLandedDuplicates(rows, facts.releaseId());
        if (rows.isEmpty()) {
            return deduplicated;
        }
        try {
            events.saveAll(rows);
            events.flush();
        } catch (DataIntegrityViolationException race) {
            throw new AnalyticsException(
                    AnalyticsException.Code.INGEST_WRITE_CONTENTION,
                    "a concurrent batch landed one of this batch's events",
                    CONTENTION_RETRY_AFTER_SECONDS);
        }
        return deduplicated;
    }

    /**
     * Removes every row whose (device, event id) key has already
     * landed (the probe) or appears twice in this batch (intra-batch
     * dedup) — returning how many were dropped.
     */
    private int dropLandedDuplicates(List<AnalyticsEventEntity> rows, UUID releaseId) {
        boolean anyClientIds = rows.stream().anyMatch(row -> row.eventId() != null);
        if (!anyClientIds) {
            return 0;
        }
        List<UUID> ids = rows.stream()
                .map(AnalyticsEventEntity::eventId)
                .filter(Objects::nonNull)
                .toList();
        Set<ClientKey> landed = new HashSet<>();
        for (Object[] pair : events.landedClientIds(releaseId, ids)) {
            landed.add(new ClientKey((UUID) pair[0], (UUID) pair[1]));
        }
        Set<ClientKey> seenInBatch = new HashSet<>();
        int before = rows.size();
        rows.removeIf(row -> {
            if (row.eventId() == null) {
                return false;
            }
            ClientKey key = new ClientKey(row.deviceId(), row.eventId());
            if (landed.contains(key) || !seenInBatch.add(key)) {
                return true;
            }
            return false;
        });
        return before - rows.size();
    }

    /**
     * The (device, event id) composite key — a record, so both UUIDs
     * hash in full (never a 32-bit truncation), and device-null events
     * share one device-less space, the index's sentinel semantics.
     */
    private record ClientKey(UUID deviceId, UUID eventId) {}

    private ReleaseFacts resolveRelease(UUID tenantId, UUID releaseId) {
        return releases.telemetryFactsOf(releaseId, tenantId).orElseThrow(AnalyticsIngestService::miss);
    }

    private static AnalyticsException miss() {
        return new AnalyticsException(
                AnalyticsException.Code.RELEASE_NOT_FOUND, "the addressed release did not resolve");
    }

    /**
     * The ingest's type acceptance: the legacy family's four values
     * plus the five OTA device lifecycle values (S16-08) — every value
     * of the entity's Type enum except {@code OTA_CHECK}, which stays
     * server-authoritative (D9: the S16-07 recorder is its one writer;
     * a device-posted check row would forge the platform's own
     * counting). The refusal is the same field-addressed answer every
     * unknown word gets, byte-identical — the wire teaches nothing
     * about the server's vocabulary.
     */
    private static Type validateType(String token, Map<String, String> errors) {
        if (token == null || token.isBlank()) {
            errors.put("type", "type is required");
            return null;
        }
        Type type;
        try {
            type = Type.valueOf(token.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.put("type", "unknown type " + token);
            return null;
        }
        if (type == Type.OTA_CHECK) {
            errors.put("type", "unknown type " + token);
            return null;
        }
        return type;
    }

    /**
     * The result word, validated per type in code beside V57's widened
     * CHECKs: the legacy family exactly as before (the closed enum
     * words, stored as the enum's name), {@code OTA_DOWNLOAD} and
     * {@code OTA_APPLIED} closed to SUCCEEDED|FAILED (uppercased onto
     * the closed words — the INSTALL precedent's own pair),
     * {@code OTA_FAILED} closed to the documented open row-value set
     * (stored as given, the lowercase provider vocabulary), and no
     * result at all on the remaining types — an OTA_SEEN or
     * OTA_ROLLED_BACK carrying one is the field-addressed refusal, not
     * a silent drop.
     */
    private static String validateResult(Type type, String token, Map<String, String> errors) {
        boolean closedFamily = type == Type.INSTALL || type == Type.TESTER_VERDICT;
        boolean outcomeFamily = type == Type.OTA_DOWNLOAD || type == Type.OTA_APPLIED;
        boolean failureFamily = type == Type.OTA_FAILED;
        boolean needsResult = closedFamily || outcomeFamily || failureFamily;
        if (token == null || token.isBlank()) {
            if (needsResult) {
                errors.put("result", "result is required for " + type);
            }
            return null;
        }
        String trimmed = token.trim();
        if (closedFamily) {
            Result result;
            try {
                result = Result.valueOf(trimmed.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                errors.put("result", "unknown result " + token);
                return null;
            }
            boolean allowed = switch (type) {
                case INSTALL -> result == Result.SUCCEEDED || result == Result.FAILED;
                case TESTER_VERDICT -> result == Result.APPROVED || result == Result.REJECTED;
                default -> false;
            };
            if (!allowed) {
                errors.put("result", result + " is not a " + type + " result");
            }
            return result.name();
        }
        if (outcomeFamily) {
            String word = trimmed.toUpperCase(java.util.Locale.ROOT);
            if (!word.equals(Result.SUCCEEDED.name()) && !word.equals(Result.FAILED.name())) {
                errors.put("result", trimmed + " is not a " + type + " result");
                return null;
            }
            return word;
        }
        if (failureFamily) {
            if (!OTA_FAILURE_REASONS.contains(trimmed)) {
                errors.put("result", trimmed + " is not a " + type + " result");
                return null;
            }
            return trimmed;
        }
        // SESSION, UNINSTALL, OTA_SEEN, OTA_ROLLED_BACK: resultless by
        // shape — a carried word is refused, never silently dropped.
        errors.put("result", trimmed + " is not a " + type + " result");
        return null;
    }

    /**
     * The OTA attribution (S16-08, V57's {@code
     * ck_analytics_events_ota_shape} mirrored in code): a device
     * lifecycle event MUST name the bundle its manifest served, and
     * the name is proven — the bundle resolves (registry probe,
     * whatever its status: attribution is history, not re-resolution)
     * and belongs to the release's own application; an unknown or
     * foreign bundle is one concealed field-addressed answer. The
     * legacy family carries NO OTA members at all — a bundle id or
     * payload on a legacy event is refused so the row it would land
     * can never violate the ota_shape disjunction.
     */
    private BundleFact validateOtaAttribution(
            Type type, EventCommand command, ReleaseFacts facts, Map<String, String> errors) {
        if (type == null || !isOtaFamily(type)) {
            if (command.otaBundleId() != null) {
                errors.put("ota_bundle_id", "ota_bundle_id belongs to the OTA event types only");
            }
            if (command.otaPayload() != null) {
                errors.put("ota_payload", "ota_payload belongs to the OTA event types only");
            }
            return null;
        }
        UUID bundleId = null;
        if (command.otaBundleId() == null || command.otaBundleId().isBlank()) {
            errors.put("ota_bundle_id", "ota_bundle_id is required for " + type);
        } else {
            try {
                bundleId = UUID.fromString(command.otaBundleId().trim());
            } catch (IllegalArgumentException e) {
                errors.put("ota_bundle_id", "ota_bundle_id must be a UUID");
            }
        }
        validateOtaPayload(command.otaPayload(), errors);
        if (bundleId == null || facts == null) {
            return null;
        }
        BundleFact bundle = bundles.bundleOf(bundleId).orElse(null);
        if (bundle == null || !bundle.applicationId().equals(facts.applicationId())) {
            // The concealed miss: an unknown and a foreign bundle are
            // one answer — no oracle distinguishing them.
            errors.put("ota_bundle_id", "the bundle did not resolve");
            return null;
        }
        return bundle;
    }

    /**
     * The OTA payload's own bounds: optional (V57 admits NULL), but
     * when present an object of at most {@value #MAX_OTA_PAYLOAD_MEMBERS}
     * members whose JSON serialization — nested content at its full
     * serialized weight — stays inside {@value #MAX_OTA_PAYLOAD_FLAT_SIZE}
     * characters, with the {@code device_id} member — the fleet device
     * identity's carrier — a non-blank string of at most
     * {@value #MAX_OTA_PAYLOAD_DEVICE_ID} characters when present (the
     * check surface's own raw identifier vocabulary).
     */
    private void validateOtaPayload(Map<String, Object> payload, Map<String, String> errors) {
        if (payload == null) {
            return;
        }
        if (payload.size() > MAX_OTA_PAYLOAD_MEMBERS) {
            errors.put("ota_payload", "ota_payload carries at most " + MAX_OTA_PAYLOAD_MEMBERS + " members");
            return;
        }
        String serialized;
        try {
            serialized = mapper.writeValueAsString(payload);
        } catch (tools.jackson.core.JacksonException e) {
            // Unreachable for a deserialized Map in practice — the
            // defensive refusal keeps the answer a field-addressed
            // problem, never a 500.
            errors.put("ota_payload", "ota_payload must be a JSON object");
            return;
        }
        if (serialized.length() > MAX_OTA_PAYLOAD_FLAT_SIZE) {
            errors.put("ota_payload", "ota_payload exceeds " + MAX_OTA_PAYLOAD_FLAT_SIZE + " characters");
            return;
        }
        Object device = payload.get("device_id");
        boolean deviceValid = device == null
                || (device instanceof String id && !id.isBlank() && id.trim().length() <= MAX_OTA_PAYLOAD_DEVICE_ID);
        if (!deviceValid) {
            errors.put(
                    "ota_payload",
                    "device_id is a non-blank string of at most " + MAX_OTA_PAYLOAD_DEVICE_ID + " characters");
        }
    }

    /** The five device lifecycle types the ingest accepts (OTA_CHECK excluded — server-authoritative). */
    private static boolean isOtaFamily(Type type) {
        return switch (type) {
            case OTA_SEEN, OTA_DOWNLOAD, OTA_APPLIED, OTA_FAILED, OTA_ROLLED_BACK -> true;
            default -> false;
        };
    }

    /** The SESSION shape: positive session count, non-negative crash/ANR counts, their sum inside the total. */
    private static int[] validateSessions(Type type, EventCommand command, Map<String, String> errors) {
        if (type != Type.SESSION) {
            if (command.sessions() != null || command.crashSessions() != null || command.anrSessions() != null) {
                errors.put("sessions", "session counts belong to SESSION events only");
            }
            return new int[] {-1, -1, -1};
        }
        Integer sessions = command.sessions();
        Integer crash = command.crashSessions();
        Integer anr = command.anrSessions();
        if (sessions == null || sessions <= 0) {
            errors.put("sessions", "a positive sessions count is required");
        }
        if (crash == null || crash < 0) {
            errors.put("crash_sessions", "a non-negative crash_sessions count is required");
        }
        if (anr == null || anr < 0) {
            errors.put("anr_sessions", "a non-negative anr_sessions count is required");
        }
        if (sessions != null && crash != null && anr != null && crash + anr > sessions) {
            errors.put("crash_sessions", "crash_sessions + anr_sessions exceeds sessions");
        }
        return new int[] {sessions == null ? -1 : sessions, crash == null ? -1 : crash, anr == null ? -1 : anr};
    }

    private static Instant validateOccurredAt(Instant occurredAt, Instant futureBound, Map<String, String> errors) {
        if (occurredAt == null) {
            errors.put("occurred_at", "occurred_at is required");
            return null;
        }
        if (occurredAt.isAfter(futureBound)) {
            errors.put("occurred_at", "occurred_at is too far in the future");
        }
        return occurredAt;
    }

    /**
     * A named device must be the reporting tenant's own ACTIVE device
     * (the feedback link's discipline) — and under a telemetry token,
     * one of the TOKEN's own: a device-bound token accepts only its
     * device, and any named device must belong to the downloading
     * tester (the spoofing refusal — another tester's device id in
     * the batch is the same field-addressed refusal, never a landed
     * row attributed to someone else). A token whose tester record no
     * longer resolves (the tester deleted, the unbound 90-day token
     * outliving them) names no tester — so it may name NO device
     * either: the narrowing refuses rather than silently widening to
     * the tenant's whole active fleet. Device-less events still land
     * (there is no device identity to spoof; the attribution is
     * simply absent).
     */
    private UUID validateDevice(
            UUID tenantId,
            TelemetryTokenPrincipal telemetry,
            UUID tokenTesterId,
            EventCommand command,
            Map<String, String> errors) {
        if (telemetry != null && telemetry.deviceId() != null) {
            if (!telemetry.deviceId().equals(command.deviceId())) {
                errors.put("device_id", "the device did not resolve for this tenant");
                return command.deviceId();
            }
        }
        if (command.deviceId() == null) {
            return null;
        }
        DeviceFacts device = devices.factsOf(command.deviceId()).orElse(null);
        if (device == null || !tenantId.equals(device.tenantId()) || !"ACTIVE".equals(device.status())) {
            // The concealed miss of the feedback link: unknown, foreign,
            // and parked are one answer.
            errors.put("device_id", "the device did not resolve for this tenant");
        } else if (telemetry != null && (tokenTesterId == null || !tokenTesterId.equals(device.testerId()))) {
            // The token's tester must resolve AND own the named device;
            // an unresolvable tester record is the same field-addressed
            // refusal, never a widening of the check.
            errors.put("device_id", "the device did not resolve for this tenant");
        }
        return command.deviceId();
    }

    /** The optional client event id: a UUID when present, the dedup key's own member (V54). */
    private static UUID validateEventId(String eventId, Map<String, String> errors) {
        if (eventId == null || eventId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(eventId.trim());
        } catch (IllegalArgumentException e) {
            errors.put("event_id", "event_id must be a UUID");
            return null;
        }
    }

    /**
     * One event of the frozen contract, as the API receives it. The
     * OTA members ({@code otaBundleId}, {@code otaPayload}) are the
     * S16-08 additive pair — the event_id precedent's own doctrine:
     * optional members the legacy family never carries, required-ish
     * (the bundle id) on the OTA family, validated whole-batch.
     */
    public record EventCommand(
            String type,
            String result,
            UUID releaseId,
            UUID deviceId,
            String eventId,
            Instant occurredAt,
            Integer sessions,
            Integer crashSessions,
            Integer anrSessions,
            String otaBundleId,
            Map<String, Object> otaPayload) {}

    /**
     * The batch's receipt: how many events landed, against which
     * release ref, and how many at-least-once duplicates were
     * recognized and dropped (the additive S15-01 member — zero for
     * every batch without client event ids).
     */
    public record IngestReport(int ingested, String releaseRef, int deduplicated) {}
}
