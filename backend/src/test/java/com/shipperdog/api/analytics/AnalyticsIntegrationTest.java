package com.shipperdog.api.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.shipperdog.api.gate.GateCatalog;
import com.shipperdog.api.gate.GateMetricSource;
import com.shipperdog.api.identity.AuditCapture;
import com.shipperdog.api.release.PolicyMetric;
import com.shipperdog.api.release.ReleasePolicyMetricSource;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end acceptance flow for the release analytics pipeline
 * (issue #49, S11-04, FR-029): the metrics dictionary served per
 * release with the explicit NO_DATA where device-side sources do not
 * exist yet (AC1/AC2), the frozen events API feeding those metrics
 * and the two evaluation seams (AC2's fail-closed contract at the
 * seam level), the internal metrics populating from the S8-04/S10-01
 * facts (AC3), the bounded series queries (AC4), the idempotent
 * rollups with late-event healing (AC5), and the download-counter
 * reconciliation (AC6).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef",
            "shipperdog.analytics.rollup.enabled=false"
        })
@Testcontainers
@Import(AuditCapture.class)
class AnalyticsIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";
    private static final String PASSWORD = "a-perfectly-valid-password";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @LocalServerPort
    int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AuditCapture audit;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private List<GateMetricSource> gateSources;

    @Autowired
    private List<ReleasePolicyMetricSource> policySources;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        audit.reset();
    }

    // -----------------------------------------------------------------------
    // AC1/AC2: the dictionary, the explicit NO_DATA, the concealed miss
    // -----------------------------------------------------------------------

    @Test
    void releaseMetricsServeTheDictionaryWithNoDataExplicit() throws Exception {
        World world = world("an-dict");
        recordDownloads(world, 4, Instant.now().minus(Duration.ofMinutes(30)));
        recordApprovalRound(
                world,
                Instant.now().minus(Duration.ofMinutes(90)),
                Instant.now().minus(Duration.ofMinutes(30)));

        JsonNode metrics = get(
                world.managerToken,
                "/api/v1/applications/" + world.app + "/releases/" + world.releaseId + "/metrics",
                200);
        assertThat(metrics.get("releaseRef").asString()).startsWith("R-");
        assertThat(metrics.get("currentRing").asString()).isEqualTo("qa");
        assertThat(metrics.get("releaseStatus").asString()).isEqualTo("DRAFT");
        Map<String, JsonNode> byMetric = new HashMap<>();
        metrics.get("metrics").forEach(entry -> byMetric.put(entry.get("metric").asString(), entry));
        assertThat(byMetric.keySet())
                .containsExactlyInAnyOrder(
                        "DOWNLOADS",
                        "INSTALLATIONS",
                        "INSTALL_SUCCESS_RATE",
                        "CRASH_FREE_SESSIONS",
                        "ANR_RATE",
                        "UNINSTALL_RATE",
                        "TESTER_ENGAGEMENT",
                        "APPROVAL_DURATION",
                        "TIME_IN_RING");

        // The internal family answers from day one (AC3): the fixture's
        // downloads, the fixture's decision, the live ring span.
        assertThat(byMetric.get("DOWNLOADS").get("value").asDouble()).isEqualTo(4.0);
        assertThat(byMetric.get("APPROVAL_DURATION").get("value").asDouble()).isCloseTo(60.0, within(1e-6));
        assertThat(byMetric.get("TIME_IN_RING").get("value").asDouble()).isPositive();
        assertThat(byMetric.get("TESTER_ENGAGEMENT").get("value").asDouble()).isEqualTo(1.0);

        // The device-side family answers the EXPLICIT NO_DATA (AC2):
        // value null, never a zero.
        for (String noData :
                List.of("INSTALLATIONS", "INSTALL_SUCCESS_RATE", "CRASH_FREE_SESSIONS", "ANR_RATE", "UNINSTALL_RATE")) {
            assertThat(byMetric.get(noData).get("value").isNull()).as(noData).isTrue();
            assertThat(byMetric.get(noData).get("source").asString()).isEqualTo("DEVICE_SDK");
        }

        // The concealed miss: an unknown release under the caller's own
        // application is the release resolution's miss; a foreign
        // application is the route's own app-grain denial, concealed the
        // authorization layer's way (both opaque 404s; PRD 12).
        JsonNode unknown = get(
                world.managerToken,
                "/api/v1/applications/" + world.app + "/releases/" + UUID.randomUUID() + "/metrics",
                404);
        assertThat(unknown.get("code").asString()).isEqualTo("RELEASE_NOT_FOUND");
        JsonNode mismatched = get(
                world.managerToken,
                "/api/v1/applications/" + UUID.randomUUID() + "/releases/" + world.releaseId + "/metrics",
                404);
        assertThat(mismatched.get("code").asString()).isEqualTo("APPLICATION_NOT_FOUND");

        // The permission boundary: the tenant admin of a target tenant
        // holds no app-scope analytics authority — the concealed shape.
        get(
                world.tenantAdminToken,
                "/api/v1/applications/" + world.app + "/releases/" + world.releaseId + "/metrics",
                404);
    }

    // -----------------------------------------------------------------------
    // AC2/AC3: the frozen events API feeds the metrics and the seams
    // -----------------------------------------------------------------------

    @Test
    void ingestEventsFeedTheDeviceMetricsAndTheSeamsFailClosedWithoutData() throws Exception {
        World world = world("an-ingest");
        GateMetricSource analyticsGate = gateSources.stream()
                .filter(source -> source.getClass().getName().contains("AnalyticsMetricSources"))
                .findFirst()
                .orElseThrow();
        ReleasePolicyMetricSource analyticsPolicy = policySources.stream()
                .filter(source -> source.getClass().getName().contains("AnalyticsMetricSources"))
                .findFirst()
                .orElseThrow();

        // AC2's contract at the seam: no events yet -> empty answers
        // (the NO_DATA the gate/policy engines fail closed on), and
        // the analytics registrant steps aside for the metrics it does
        // not own.
        assertThat(analyticsGate.metricOf(world.releaseId, GateCatalog.Metric.CRASH_FREE_RATE))
                .isEmpty();
        assertThat(analyticsPolicy.metricOf(world.releaseId, PolicyMetric.ANR_RATE))
                .isEmpty();
        assertThat(analyticsPolicy.metricOf(world.releaseId, PolicyMetric.OPEN_CRITICAL_BUGS))
                .isEmpty();
        assertThat(analyticsPolicy.metricOf(world.releaseId, PolicyMetric.CRITICAL_VULNERABILITIES))
                .isEmpty();
        assertThat(analyticsPolicy.metricOf(world.releaseId, PolicyMetric.TESTER_APPROVAL_RATE))
                .isEmpty();

        // The frozen contract's shapes: installs, session summaries,
        // uninstalls, verdicts — one batch, one release.
        JsonNode receipt = post(
                world.tenantAdminToken,
                "/api/v1/tenants/" + world.tenantA + "/analytics/events",
                Map.of(
                        "events",
                        List.of(
                                event(world, "INSTALL", "SUCCEEDED", null, null, null),
                                event(world, "INSTALL", "SUCCEEDED", null, null, null),
                                event(world, "INSTALL", "FAILED", null, null, null),
                                event(world, "SESSION", null, 100, 2, 1),
                                event(world, "UNINSTALL", null, null, null, null),
                                event(world, "TESTER_VERDICT", "APPROVED", null, null, null),
                                event(world, "TESTER_VERDICT", "APPROVED", null, null, null),
                                event(world, "TESTER_VERDICT", "REJECTED", null, null, null))),
                201);
        assertThat(receipt.get("ingested").asInt()).isEqualTo(8);
        assertThat(receipt.get("releaseRef").asString()).startsWith("R-");

        // The metrics view flips from NO_DATA to values.
        JsonNode metrics = get(
                world.managerToken,
                "/api/v1/applications/" + world.app + "/releases/" + world.releaseId + "/metrics",
                200);
        Map<String, JsonNode> byMetric = new HashMap<>();
        metrics.get("metrics").forEach(entry -> byMetric.put(entry.get("metric").asString(), entry));
        assertThat(byMetric.get("INSTALLATIONS").get("value").asDouble()).isEqualTo(3.0);
        assertThat(byMetric.get("INSTALL_SUCCESS_RATE").get("value").asDouble()).isCloseTo(2.0 / 3.0, within(1e-9));
        assertThat(byMetric.get("CRASH_FREE_SESSIONS").get("value").asDouble()).isCloseTo(0.98, within(1e-9));
        assertThat(byMetric.get("ANR_RATE").get("value").asDouble()).isCloseTo(0.01, within(1e-9));
        assertThat(byMetric.get("UNINSTALL_RATE").get("value").asDouble()).isCloseTo(1.0 / 3.0, within(1e-9));

        // The seams now answer (the same formulas, the evaluation-time
        // path the gates and policies read): the gate seam in its own
        // percentage unit, the policy seam in the dictionary's 0..1
        // rate (each surface's documented contract).
        assertThat(analyticsGate.metricOf(world.releaseId, GateCatalog.Metric.CRASH_FREE_RATE))
                .contains(98.0);
        assertThat(analyticsPolicy.metricOf(world.releaseId, PolicyMetric.CRASH_FREE_RATE))
                .contains(0.98);
        assertThat(analyticsPolicy.metricOf(world.releaseId, PolicyMetric.TESTER_APPROVAL_RATE))
                .contains(2.0 / 3.0);

        // The batch is audited by shape: counts by type, the release
        // ref; no device identifiers (none were sent, none appear).
        var ingested = audit.eventsOfType(com.shipperdog.api.audit.AuditEventType.ANALYTICS_EVENTS_INGESTED);
        assertThat(ingested).hasSize(1);
        assertThat(ingested.get(0).detail().get("count")).isEqualTo("8");
        assertThat(ingested.get(0).detail().get("install")).isEqualTo("3");
        assertThat(ingested.get(0).detail().get("session")).isEqualTo("1");
        assertThat(ingested.get(0).detail().get("releaseRef")).startsWith("R-");

        // Validation, whole-batch: the type vocabulary, the session
        // shape, the future bound, the one-release rule.
        assertThat(post(
                                world.tenantAdminToken,
                                "/api/v1/tenants/" + world.tenantA + "/analytics/events",
                                Map.of("events", List.of(event(world, "TELEPORT", null, null, null, null))),
                                400)
                        .get("errors")
                        .get("type")
                        .asString())
                .contains("TELEPORT");
        assertThat(post(
                                world.tenantAdminToken,
                                "/api/v1/tenants/" + world.tenantA + "/analytics/events",
                                Map.of("events", List.of(event(world, "SESSION", null, 0, 0, 0))),
                                400)
                        .toString())
                .contains("sessions");
        Map<String, Object> future = event(world, "INSTALL", "SUCCEEDED", null, null, null);
        future.put("occurredAt", Instant.now().plusSeconds(3600).toString());
        assertThat(post(
                                world.tenantAdminToken,
                                "/api/v1/tenants/" + world.tenantA + "/analytics/events",
                                Map.of("events", List.of(future)),
                                400)
                        .toString())
                .contains("future");
        Map<String, Object> otherRelease = event(world, "INSTALL", "SUCCEEDED", null, null, null);
        otherRelease.put("releaseId", UUID.randomUUID().toString());
        assertThat(post(
                                world.tenantAdminToken,
                                "/api/v1/tenants/" + world.tenantA + "/analytics/events",
                                Map.of(
                                        "events",
                                        List.of(event(world, "INSTALL", "SUCCEEDED", null, null, null), otherRelease)),
                                400)
                        .get("errors")
                        .get("release_id")
                        .asString())
                .contains("one release");

        // The concealed miss: a release the reporting tenant is not
        // targeted by is byte-identical to an unknown id.
        Map<String, Object> foreign = event(world, "INSTALL", "SUCCEEDED", null, null, null);
        foreign.put("releaseId", UUID.randomUUID().toString());
        JsonNode foreignMiss = post(
                world.tenantAdminToken,
                "/api/v1/tenants/" + world.tenantA + "/analytics/events",
                Map.of("events", List.of(foreign)),
                404);
        assertThat(foreignMiss.get("code").asString()).isEqualTo("RELEASE_NOT_FOUND");

        // The ingest authority is the tenant's own: tenant B's admin
        // cannot report against tenant A's context.
        post(
                world.tenantBAdminToken,
                "/api/v1/tenants/" + world.tenantA + "/analytics/events",
                Map.of("events", List.of(event(world, "INSTALL", "SUCCEEDED", null, null, null))),
                404);

        // A device link, when the reporter owns an ACTIVE one in the
        // tenant; another tenant's device is the concealed miss.
        UUID deviceId = UUID.fromString(post(
                        world.tenantAdminToken,
                        "/api/v1/tenants/" + world.tenantA + "/devices",
                        Map.of(
                                "testerId",
                                world.testerId.toString(),
                                "platform",
                                "IOS",
                                "model",
                                "iPhone 15",
                                "osVersion",
                                "18.2",
                                "identifier",
                                "AN-EVT-1"),
                        201)
                .get("id")
                .asText());
        Map<String, Object> withDevice = event(world, "INSTALL", "SUCCEEDED", null, null, null);
        withDevice.put("deviceId", deviceId.toString());
        assertThat(post(
                                world.tenantAdminToken,
                                "/api/v1/tenants/" + world.tenantA + "/analytics/events",
                                Map.of("events", List.of(withDevice)),
                                201)
                        .get("ingested")
                        .asInt())
                .isEqualTo(1);
        Map<String, Object> foreignDevice = event(world, "INSTALL", "SUCCEEDED", null, null, null);
        foreignDevice.put("deviceId", UUID.randomUUID().toString());
        assertThat(post(
                                world.tenantAdminToken,
                                "/api/v1/tenants/" + world.tenantA + "/analytics/events",
                                Map.of("events", List.of(foreignDevice)),
                                400)
                        .toString())
                .contains("device");
    }

    // -----------------------------------------------------------------------
    // AC5/AC6: idempotent rollups, late-event healing, reconciliation
    // -----------------------------------------------------------------------

    @Test
    void rollupsAreIdempotentHealLateEventsAndReconcileWithTheCounter() throws Exception {
        World world = world("an-roll");
        Instant bucket = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(Duration.ofHours(1));
        // The S8-04 records of the same acts: the audit trail AND the
        // artifact counter (two independent writes of one download).
        recordDownloads(world, 7, bucket);
        recordApprovalRound(world, bucket, bucket.plusSeconds(1800));

        Instant from = Instant.now().minus(Duration.ofHours(3));
        Instant to = Instant.now().plus(Duration.ofMinutes(5));
        String trigger = "/api/v1/applications/" + world.app + "/metrics/rollups";
        JsonNode first = post(world.managerToken, trigger, Map.of("from", from.toString(), "to", to.toString()), 200);
        assertThat(first.get("rows").asInt()).isPositive();

        long counter = downloadsCounter(world);
        long rolled = downloadsRollup(world, "metric_hourly");
        long rolledDaily = downloadsRollup(world, "metric_daily");
        // AC6: the counter the S8-04 surface increments reconciles
        // with the rollups, at both grains.
        assertThat(rolled).isEqualTo(counter);
        assertThat(rolledDaily).isEqualTo(counter);

        // AC5: idempotent — the same window recomputes the same
        // numbers, nothing doubles.
        post(world.managerToken, trigger, Map.of("from", from.toString(), "to", to.toString()), 200);
        assertThat(downloadsRollup(world, "metric_hourly")).isEqualTo(counter);

        // Late events inside the window heal by recompute: three more
        // downloads land against the SAME bucket, the counter moves,
        // the next pass folds them.
        recordDownloads(world, 3, bucket);
        post(world.managerToken, trigger, Map.of("from", from.toString(), "to", to.toString()), 200);
        assertThat(downloadsRollup(world, "metric_hourly")).isEqualTo(counter + 3);
        assertThat(downloadsRollup(world, "metric_hourly")).isEqualTo(downloadsCounter(world));

        // The approval duration rollup: the fixture's 30-minute round,
        // attributed to the decision's bucket.
        Double approvalMinutes = jdbc.queryForObject(
                "SELECT value FROM metric_hourly WHERE release_id = ? AND metric = 'APPROVAL_DURATION'",
                Double.class,
                world.releaseId);
        assertThat(approvalMinutes).isCloseTo(30.0, within(1e-6));

        // The time-in-ring rollup stays absent while the release holds
        // its starting ring (the open span is the live read's, AC1's
        // positive TIME_IN_RING current value).
        Long closedSpans = jdbc.queryForObject(
                "SELECT count(*) FROM metric_hourly WHERE release_id = ? AND metric = 'TIME_IN_RING'",
                Long.class,
                world.releaseId);
        assertThat(closedSpans).isZero();

        // The ring attribution: the fixture's downloads sit on the
        // release's starting ring (qa).
        String ringCode = jdbc.queryForObject(
                "SELECT rg.code FROM metric_hourly m JOIN rings rg ON rg.id = m.ring_id "
                        + "WHERE m.release_id = ? AND m.metric = 'DOWNLOADS'",
                String.class,
                world.releaseId);
        assertThat(ringCode).isEqualTo("qa");

        // The recompute trigger's own bounds: a window beyond the
        // maximum is refused, never clamped.
        JsonNode refused = post(
                world.managerToken,
                trigger,
                Map.of("from", Instant.now().minus(Duration.ofDays(400)).toString(), "to", to.toString()),
                422);
        assertThat(refused.get("code").asString()).isEqualTo("ANALYTICS_REQUEST_REFUSED");

        // The trigger is the release governance persona's: the viewer
        // reads but does not recompute.
        post(world.viewerToken, trigger, Map.of("from", from.toString(), "to", to.toString()), 404);
    }

    // -----------------------------------------------------------------------
    // AC4: the bounded series queries
    // -----------------------------------------------------------------------

    @Test
    void seriesServeRollupsWithBoundedWindowsAndFilters() throws Exception {
        World world = world("an-series");
        Instant bucket = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(Duration.ofHours(1));
        recordDownloads(world, 5, bucket);
        post(
                world.managerToken,
                "/api/v1/applications/" + world.app + "/metrics/rollups",
                Map.of(
                        "from",
                        Instant.now().minus(Duration.ofHours(3)).toString(),
                        "to",
                        Instant.now().plus(Duration.ofMinutes(5)).toString()),
                200);

        String series = "/api/v1/applications/" + world.app + "/metrics?metric=DOWNLOADS&from="
                + Instant.now().minus(Duration.ofHours(3)) + "&to=" + Instant.now();
        JsonNode points = get(world.managerToken, series, 200);
        assertThat(points.get("items").size()).isEqualTo(1);
        assertThat(points.get("items").get(0).get("value").asDouble()).isEqualTo(5.0);
        assertThat(points.get("items").get(0).get("releaseRef").asString()).startsWith("R-");
        assertThat(points.get("items").get(0).get("ringCode").asString()).isEqualTo("qa");

        // The ring filter narrows to the ring named.
        JsonNode qaOnly = get(world.managerToken, series + "&ring=qa", 200);
        assertThat(qaOnly.get("items").size()).isEqualTo(1);
        JsonNode productionOnly = get(world.managerToken, series + "&ring=production", 200);
        assertThat(productionOnly.get("items").size()).isZero();

        // The release filter narrows to the release named; a foreign
        // release id under this application is the concealed miss.
        JsonNode releaseScoped = get(world.managerToken, series + "&releaseId=" + world.releaseId, 200);
        assertThat(releaseScoped.get("items").size()).isEqualTo(1);
        get(world.managerToken, series + "&releaseId=" + UUID.randomUUID(), 404);

        // The daily grain: a day's bucket STARTS at its midnight, so
        // the window must span the day the fixture wrote (the series
        // window selects buckets that start within it — docs/analytics.md).
        JsonNode daily = get(
                world.managerToken,
                "/api/v1/applications/" + world.app + "/metrics?metric=DOWNLOADS&from="
                        + Instant.now().minus(Duration.ofDays(3)) + "&to=" + Instant.now() + "&grain=day",
                200);
        assertThat(daily.get("items").size()).isEqualTo(1);
        assertThat(daily.get("items").get(0).get("value").asDouble()).isEqualTo(5.0);

        // Window bounds: inverted, overlong, overlong-for-hourly, and
        // future-to are each refused with the field named.
        assertThat(get(
                                world.managerToken,
                                "/api/v1/applications/" + world.app + "/metrics?metric=DOWNLOADS&from=" + Instant.now()
                                        + "&to=" + Instant.now().minus(Duration.ofHours(1)),
                                422)
                        .get("code")
                        .asString())
                .isEqualTo("ANALYTICS_REQUEST_REFUSED");
        get(
                world.managerToken,
                "/api/v1/applications/" + world.app + "/metrics?metric=DOWNLOADS&from="
                        + Instant.now().minus(Duration.ofDays(400)) + "&to=" + Instant.now(),
                422);
        get(
                world.managerToken,
                "/api/v1/applications/" + world.app + "/metrics?metric=DOWNLOADS&grain=hour&from="
                        + Instant.now().minus(Duration.ofDays(60)) + "&to=" + Instant.now(),
                422);
        get(
                world.managerToken,
                "/api/v1/applications/" + world.app + "/metrics?metric=DOWNLOADS&from="
                        + Instant.now().minus(Duration.ofHours(1)) + "&to="
                        + Instant.now().plus(Duration.ofDays(2)),
                422);
        assertThat(get(
                                world.managerToken,
                                "/api/v1/applications/" + world.app + "/metrics?metric=LOVE&from="
                                        + Instant.now().minus(Duration.ofHours(1)) + "&to=" + Instant.now(),
                                400)
                        .get("code")
                        .asString())
                .isEqualTo("INVALID_REQUEST");

        // The tenant grain: the tenant admin reads their dimension;
        // the shared application's releases answer the org dimension
        // (the tenant-grain page is empty here by construction), and a
        // foreign tenant conceals.
        String tenantSeries = "/api/v1/tenants/" + world.tenantA + "/metrics?metric=DOWNLOADS&from="
                + Instant.now().minus(Duration.ofHours(3)) + "&to=" + Instant.now();
        assertThat(get(world.tenantAdminToken, tenantSeries, 200).get("items").size())
                .isZero();
        get(
                world.tenantBAdminToken,
                "/api/v1/tenants/" + world.tenantA + "/metrics?metric=DOWNLOADS&from="
                        + Instant.now().minus(Duration.ofHours(3)) + "&to=" + Instant.now(),
                404);

        // The empty-bucket NO_DATA discipline at the series level: a
        // device-side metric with no events in the window has NO
        // points at all (never a zero-valued point).
        JsonNode installs = get(
                world.managerToken,
                "/api/v1/applications/" + world.app + "/metrics?metric=INSTALLATIONS&from="
                        + Instant.now().minus(Duration.ofHours(3)) + "&to=" + Instant.now(),
                200);
        assertThat(installs.get("items").size()).isZero();
    }

    // -----------------------------------------------------------------------
    // The frozen contract's batch discipline: whole-batch validation,
    // the exact ceiling, and the device link's tester attribution
    // -----------------------------------------------------------------------

    @Test
    void rejectedBatchesPersistNothingAndTheCeilingIsExact() throws Exception {
        World world = world("an-batch");
        String ingest = "/api/v1/tenants/" + world.tenantA + "/analytics/events";

        // An empty batch is refused.
        post(world.tenantAdminToken, ingest, Map.of("events", List.of()), 400);
        assertThat(eventCount(world)).isZero();

        // Whole-batch validation: a valid INSTALL ahead of an invalid
        // SESSION lands NOTHING — the batch is atomic.
        post(
                world.tenantAdminToken,
                ingest,
                Map.of(
                        "events",
                        List.of(
                                event(world, "INSTALL", "SUCCEEDED", null, null, null),
                                event(world, "SESSION", null, 0, 0, 0))),
                400);
        assertThat(eventCount(world)).as("a rejected batch persists nothing").isZero();

        // The ceiling: 501 events are refused, 500 land.
        List<Map<String, Object>> oversized = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            oversized.add(event(world, "INSTALL", "SUCCEEDED", null, null, null));
        }
        assertThat(post(world.tenantAdminToken, ingest, Map.of("events", oversized), 400)
                        .toString())
                .contains("500");
        assertThat(eventCount(world)).isZero();
        assertThat(post(world.tenantAdminToken, ingest, Map.of("events", oversized.subList(0, 500)), 201)
                        .get("ingested")
                        .asInt())
                .isEqualTo(500);
        assertThat(eventCount(world)).isEqualTo(500);

        // A device-linked event carries the device's own tester (the
        // attribution the device join index and the engagement family
        // read).
        UUID deviceId = UUID.fromString(post(
                        world.tenantAdminToken,
                        "/api/v1/tenants/" + world.tenantA + "/devices",
                        Map.of(
                                "testerId",
                                world.testerId.toString(),
                                "platform",
                                "IOS",
                                "model",
                                "iPhone 15",
                                "osVersion",
                                "18.2",
                                "identifier",
                                "AN-BATCH-1"),
                        201)
                .get("id")
                .asText());
        Map<String, Object> withDevice = event(world, "INSTALL", "SUCCEEDED", null, null, null);
        withDevice.put("deviceId", deviceId.toString());
        post(world.tenantAdminToken, ingest, Map.of("events", List.of(withDevice)), 201);
        assertThat(jdbc.queryForMap("SELECT device_id, tester_id FROM analytics_events WHERE device_id = ?", deviceId))
                .containsEntry("tester_id", world.testerId);
    }

    // -----------------------------------------------------------------------
    // AC2 at the gate seam: NO_DATA blocks with the real registrant
    // registered, and real sessions flip the gate — the first-
    // definitive-answer loop's crash-free contract in the gate's own
    // percentage unit
    // -----------------------------------------------------------------------

    @Test
    void crashFreeGateFailsClosedOnNoDataAndPassesOnRealSessions() throws Exception {
        World world = world("an-gate");
        post(
                world.managerToken,
                "/api/v1/gate-definitions",
                Map.of(
                        "organizationId",
                        world.org.toString(),
                        "gateType",
                        "crash-free-threshold",
                        "ring",
                        "qa",
                        "required",
                        true,
                        "config",
                        Map.of("threshold", 99.5)),
                201);
        post(
                world.managerToken,
                "/api/v1/releases/" + world.releaseId + "/transitions",
                Map.of("applicationId", world.app.toString(), "to", "VALIDATING"),
                200);

        // NO_DATA through the REAL seam: the analytics registrant is
        // registered and answers empty (no sessions yet) — the crossing
        // blocks fail-closed, never a pass on missing data.
        JsonNode blocked = post(
                world.managerToken,
                "/api/v1/releases/" + world.releaseId + "/transitions",
                Map.of("applicationId", world.app.toString(), "to", "READY"),
                422);
        assertThat(blocked.get("errors").get("ReleaseGatePolicy").asString())
                .contains("crash-free-threshold")
                .contains("no analytics data yet");

        // Real sessions: 1000 sessions, 1 crash — 99.9% crash-free,
        // above the 99.5% threshold. The same computation the
        // dictionary serves (the 0.999 rate) flips the gate to PASSED.
        post(
                world.tenantAdminToken,
                "/api/v1/tenants/" + world.tenantA + "/analytics/events",
                Map.of("events", List.of(event(world, "SESSION", null, 1000, 1, 0))),
                201);
        post(
                world.managerToken,
                "/api/v1/releases/" + world.releaseId + "/transitions",
                Map.of("applicationId", world.app.toString(), "to", "READY"),
                200);

        JsonNode gates = get(
                world.managerToken, "/api/v1/releases/" + world.releaseId + "/gates?applicationId=" + world.app, 200);
        JsonNode crashFree = gateOf(gates, "crash-free-threshold");
        assertThat(crashFree.get("status").asString()).isEqualTo("PASSED");
        assertThat(crashFree.get("observedValue").asDouble()).isCloseTo(99.9, within(0.05));
    }

    // -----------------------------------------------------------------------
    // AC4's window edges: the series window is half-open — the bucket at
    // `from` is inside, the bucket at `to` is not
    // -----------------------------------------------------------------------

    @Test
    void seriesWindowsAreHalfOpenAtBothEdges() throws Exception {
        World world = world("an-edge");
        Instant bucket = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(Duration.ofHours(1));
        recordDownloads(world, 2, bucket);
        post(
                world.managerToken,
                "/api/v1/applications/" + world.app + "/metrics/rollups",
                Map.of(
                        "from",
                        bucket.minusSeconds(3600).toString(),
                        "to",
                        Instant.now().toString()),
                200);

        // The bucket that starts exactly at `from` is served.
        JsonNode fromEdge = get(
                world.managerToken,
                "/api/v1/applications/" + world.app + "/metrics?metric=DOWNLOADS&from=" + bucket + "&to="
                        + bucket.plusSeconds(3600),
                200);
        assertThat(fromEdge.get("items").size()).isEqualTo(1);
        assertThat(fromEdge.get("items").get(0).get("value").asDouble()).isEqualTo(2.0);

        // The bucket that starts exactly at `to` is not its own point.
        JsonNode toEdge = get(
                world.managerToken,
                "/api/v1/applications/" + world.app + "/metrics?metric=DOWNLOADS&from=" + bucket.minusSeconds(3600)
                        + "&to=" + bucket,
                200);
        assertThat(toEdge.get("items").size()).isZero();
    }

    // -----------------------------------------------------------------------
    // AC5 under race: recompute passes over the same window overlapping
    // never double a value or fail — idempotency holds concurrently
    // (the scheduler's overlap case)
    // -----------------------------------------------------------------------

    @Test
    void concurrentRecomputePassesNeverDoubleOrFail() throws Exception {
        World world = world("an-race");
        Instant bucket = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(Duration.ofHours(1));
        recordDownloads(world, 6, bucket);
        String trigger = "/api/v1/applications/" + world.app + "/metrics/rollups";
        Map<String, Object> body = Map.of(
                "from",
                Instant.now().minus(Duration.ofHours(3)).toString(),
                "to",
                Instant.now().toString());

        int threads = 3;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        List<java.util.concurrent.Future<Void>> runs = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            runs.add(pool.submit(() -> {
                start.await();
                post(world.managerToken, trigger, body, 200);
                accepted.incrementAndGet();
                return null;
            }));
        }
        start.countDown();
        for (java.util.concurrent.Future<Void> run : runs) {
            run.get(120, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(accepted.get()).isEqualTo(threads);

        // The same facts, once — never doubled by the overlap.
        assertThat(downloadsRollup(world, "metric_hourly")).isEqualTo(downloadsCounter(world));
        assertThat(downloadsRollup(world, "metric_daily")).isEqualTo(downloadsCounter(world));
    }

    // -----------------------------------------------------------------------
    // Fixtures: the world and its facts
    // -----------------------------------------------------------------------

    private static final class World {
        UUID org;
        String adminToken;
        String ownerToken;
        String managerToken;
        String viewerToken;
        String tenantAdminToken;
        String tenantBAdminToken;
        UUID tenantA;
        UUID tenantB;
        UUID app;
        UUID version;
        UUID iosArtifact;
        UUID androidArtifact;
        UUID releaseId;
        UUID testerId;
        UUID testerUserId;
    }

    /** An organization, two tenants, a released shared application targeting both, one linked tester. */
    private World world(String slug) throws Exception {
        World world = new World();
        world.org = createOrg("An " + slug + " Corp");
        world.adminToken = loginOfOrgRoleIn(world.org, slug + "-admin", "ORG_ADMIN");
        world.ownerToken = loginOfOrgRoleIn(world.org, slug + "-owner", "APP_OWNER");
        world.managerToken = loginOfOrgRoleIn(world.org, slug + "-manager", "RELEASE_MANAGER");
        world.viewerToken = loginOfOrgRoleIn(world.org, slug + "-viewer", "VIEWER");
        world.tenantA = createTenant(world.adminToken, world.org, "An " + slug + " Bank", "an-" + slug + "-a");
        world.tenantB = createTenant(world.adminToken, world.org, "An " + slug + " Bank B", "an-" + slug + "-b");
        world.tenantAdminToken = loginOfTenantAdminRoleIn(world.org, world.tenantA, slug + "-tenant-admin");
        world.tenantBAdminToken = loginOfTenantAdminRoleIn(world.org, world.tenantB, slug + "-tenant-b-admin");
        world.app = createSharedApp(
                world.adminToken, world.org, "An " + slug + " Banking", "com." + slug.replace("-", "") + ".banking");
        world.version = createVersion(world.ownerToken, world.app, "1.0.0");
        world.iosArtifact = insertArtifact(world.version, "IOS", 1);
        world.androidArtifact = insertArtifact(world.version, "ANDROID", 2);
        authorize(world.adminToken, world.app, world.tenantA);
        authorize(world.adminToken, world.app, world.tenantB);
        Map<String, Object> release = new LinkedHashMap<>();
        release.put("applicationId", world.app.toString());
        release.put("versionId", world.version.toString());
        release.put("ring", "qa");
        release.put(
                "artifacts",
                List.of(
                        Map.of("platform", "IOS", "artifactId", world.iosArtifact.toString()),
                        Map.of("platform", "ANDROID", "artifactId", world.androidArtifact.toString())));
        release.put(
                "targets",
                List.of(
                        Map.of("tenantId", world.tenantA.toString(), "testerGroupIds", List.of()),
                        Map.of("tenantId", world.tenantB.toString(), "testerGroupIds", List.of())));
        JsonNode created = post(world.managerToken, "/api/v1/releases", release, 201);
        world.releaseId = UUID.fromString(created.get("id").asText());

        world.testerId = linkedTester(world, slug + "-tester");
        world.testerUserId =
                jdbc.queryForObject("SELECT user_id FROM testers WHERE id = ?", UUID.class, world.testerId);
        return world;
    }

    /**
     * The S8-04 facts of {@code count} downloads: the audit trail rows
     * AND the artifact counter increments — the two independent
     * records of the same acts the reconciliation test compares.
     */
    private void recordDownloads(World world, int count, Instant occurredAt) {
        for (int i = 0; i < count; i++) {
            UUID artifact = i % 2 == 0 ? world.iosArtifact : world.androidArtifact;
            jdbc.update(
                    "INSERT INTO audit_events (id, event_type, occurred_at, user_id, org_id, application_id, "
                            + "version_id, artifact_id, success, detail) VALUES (gen_random_uuid(), "
                            + "'DOWNLOAD_ISSUED', ?, ?, ?, ?, ?, ?, true, '{}'::jsonb)",
                    java.sql.Timestamp.from(occurredAt.plusSeconds(i)),
                    world.testerUserId,
                    world.org,
                    world.app,
                    world.version,
                    artifact);
            jdbc.update("UPDATE artifacts SET download_count = download_count + 1 WHERE id = ?", artifact);
        }
    }

    /** One approval round of the release: created at {@code from}, decided (SATISFIED) at {@code to}. */
    private void recordApprovalRound(World world, Instant from, Instant to) {
        UUID workflowId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO approval_workflows (id, organization_id, name, created_by) "
                        + "VALUES (?, ?, 'fixture-chain', ?)",
                workflowId,
                world.org,
                userIdOf(world.managerToken));
        UUID roundId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO release_approval_rounds (id, release_id, round_number, workflow_id, status, created_by, "
                        + "created_at, updated_at) VALUES (?, ?, 1, ?, 'SATISFIED', ?, ?, ?)",
                roundId,
                world.releaseId,
                workflowId,
                userIdOf(world.managerToken),
                java.sql.Timestamp.from(from),
                java.sql.Timestamp.from(to));
        jdbc.update(
                "INSERT INTO release_approvals (id, release_id, approval_type, approver, status, decided_at, "
                        + "round_id, position, approver_role) VALUES (gen_random_uuid(), ?, 'QA', ?, 'APPROVED', "
                        + "?, ?, 1, 'QA_ENGINEER')",
                world.releaseId,
                userIdOf(world.managerToken),
                java.sql.Timestamp.from(to),
                roundId);
    }

    private long downloadsCounter(World world) {
        return jdbc.queryForObject("SELECT download_count FROM artifacts WHERE id = ?", Long.class, world.iosArtifact)
                + jdbc.queryForObject(
                        "SELECT download_count FROM artifacts WHERE id = ?", Long.class, world.androidArtifact);
    }

    private long downloadsRollup(World world, String table) {
        return jdbc.queryForObject(
                "SELECT coalesce(sum(value), 0) FROM " + table + " WHERE release_id = ? AND metric = 'DOWNLOADS'",
                Long.class,
                world.releaseId);
    }

    private static Map<String, Object> event(
            World world, String type, String result, Integer sessions, Integer crash, Integer anr) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.put("releaseId", world.releaseId.toString());
        if (result != null) {
            event.put("result", result);
        }
        event.put("occurredAt", Instant.now().toString());
        if (sessions != null) {
            event.put("sessions", sessions);
            event.put("crashSessions", crash);
            event.put("anrSessions", anr);
        }
        return event;
    }

    private long eventCount(World world) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM analytics_events WHERE release_id = ?", Long.class, world.releaseId);
    }

    private static JsonNode gateOf(JsonNode gates, String gateType) {
        for (JsonNode gate : gates.get("gates")) {
            if (gateType.equals(gate.get("gateType").asString())) {
                return gate;
            }
        }
        throw new IllegalStateException("gate '" + gateType + "' not in view");
    }

    // -----------------------------------------------------------------------
    // HTTP helpers (the device suite's shapes)
    // -----------------------------------------------------------------------

    private JsonNode get(String bearer, String uri, int expectedStatus) {
        Exchange recorded = client.get()
                .uri(uri)
                .headers(headers -> headers.setBearerAuth(bearer))
                .exchange((request, response) -> exchangeOf(
                        response.getStatusCode().value(),
                        response.getHeaders().getFirst(org.springframework.http.HttpHeaders.CONTENT_TYPE),
                        response.getBody().readAllBytes()));
        assertThat(recorded.status()).as("GET %s -> %d", uri, expectedStatus).isEqualTo(expectedStatus);
        return recorded.json();
    }

    private JsonNode post(String bearer, String uri, Map<String, Object> body, int expectedStatus) {
        Exchange recorded = client.post()
                .uri(uri)
                .headers(headers -> headers.setBearerAuth(bearer))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) -> exchangeOf(
                        response.getStatusCode().value(),
                        response.getHeaders().getFirst(org.springframework.http.HttpHeaders.CONTENT_TYPE),
                        response.getBody().readAllBytes()));
        assertThat(recorded.status())
                .as("POST %s -> %d, body=%s", uri, expectedStatus, recorded.raw())
                .isEqualTo(expectedStatus);
        return recorded.json();
    }

    private Exchange exchangeOf(int status, String contentType, byte[] bytes) {
        try {
            JsonNode json = contentType != null && contentType.contains("json") && bytes.length > 0
                    ? mapper.readTree(bytes)
                    : mapper.createObjectNode();
            return new Exchange(status, contentType, new String(bytes, java.nio.charset.StandardCharsets.UTF_8), json);
        } catch (Exception e) {
            throw new IllegalStateException("could not decode the exchange body", e);
        }
    }

    private record Exchange(int status, String contentType, String raw, JsonNode json) {}

    private UUID insertArtifact(UUID versionId, String platform, long build) {
        UUID artifactId = UUID.randomUUID();
        Map<String, Object> owner =
                jdbc.queryForMap("SELECT organization_id, tenant_id FROM versions WHERE id = ?", versionId);
        String extension = "ANDROID".equals(platform) ? "apk" : "ipa";
        jdbc.update(
                "INSERT INTO artifacts (id, version_id, platform, file_name, storage_key, sha256, size, "
                        + "build_number, status, organization_id, tenant_id) "
                        + "VALUES (?, ?, ?::platform, ?, ?, ?, ?, ?, ?::artifact_status, ?, ?)",
                artifactId,
                versionId,
                platform,
                platform.toLowerCase(java.util.Locale.ROOT) + "-" + build + "." + extension,
                "fixture/" + artifactId + "/artifact",
                "a1".repeat(32),
                1024L,
                build,
                "READY",
                owner.get("organization_id"),
                owner.get("tenant_id"));
        return artifactId;
    }

    private UUID createVersion(String token, UUID applicationId, String versionName) {
        return UUID.fromString(post(
                        token,
                        "/api/v1/applications/" + applicationId + "/versions",
                        Map.of("versionName", versionName),
                        201)
                .get("id")
                .asText());
    }

    private UUID createSharedApp(String token, UUID organizationId, String name, String bundleId) {
        Map<String, Object> body = new HashMap<>();
        body.put("ownershipScope", "ORGANIZATION");
        body.put("organizationId", organizationId.toString());
        body.put("name", name);
        body.put("platforms", List.of("IOS", "ANDROID"));
        body.put("bundleId", bundleId);
        body.put("packageName", bundleId);
        return UUID.fromString(
                post(token, "/api/v1/applications", body, 201).get("id").asText());
    }

    private void authorize(String token, UUID applicationId, UUID tenantId) {
        post(token, "/api/v1/applications/" + applicationId + "/tenants/" + tenantId, Map.of(), 201);
    }

    private UUID createTenant(String token, UUID organizationId, String name, String externalId) {
        return UUID.fromString(post(
                        token,
                        "/api/v1/tenants",
                        Map.of("organizationId", organizationId.toString(), "name", name, "externalId", externalId),
                        201)
                .get("id")
                .asText());
    }

    private UUID createOrg(String orgName) {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        return UUID.fromString(post(devToken, "/api/v1/organizations", Map.of("name", orgName), 201)
                .get("id")
                .asText());
    }

    private String loginOfOrgRoleIn(UUID orgId, String slug, String role) {
        String email = slug + "@example.com";
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        JsonNode invitation = post(
                devToken,
                "/api/v1/auth/invitations",
                Map.of("email", email, "organizationId", orgId.toString(), "role", role),
                201);
        client.post()
                .uri("/api/v1/auth/register")
                .body(Map.of(
                        "invitationToken",
                        invitation.get("invitationToken").asText(),
                        "email",
                        email,
                        "password",
                        PASSWORD,
                        "fullName",
                        "An " + role))
                .retrieve()
                .body(String.class);
        return accessToken(login(email, PASSWORD));
    }

    private String loginOfTenantAdminRoleIn(UUID orgId, UUID tenantId, String slug) {
        String email = slug + "@example.com";
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        JsonNode invitation = post(
                devToken, "/api/v1/auth/invitations", Map.of("email", email, "organizationId", orgId.toString()), 201);
        client.post()
                .uri("/api/v1/auth/register")
                .body(Map.of(
                        "invitationToken",
                        invitation.get("invitationToken").asText(),
                        "email",
                        email,
                        "password",
                        PASSWORD,
                        "fullName",
                        "An Tenant Admin"))
                .retrieve()
                .body(String.class);
        String userToken = accessToken(login(email, PASSWORD));
        String orgAdminToken = loginOfOrgRoleIn(orgId, slug + "-orgadmin", "ORG_ADMIN");
        post(
                orgAdminToken,
                "/api/v1/tenants/" + tenantId + "/members",
                Map.of("userId", userIdOf(userToken).toString(), "role", "TENANT_ADMIN"),
                201);
        return userToken;
    }

    /** Invites, registers, and tenant-links one tester; returns their tester id. */
    private UUID linkedTester(World world, String slug) {
        String email = slug + "@example.com";
        JsonNode invited = post(
                world.adminToken,
                "/api/v1/testers",
                Map.of("organizationId", world.org.toString(), "email", email, "fullName", "An Tester " + slug),
                201);
        client.post()
                .uri("/api/v1/auth/register")
                .body(Map.of(
                        "invitationToken",
                        invited.get("invitation").get("invitationToken").asString(),
                        "email",
                        email,
                        "password",
                        PASSWORD,
                        "fullName",
                        "An Tester " + slug))
                .retrieve()
                .body(String.class);
        post(
                world.adminToken,
                "/api/v1/tenants/" + world.tenantA + "/testers",
                Map.of("testerId", invited.get("id").asText()),
                201);
        return UUID.fromString(invited.get("id").asText());
    }

    private UUID userIdOf(String bearer) {
        String claims = bearer.split("\\.")[1];
        try {
            return UUID.fromString(mapper.readTree(Base64.getUrlDecoder().decode(claims))
                    .get("sub")
                    .asText());
        } catch (Exception e) {
            throw new IllegalStateException("could not decode the token", e);
        }
    }

    private String login(String email, String password) {
        return client.post()
                .uri("/api/v1/auth/login")
                .body(Map.of("email", email, "password", password))
                .retrieve()
                .body(String.class);
    }

    private String accessToken(String tokensJson) {
        try {
            return mapper.readTree(tokensJson).get("accessToken").asString();
        } catch (Exception e) {
            throw new IllegalStateException("could not parse the token pair", e);
        }
    }
}
