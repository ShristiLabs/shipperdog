package com.shipperdog.api.otabundle;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The S16-11 acceptance flow (issue #173): the alert rules' CRUD over
 * HTTP, the evaluation pass's crossings against seeded rollup data
 * (the firing row, the OTA_ALERT_FIRED bus event, the console badge
 * feed), the recovery stamp, the standing-firing dedupe -- and D10's
 * pin that NOTHING halts on a firing: the rollout plan the rule
 * watches stays RUNNING while the alert stands. The pass runs through
 * the service's own evaluate call (the scheduler's exact body; the
 * clock is off, the rollup-scheduler pattern).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef",
            "shipperdog.analytics.rollup.enabled=false",
            "shipperdog.ota.alerts.enabled=false"
        })
@Testcontainers
class OtaAlertIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";
    private static final String PASSWORD = "a-perfectly-valid-password";

    /** The compose GCS emulator image, in a container (ADR-0003) — the bundle fixtures stream bytes through it. */
    @Container
    static final GenericContainer<?> GCS = new GenericContainer<>(
                    DockerImageName.parse("fsouza/fake-gcs-server:1.56.1"))
            .withCommand("-scheme", "http", "-host", "0.0.0.0", "-port", "4443")
            .withExposedPorts(4443);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(
                "shipperdog.artifact.storage.host", () -> "http://" + GCS.getHost() + ":" + GCS.getMappedPort(4443));
        registry.add("shipperdog.ota.app-token-key", () -> "test-ota-app-token-key");
    }

    @LocalServerPort
    int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private com.shipperdog.api.otabundle.internal.service.OtaAlertService alerts;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    // -----------------------------------------------------------------------
    // AC1/AC3: the rules CRUD — create, list, read with history, update,
    // delete, and the field-addressed problem+json validation
    // -----------------------------------------------------------------------

    @Test
    void rulesCrudValidatesShapedAndServesTheWatch() {
        World world = world("f-crud");
        UUID rule = createRule(world, world.app, null, "OTA_FAILURES", "GT", 50.0, 60, "WARNING", 201);

        // The watch serves back whole: every member the row stores.
        JsonNode view = get(world.managerToken, "/api/v1/ota/alert-rules/" + rule + "?applicationId=" + world.app, 200)
                .get("rule");
        assertThat(view.get("id").asString()).isEqualTo(rule.toString());
        assertThat(view.get("applicationId").asString()).isEqualTo(world.app.toString());
        assertThat(view.get("otaBundleId").isNull()).isTrue();
        assertThat(view.get("metric").asString()).isEqualTo("OTA_FAILURES");
        assertThat(view.get("comparator").asString()).isEqualTo("GT");
        assertThat(view.get("threshold").asDouble()).isEqualTo(50.0);
        assertThat(view.get("windowMinutes").asInt()).isEqualTo(60);
        assertThat(view.get("severity").asString()).isEqualTo("WARNING");
        assertThat(view.get("standingFiring").isNull()).isTrue();

        // The bundle-scoped variant and the update's full replace.
        UUID scoped = createRule(
                world, world.app, world.bundle2, "OTA_APPLY_FAILURE_RATE", "GTE", 0.25, 120, "CRITICAL", 201);
        JsonNode updated = put(
                world.managerToken,
                "/api/v1/ota/alert-rules/" + scoped,
                ruleBody(world.app, world.bundle2.toString(), "OTA_CRASH_FREE_RATE", "LT", 0.9, 1440, "CRITICAL"),
                200);
        assertThat(updated.get("metric").asString()).isEqualTo("OTA_CRASH_FREE_RATE");
        assertThat(updated.get("threshold").asDouble()).isEqualTo(0.9);

        // The listing: newest first, the scoped reshape reflected.
        JsonNode list = get(world.managerToken, "/api/v1/ota/alert-rules?applicationId=" + world.app, 200);
        assertThat(list.get("items").size()).isEqualTo(2);

        // The delete: a never-fired rule goes; the validation is the
        // field-addressed problem+json the platform renders.
        delete(world.managerToken, "/api/v1/ota/alert-rules/" + rule + "?applicationId=" + world.app, 204);
        Recording invalid = exchange(
                HttpMethod.POST,
                "/api/v1/ota/alert-rules",
                world.managerToken,
                ruleBody(world.app, null, "NOT_A_METRIC", "BETWEEN", null, 0, "LOUD"));
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.contentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        JsonNode errors = invalid.body().get("errors");
        assertThat(errors.get("metric").asString()).contains("unknown metric");
        assertThat(errors.get("comparator").asString()).contains("GT");
        assertThat(errors.get("threshold").asString()).contains("finite");
        assertThat(errors.get("windowMinutes").asString()).contains("10080");
        assertThat(errors.get("severity").asString()).contains("CRITICAL");

        // A bundle of another application, an unknown bundle, and a
        // rule id that is not the caller's own: the concealed miss.
        World other = world("f-crud-other");
        assertThat(exchange(
                                HttpMethod.POST,
                                "/api/v1/ota/alert-rules",
                                world.managerToken,
                                ruleBody(world.app, other.bundle2.toString(), "OTA_FAILURES", "GT", 1.0, 60, "INFO"))
                        .status())
                .isEqualTo(404);
        assertThat(exchange(
                                HttpMethod.POST,
                                "/api/v1/ota/alert-rules",
                                world.managerToken,
                                ruleBody(
                                        world.app, UUID.randomUUID().toString(), "OTA_FAILURES", "GT", 1.0, 60, "INFO"))
                        .status())
                .isEqualTo(404);
        assertThat(exchange(
                                HttpMethod.GET,
                                "/api/v1/ota/alert-rules/" + UUID.randomUUID() + "?applicationId=" + world.app,
                                world.managerToken)
                        .status())
                .isEqualTo(404);
    }

    @Test
    void ruleCreateRequiresTheOtaOptIn() {
        World world = world("f-optin");
        UUID plainApp = createTenantApp(world.adminToken, world.tenantA, "F Plain App", "com.f.optin.plain");
        Recording refusal = exchange(
                HttpMethod.POST,
                "/api/v1/ota/alert-rules",
                world.managerToken,
                ruleBody(plainApp, null, "OTA_FAILURES", "GT", 1.0, 60, "INFO"));
        assertThat(refusal.status()).isEqualTo(409);
        assertThat(refusal.body().get("code").asString()).isEqualTo("APPLICATION_NOT_OTA_ENABLED");
    }

    // -----------------------------------------------------------------------
    // AC2: the evaluation pass — firing row, bus event, badge feed
    // -----------------------------------------------------------------------

    @Test
    void evaluationFiresPersistsPublishesAndFeedsTheBadgeWithoutHalting() {
        World world = world("f-fire");
        seedRollup(world, "OTA_FAILURES", 75.0);
        seedRollup(world, "OTA_APPLIED", 25.0);
        UUID rule = createRule(world, world.app, world.bundle2, "OTA_FAILURES", "GT", 50.0, 60, "CRITICAL", 201);

        alerts.evaluateAll(Instant.now());

        // The firing row: the rule's standing firing, the observed
        // value the window summed, the detail carrying the arithmetic.
        JsonNode standing = get(
                        world.managerToken, "/api/v1/ota/alert-rules/" + rule + "?applicationId=" + world.app, 200)
                .get("rule")
                .get("standingFiring");
        assertThat(standing).isNotNull();
        assertThat(standing.get("observedValue").asDouble()).isEqualTo(75.0);
        assertThat(standing.get("detail").asString())
                .contains("OTA_FAILURES")
                .contains("75")
                .contains("over 60m");

        // The bus event: one OTA_ALERT_FIRED outbox row carrying the
        // watch's own facts (ADR-0007 -- same transaction as the row).
        Map<String, Object> outbox = jdbc.queryForMap(
                "SELECT application_id, payload FROM notification_outbox"
                        + " WHERE event_name = 'OTA_ALERT_FIRED' AND application_id = ?",
                world.app);
        assertThat(outbox.get("application_id")).isEqualTo(world.app);
        String payload = outbox.get("payload").toString();
        assertThat(payload).contains(rule.toString());
        assertThat(payload).contains("OTA_FAILURES");
        assertThat(payload).contains("CRITICAL");

        // The badge feed: the tenant's standing counts and firing list.
        JsonNode feed = get(world.tenantAdminToken, "/api/v1/tenants/" + world.tenantA + "/dashboard/ota/alerts", 200);
        assertThat(feed.get("counts").get("critical").asInt()).isEqualTo(1);
        assertThat(feed.get("standing").size()).isEqualTo(1);
        assertThat(feed.get("standing").get(0).get("ruleId").asString()).isEqualTo(rule.toString());
        assertThat(feed.get("standing").get(0).get("severity").asString()).isEqualTo("CRITICAL");

        // The badge is the OWNING tenant's alone: a sibling tenant of
        // the same organization (no catalog standing on the
        // application) reads its own feed and sees nothing -- the
        // live-firing isolation the empty-world matrix probes cannot
        // pin.
        UUID siblingTenant = createTenant(
                world.adminToken,
                UUID.fromString(jdbc.queryForObject(
                        "SELECT organization_id FROM applications WHERE id = ?", String.class, world.app)),
                "F Fire Sibling Bank",
                "crm-f-fire-sibling");
        String siblingAdmin = loginOfTenantAdminRoleIn(
                UUID.fromString(jdbc.queryForObject(
                        "SELECT organization_id FROM applications WHERE id = ?", String.class, world.app)),
                siblingTenant,
                "f-fire-sibling-admin");
        JsonNode siblingFeed = get(siblingAdmin, "/api/v1/tenants/" + siblingTenant + "/dashboard/ota/alerts", 200);
        assertThat(siblingFeed.get("counts").get("critical").asInt()).isZero();
        assertThat(siblingFeed.get("standing").size()).isZero();

        // D10, pinned: the firing stood and NOTHING halted -- the plan
        // the rule watches keeps RUNNING; the halt is the operator's
        // own manual act through the S16-06 endpoints.
        JsonNode plan =
                get(world.managerToken, "/api/v1/ota/rollouts/" + world.release2 + "?applicationId=" + world.app, 200);
        assertThat(plan.get("status").asString()).isEqualTo("RUNNING");

        // The pass is idempotent while the crossing stands: one
        // firing, one bus event, however many passes run.
        alerts.evaluateAll(Instant.now());
        alerts.evaluateAll(Instant.now());
        Integer firings =
                jdbc.queryForObject("SELECT count(*) FROM ota_alert_firings WHERE rule_id = ?", Integer.class, rule);
        assertThat(firings).isEqualTo(1);
        Integer events = jdbc.queryForObject(
                "SELECT count(*) FROM notification_outbox WHERE event_name = 'OTA_ALERT_FIRED'"
                        + " AND application_id = ?",
                Integer.class,
                world.app);
        assertThat(events).isEqualTo(1);

        // The recovery: the window re-observes BELOW the threshold
        // (the fleet's failures subside to 10), the standing firing
        // stamps resolved, and the rule detail carries the history.
        // Deleting the rows instead would be NO_DATA -- silence that
        // neither fires nor resolves, the evaluator's own rule.
        jdbc.update(
                "UPDATE metric_hourly SET value = 10 WHERE application_id = ? AND ota_bundle_id = ?"
                        + " AND metric = 'OTA_FAILURES'",
                world.app,
                world.bundle2);
        alerts.evaluateAll(Instant.now());
        JsonNode detail =
                get(world.managerToken, "/api/v1/ota/alert-rules/" + rule + "?applicationId=" + world.app, 200);
        assertThat(detail.get("rule").get("standingFiring").isNull()).isTrue();
        assertThat(detail.get("firings").size()).isEqualTo(1);
        assertThat(detail.get("firings").get(0).get("resolvedAt").isNull()).isFalse();
        Integer stillStanding = jdbc.queryForObject(
                "SELECT count(*) FROM ota_alert_firings WHERE rule_id = ? AND resolved_at IS NULL",
                Integer.class,
                rule);
        assertThat(stillStanding).isZero();

        // The delete now refuses: the rule has firing history, and the
        // trail stays addressable through its rule.
        Recording refused = exchange(
                HttpMethod.DELETE,
                "/api/v1/ota/alert-rules/" + rule + "?applicationId=" + world.app,
                world.managerToken);
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.body().get("code").asString()).isEqualTo("ALERT_RULE_HAS_FIRINGS");
    }

    @Test
    void derivedRateRulesFireOnTheWindowArithmetic() {
        World world = world("f-rate");
        // 25 failures of 125 attempts: a 0.2 apply-failure rate --
        // under a GTE 0.15 watch the rule fires; the bundle-scoped
        // sibling (bundle1) sees no rollup rows and stays silent.
        seedRollup(world, "OTA_FAILURES", 25.0);
        seedRollup(world, "OTA_APPLIED", 100.0);
        UUID fired =
                createRule(world, world.app, world.bundle2, "OTA_APPLY_FAILURE_RATE", "GTE", 0.15, 120, "WARNING", 201);
        UUID silent =
                createRule(world, world.app, world.bundle1, "OTA_APPLY_FAILURE_RATE", "GTE", 0.15, 120, "WARNING", 201);

        alerts.evaluateAll(Instant.now());

        JsonNode standing = get(
                        world.managerToken, "/api/v1/ota/alert-rules/" + fired + "?applicationId=" + world.app, 200)
                .get("rule")
                .get("standingFiring");
        assertThat(standing).isNotNull();
        assertThat(standing.get("observedValue").asDouble()).isEqualTo(0.2);
        assertThat(standing.get("detail").asString()).contains("25 failures / 125 attempts");
        assertThat(get(world.managerToken, "/api/v1/ota/alert-rules/" + silent + "?applicationId=" + world.app, 200)
                        .get("rule")
                        .get("standingFiring")
                        .isNull())
                .isTrue();
    }

    @Test
    void crashFreeRuleFiresOnTheSessionsOfTheBundlesRelease() {
        World world = world("f-crash");
        // 10 crashes of 200 sessions on the new bundle's release: a
        // 0.95 crash-free rate -- a LT 0.97 watch fires (the S16-10
        // health arithmetic, windowed).
        postSessions(world, world.release2, 200, 10);
        UUID rule =
                createRule(world, world.app, world.bundle2, "OTA_CRASH_FREE_RATE", "LT", 0.97, 1440, "CRITICAL", 201);

        alerts.evaluateAll(Instant.now());

        JsonNode standing = get(
                        world.managerToken, "/api/v1/ota/alert-rules/" + rule + "?applicationId=" + world.app, 200)
                .get("rule")
                .get("standingFiring");
        assertThat(standing).isNotNull();
        assertThat(standing.get("observedValue").asDouble()).isEqualTo(0.95);
    }

    // -----------------------------------------------------------------------
    // AC1: the apply-failure meter, on the ingest path
    // -----------------------------------------------------------------------

    @Test
    void ingestCountsTheApplyFailureMeterByBundleAndReason() {
        World world = world("f-meter");
        ingest(
                world,
                List.of(
                        lifecycle(world, "OTA_SEEN", null, Instant.now().minusSeconds(120), device("f-meter", "a")),
                        lifecycle(
                                world,
                                "OTA_DOWNLOAD",
                                "SUCCEEDED",
                                Instant.now().minusSeconds(90),
                                device("f-meter", "a")),
                        lifecycle(
                                world,
                                "OTA_FAILED",
                                "checksum_mismatch",
                                Instant.now().minusSeconds(30),
                                device("f-meter", "a"))));
        double counted = meters.counter(
                        "shipperdog.ota.apply.failure",
                        "bundle",
                        world.bundle2.toString(),
                        "reason",
                        "checksum_mismatch")
                .count();
        assertThat(counted).isGreaterThanOrEqualTo(1.0);
        // The retried duplicate never double-counts: the client id
        // makes the second landing a dedup, and the meter only counts
        // survivors.
        Map<String, Object> retry = lifecycle(
                world, "OTA_FAILED", "checksum_mismatch", Instant.now().minusSeconds(20), device("f-meter", "a"));
        Map<String, Object> first = jdbc.queryForMap(
                "SELECT event_id FROM analytics_events WHERE ota_bundle_id = ? AND event_type = 'OTA_FAILED'",
                world.bundle2);
        retry.put("eventId", first.get("event_id").toString());
        // The retry answers 201 with nothing landed and one dedup --
        // the at-least-once resend folding into the client id.
        JsonNode retried = post(
                world.tenantAdminToken,
                "/api/v1/tenants/" + world.tenantA + "/analytics/events",
                Map.of("events", List.of(retry)),
                201);
        assertThat(retried.get("ingested").asInt()).isZero();
        assertThat(retried.get("deduplicated").asInt()).isEqualTo(1);
        double afterRetry = meters.counter(
                        "shipperdog.ota.apply.failure",
                        "bundle",
                        world.bundle2.toString(),
                        "reason",
                        "checksum_mismatch")
                .count();
        assertThat(afterRetry).isEqualTo(counted);
    }

    // -----------------------------------------------------------------------
    // The fixture: the OTA world of the S16-10 suite, walked the same way
    // -----------------------------------------------------------------------

    private World world(String slug) {
        UUID orgId = createOrg("F " + slug + " Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "f-" + slug + "-admin", "ORG_ADMIN");
        String managerToken = loginOfOrgRoleIn(orgId, "f-" + slug + "-manager", "RELEASE_MANAGER");
        String developerToken = loginOfOrgRoleIn(orgId, "f-" + slug + "-dev", "DEVELOPER");
        UUID tenantA = createTenant(adminToken, orgId, "F " + slug + " Bank", "crm-f-" + slug);
        String tenantAdminToken = loginOfTenantAdminRoleIn(orgId, tenantA, "f-" + slug + "-tenant-admin");
        UUID appId =
                createTenantApp(adminToken, tenantA, "F " + slug + " App", "com.f." + slug.replace("-", "") + ".bank");
        jdbc.update("UPDATE applications SET ota_enabled = true WHERE id = ?", appId);

        Map<String, Object> ring = new LinkedHashMap<>();
        ring.put("organizationId", orgId.toString());
        ring.put("code", "ota");
        ring.put("name", "F OTA");
        ring.put("position", 3);
        ring.put("distributionChannel", "ota");
        post(adminToken, "/api/v1/rings", ring, 201);

        JsonNode first = authorSignedBundle(developerToken, appId, "1.4.0", "ios", payload(384));
        UUID release1 = createOtaRelease(managerToken, appId, first);
        JsonNode second = authorSignedBundle(developerToken, appId, "1.4.2", "ios", payload(512));
        UUID release2 = createOtaRelease(managerToken, appId, second);
        for (String state : List.of("VALIDATING", "READY", "APPROVAL_PENDING", "APPROVED", "DEPLOYING", "ACTIVE")) {
            post(
                    managerToken,
                    "/api/v1/releases/" + release2 + "/transitions",
                    Map.of("applicationId", appId.toString(), "to", state),
                    200);
        }
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("applicationId", appId.toString());
        plan.put("releaseId", release2.toString());
        plan.put("mode", "CHECKPOINT");
        // Two stages so the plan stands mid-rollout (RUNNING at stage
        // 1) -- the D10 pin needs a live plan the alert could have
        // halted but did not.
        plan.put("stages", List.of(Map.of("basisPoints", 5000), Map.of("basisPoints", 10000)));
        post(managerToken, "/api/v1/ota/rollouts", plan, 201);
        return new World(
                tenantA,
                adminToken,
                tenantAdminToken,
                appId,
                release1,
                release2,
                UUID.fromString(first.get("id").asText()),
                UUID.fromString(second.get("id").asText()),
                managerToken,
                developerToken);
    }

    private record World(
            UUID tenantA,
            String adminToken,
            String tenantAdminToken,
            UUID app,
            UUID release1,
            UUID release2,
            UUID bundle1,
            UUID bundle2,
            String managerToken,
            String developerToken) {}

    /** Seeds one hourly rollup row for the NEW bundle -- the S16-09 read model the rules evaluate. */
    private void seedRollup(World world, String metric, double value) {
        Map<String, Object> facts =
                jdbc.queryForMap("SELECT organization_id, tenant_id FROM applications WHERE id = ?", world.app);
        jdbc.update(
                """
                INSERT INTO metric_hourly
                    (organization_id, tenant_id, application_id, release_id, ring_id, metric, bucket_start,
                     value, computed_at, ota_bundle_id)
                VALUES (?, ?, ?, ?, '00000000-0000-0000-0000-000000000000'::uuid, ?, ?, ?, now(), ?)
                """,
                facts.get("organization_id"),
                facts.get("tenant_id"),
                world.app,
                world.release2,
                metric,
                java.sql.Timestamp.from(Instant.now().truncatedTo(ChronoUnit.HOURS)),
                value,
                world.bundle2);
    }

    /** One release-keyed SESSION report (the crash-free watch's family). */
    private void postSessions(World world, UUID releaseId, int sessions, int crashes) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "SESSION");
        event.put("releaseId", releaseId.toString());
        event.put("occurredAt", Instant.now().minus(Duration.ofMinutes(5)).toString());
        event.put("sessions", sessions);
        event.put("crashSessions", crashes);
        event.put("anrSessions", 0);
        ingest(world, List.of(event));
    }

    /** One OTA lifecycle report body against the NEW bundle, carrying its device identity. */
    private Map<String, Object> lifecycle(
            World world, String type, String result, Instant occurredAt, Map<String, Object> payload) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        if (result != null) {
            event.put("result", result);
        }
        event.put("releaseId", world.release2.toString());
        event.put("eventId", UUID.randomUUID().toString());
        event.put("occurredAt", occurredAt.toString());
        event.put("otaBundleId", world.bundle2.toString());
        if (payload != null) {
            event.put("otaPayload", payload);
        }
        return event;
    }

    /** The payload's fleet device identity (S16-08's carrier). */
    private static Map<String, Object> device(String prefix, String device) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("device_id", prefix + "-" + device);
        return payload;
    }

    /** Posts one event batch through the reporting tenant's console standing. */
    private void ingest(World world, List<Map<String, Object>> events) {
        assertThat(post(
                                world.tenantAdminToken,
                                "/api/v1/tenants/" + world.tenantA + "/analytics/events",
                                Map.of("events", events),
                                201)
                        .get("ingested")
                        .asInt())
                .isEqualTo(events.size());
    }

    private UUID createRule(
            World world,
            UUID appId,
            UUID bundleId,
            String metric,
            String comparator,
            double threshold,
            int window,
            String severity,
            int expectedStatus) {
        JsonNode created = post(
                world.managerToken,
                "/api/v1/ota/alert-rules",
                ruleBody(
                        appId,
                        bundleId == null ? null : bundleId.toString(),
                        metric,
                        comparator,
                        threshold,
                        window,
                        severity),
                expectedStatus);
        return UUID.fromString(created.get("id").asText());
    }

    private static Map<String, Object> ruleBody(
            UUID appId,
            String bundleId,
            String metric,
            String comparator,
            Double threshold,
            int window,
            String severity) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", appId.toString());
        if (bundleId != null) {
            body.put("otaBundleId", bundleId);
        }
        body.put("metric", metric);
        body.put("comparator", comparator);
        body.put("threshold", threshold);
        body.put("windowMinutes", window);
        body.put("severity", severity);
        return body;
    }

    /** One signed bundle through the authoring walk (draft, bytes, finalize). */
    private JsonNode authorSignedBundle(String developerToken, UUID appId, String version, String tag, byte[] bytes) {
        Map<String, Object> body = new HashMap<>();
        body.put("applicationId", appId.toString());
        body.put("bundleVersion", version);
        body.put("platformTag", tag);
        body.put("fileName", "bundle-" + version + ".zip");
        JsonNode draft = post(developerToken, "/api/v1/ota/bundles", body, 201);
        client.put()
                .uri(URI.create(draft.get("upload").get("uploadUrl").asText()))
                .header(HttpHeaders.CONTENT_RANGE, "bytes 0-" + (bytes.length - 1) + "/" + bytes.length)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(bytes)
                .exchange((request, response) -> new Recording(
                        response.getStatusCode().value(),
                        response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                        mapper.createObjectNode()));
        return post(
                        developerToken,
                        "/api/v1/ota/bundles/" + draft.get("id").asText() + "/finalize",
                        Map.of("versionId", draft.get("versionId").asText()),
                        201)
                .get("bundle");
    }

    /** One OTA-channel release over the bundle, targeting the owning tenant. */
    private UUID createOtaRelease(String managerToken, UUID appId, JsonNode bundle) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", appId.toString());
        body.put("versionId", bundle.get("versionId").asText());
        body.put("ring", "ota");
        body.put(
                "artifacts",
                List.of(Map.of("platform", "IOS", "artifactId", bundle.get("id").asText())));
        UUID tenantId = jdbc.queryForObject("SELECT tenant_id FROM applications WHERE id = ?", UUID.class, appId);
        body.put("targets", List.of(Map.of("tenantId", tenantId.toString(), "testerGroupIds", List.of())));
        return UUID.fromString(
                post(managerToken, "/api/v1/releases", body, 201).get("id").asText());
    }

    private UUID createTenantApp(String token, UUID tenantId, String name, String bundleId) {
        Map<String, Object> body = new HashMap<>();
        body.put("tenantId", tenantId.toString());
        body.put("name", name);
        body.put("platforms", List.of("IOS"));
        body.put("bundleId", bundleId);
        return UUID.fromString(
                post(token, "/api/v1/applications", body, 201).get("id").asText());
    }

    private UUID createTenant(String token, UUID organizationId, String name, String externalId) {
        JsonNode created = post(
                token,
                "/api/v1/tenants",
                Map.of("organizationId", organizationId.toString(), "name", name, "externalId", externalId),
                201);
        return UUID.fromString(created.get("id").asText());
    }

    /** Creates one organization per test; repeated calls for the same name are a fixture bug. */
    private UUID createOrg(String orgName) {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        return UUID.fromString(post(devToken, "/api/v1/organizations", Map.of("name", orgName), 201)
                .get("id")
                .asText());
    }

    /** Registers a user with the given role into an EXISTING organization; returns their token. */
    private String loginOfOrgRoleIn(UUID orgId, String localPart, String role) {
        String email = localPart + "@example.com";
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
                        "F " + role))
                .retrieve()
                .body(String.class);
        return accessToken(login(email, PASSWORD));
    }

    /** Registers a user, links them as the tenant's TENANT_ADMIN; returns their token. */
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
                        "F Tenant Admin"))
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

    private String login(String email, String password) {
        return client.post()
                .uri("/api/v1/auth/login")
                .body(Map.of("email", email, "password", password))
                .retrieve()
                .body(String.class);
    }

    private String accessToken(String tokensJson) {
        try {
            return mapper.readTree(tokensJson).get("accessToken").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The bearer's subject claim — the user id the tenant membership link needs. */
    private UUID userIdOf(String bearer) {
        String claims = bearer.split("\\.")[1];
        try {
            return UUID.fromString(mapper.readTree(Base64.getUrlDecoder().decode(claims))
                    .get("sub")
                    .asString());
        } catch (Exception e) {
            throw new IllegalStateException("could not decode the token", e);
        }
    }

    /** One authenticated GET, asserting the status and returning the body. */
    private JsonNode get(String bearer, String uri, int expectedStatus) {
        Recording recording = exchange(HttpMethod.GET, uri, bearer);
        assertThat(recording.status()).as("GET %s -> %s", uri, recording.body()).isEqualTo(expectedStatus);
        return recording.body();
    }

    private JsonNode post(String bearer, String uri, Map<String, Object> body, Integer expectedStatus) {
        Recording recording = exchange(HttpMethod.POST, uri, bearer, body);
        if (expectedStatus != null) {
            assertThat(recording.status())
                    .as("POST %s -> %s", uri, recording.body())
                    .isEqualTo(expectedStatus);
        }
        return recording.body();
    }

    private JsonNode put(String bearer, String uri, Map<String, Object> body, int expectedStatus) {
        Recording recording = exchange(HttpMethod.PUT, uri, bearer, body);
        assertThat(recording.status()).as("PUT %s -> %s", uri, recording.body()).isEqualTo(expectedStatus);
        return recording.body();
    }

    private void delete(String bearer, String uri, int expectedStatus) {
        Recording recording = exchange(HttpMethod.DELETE, uri, bearer);
        assertThat(recording.status())
                .as("DELETE %s -> %s", uri, recording.body())
                .isEqualTo(expectedStatus);
    }

    private Recording exchange(HttpMethod method, String uri, String bearer) {
        return exchange(method, uri, bearer, null);
    }

    private Recording exchange(HttpMethod method, String uri, String bearer, Map<String, Object> body) {
        RestClient.RequestBodySpec spec = client.method(method).uri(URI.create(uri));
        if (bearer != null) {
            spec = spec.headers(headers -> headers.setBearerAuth(bearer));
        }
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((request, response) -> {
            byte[] bytes = response.getBody().readAllBytes();
            return new Recording(
                    response.getStatusCode().value(),
                    response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                    bytes.length == 0 ? mapper.createObjectNode() : mapper.readTree(bytes));
        });
    }

    private static byte[] payload(int size) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) ('a' + (i % 26));
        }
        return bytes;
    }

    private record Recording(int status, String contentType, JsonNode body) {

        Recording expect(int expectedStatus) {
            assertThat(status).as("HTTP status for %s", body.toString()).isEqualTo(expectedStatus);
            return this;
        }
    }
}
