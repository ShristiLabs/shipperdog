package com.shipperdog.api.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.shipperdog.api.identity.AuditCapture;
import java.time.Duration;
import java.time.Instant;
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
 * The tester telemetry SDK's end-to-end acceptance flow (issue #72,
 * S15-01, FR-029, PRD 37): the download issuance mints the telemetry
 * token (the SDK's ONLY configuration surface), the token
 * authenticates event batches against the frozen events contract
 * (spoofing and misbinding refused), the client event id makes
 * at-least-once delivery idempotent by (device, release, event id),
 * and the metrics the FR-029 dictionary serves — and the crash-free
 * threshold gate resolves through its seam — flip from NO_DATA to
 * REAL device data, exactly the story's activation criterion.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef",
            "shipperdog.analytics.rollup.enabled=false",
            "shipperdog.artifact.storage.host=http://localhost:4443"
        })
@Testcontainers
@Import(AuditCapture.class)
class TelemetrySdkIntegrationTest {

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

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        audit.reset();
    }

    // -----------------------------------------------------------------------
    // AC1/AC2: the token mints at the download, feeds the metrics, and
    // the crash-free gate evaluates on the SDK's real data
    // -----------------------------------------------------------------------

    @Test
    void tokenAuthenticatedEventsFeedMetricsAndFlipTheCrashFreeGateOnRealData() throws Exception {
        World world = world("sdk-e2e");
        grantExplicitVersion(world.tenantAdminToken, world.tenantA, world.testerId, world.app, world.version);

        // The download issuance (the SDK's configuration surface): the
        // telemetry block rides the ALLOW beside the URL, bound to the
        // downloading tester, their membership tenant, and the release
        // of the downloaded version.
        JsonNode issued = get(
                world.testerToken,
                "/api/v1/artifacts/" + world.androidArtifact + "/download?version_id=" + world.version + "&tenant_id="
                        + world.tenantA,
                200);
        assertThat(issued.get("url").asString()).isNotBlank();
        JsonNode telemetry = issued.get("telemetry");
        assertThat(telemetry)
                .as("the telemetry block mints for the paired download")
                .isNotNull();
        assertThat(telemetry.get("token").asString()).startsWith("sdt1.");
        assertThat(UUID.fromString(telemetry.get("tenantId").asString())).isEqualTo(world.tenantA);
        assertThat(UUID.fromString(telemetry.get("releaseId").asString())).isEqualTo(world.releaseId);
        assertThat(UUID.fromString(telemetry.get("versionId").asString())).isEqualTo(world.version);
        assertThat(Instant.parse(telemetry.get("expiresAt").asString())).isAfter(Instant.now());
        String token = telemetry.get("token").asString();
        String ingest = "/api/v1/tenants/" + world.tenantA + "/analytics/events";

        // The SDK's first-launch shape: INSTALL then a session summary
        // — every event carrying its client event id (the dedup key).
        String installId = UUID.randomUUID().toString();
        String sessionId = UUID.randomUUID().toString();
        JsonNode receipt = post(
                token,
                ingest,
                Map.of(
                        "events",
                        List.of(
                                sdkEvent(world, "INSTALL", "SUCCEEDED", installId),
                                sdkEvent(world, "SESSION", null, sessionId, 100, 1, 0))),
                201);
        assertThat(receipt.get("ingested").asInt()).isEqualTo(2);
        assertThat(receipt.get("deduplicated").asInt()).isZero();

        // The metrics dictionary answers real device data (the
        // NO_DATA era ends where the SDK lands).
        JsonNode metrics = get(
                world.managerToken,
                "/api/v1/applications/" + world.app + "/releases/" + world.releaseId + "/metrics",
                200);
        Map<String, JsonNode> byMetric = new HashMap<>();
        metrics.get("metrics").forEach(entry -> byMetric.put(entry.get("metric").asString(), entry));
        assertThat(byMetric.get("INSTALLATIONS").get("value").asDouble()).isEqualTo(1.0);
        assertThat(byMetric.get("CRASH_FREE_SESSIONS").get("value").asDouble()).isCloseTo(0.99, within(1e-9));

        // The crash-free threshold gate on REAL data (the story's
        // activation): 99.0% observed against a 99.5% threshold — the
        // crossing BLOCKS on the evidence, the fail-closed era's
        // opposite failure mode, a data-driven refusal.
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
        JsonNode blocked = post(
                world.managerToken,
                "/api/v1/releases/" + world.releaseId + "/transitions",
                Map.of("applicationId", world.app.toString(), "to", "READY"),
                422);
        assertThat(blocked.get("errors").get("ReleaseGatePolicy").asString())
                .contains("crash-free-threshold")
                .doesNotContain("no analytics data yet");

        // The gate's observed value is the SDK's own arithmetic.
        JsonNode gates = get(
                world.managerToken, "/api/v1/releases/" + world.releaseId + "/gates?applicationId=" + world.app, 200);
        JsonNode crashFree = gateOf(gates, "crash-free-threshold");
        assertThat(crashFree.get("status").asString()).isEqualTo("FAILED");
        assertThat(crashFree.get("observedValue").asDouble()).isCloseTo(99.0, within(0.05));

        // The rollup pipeline folds the same events (the backfill
        // trigger's window): the hourly grain carries the sessions.
        post(
                world.managerToken,
                "/api/v1/applications/" + world.app + "/metrics/rollups",
                Map.of(
                        "from",
                        Instant.now().minus(Duration.ofHours(3)).toString(),
                        "to",
                        Instant.now().plus(Duration.ofMinutes(5)).toString()),
                200);
        Double sessionsRolled = jdbc.queryForObject(
                "SELECT sum(value) FROM metric_hourly WHERE release_id = ? AND metric = 'INSTALLATIONS'",
                Double.class,
                world.releaseId);
        assertThat(sessionsRolled).isCloseTo(1.0, within(1e-9));

        // The audit carries the batch's shape with the tester as the
        // actor (the token IS the reporter).
        var ingested = audit.eventsOfType(com.shipperdog.api.audit.AuditEventType.ANALYTICS_EVENTS_INGESTED);
        assertThat(ingested).hasSize(1);
        assertThat(ingested.get(0).userId()).isEqualTo(world.testerUserId);
    }

    // -----------------------------------------------------------------------
    // AC3: events authenticate as the DOWNLOADING tester — misbinding
    // and spoofing legs all refused
    // -----------------------------------------------------------------------

    @Test
    void misboundAndSpoofedTokensAreRefused() throws Exception {
        World world = world("sdk-authz");
        grantExplicitVersion(world.tenantAdminToken, world.tenantA, world.testerId, world.app, world.version);
        String token = tokenOf(world, world.tenantA, null);
        String ingest = "/api/v1/tenants/" + world.tenantA + "/analytics/events";

        // A tampered token never authenticates (the uniform 401).
        post(
                token.substring(0, token.length() - 4) + "AAAA",
                ingest,
                Map.of(
                        "events",
                        List.of(sdkEvent(
                                world, "INSTALL", "SUCCEEDED", UUID.randomUUID().toString()))),
                401);

        // The token's tenant binding: the SAME token on tenant B's
        // path is the concealed miss — the reporter is the token's
        // tenant, never the path's (and tenant B is not targeted by
        // this release either way; both refusals are the same 404).
        post(
                token,
                "/api/v1/tenants/" + world.tenantB + "/analytics/events",
                Map.of(
                        "events",
                        List.of(sdkEvent(
                                world, "INSTALL", "SUCCEEDED", UUID.randomUUID().toString()))),
                404);

        // The token's release binding: an event naming ANY other
        // release — even one the tenant is targeted by — is the same
        // concealed miss.
        UUID otherRelease = createRelease(world, "1.1.0");
        Map<String, Object> offToken =
                sdkEvent(world, "INSTALL", "SUCCEEDED", UUID.randomUUID().toString());
        offToken.put("releaseId", otherRelease.toString());
        post(token, ingest, Map.of("events", List.of(offToken)), 404);

        // Another tester's device in the batch: the same field-addressed
        // refusal as a foreign device (the reporter cannot attribute
        // events to a device the downloading tester does not own).
        UUID foreignTesterDevice = registerDevice(world, "sdk-foreign-device", otherTester(world, "sdk-other"));
        Map<String, Object> spoofed =
                sdkEvent(world, "INSTALL", "SUCCEEDED", UUID.randomUUID().toString());
        spoofed.put("deviceId", foreignTesterDevice.toString());
        assertThat(post(token, ingest, Map.of("events", List.of(spoofed)), 400)
                        .get("errors")
                        .get("device_id")
                        .asString())
                .contains("device did not resolve");

        // A device-bound token accepts only its device, and the
        // device's own status switch revokes it (the two-switches
        // rule): DEACTIVATED is the same field refusal.
        UUID ownDevice = registerDevice(world, "sdk-own-device", world.testerId);
        String bound = tokenOf(world, world.tenantA, ownDevice);
        Map<String, Object> wrongDevice =
                sdkEvent(world, "INSTALL", "SUCCEEDED", UUID.randomUUID().toString());
        wrongDevice.put("deviceId", foreignTesterDevice.toString());
        post(bound, ingest, Map.of("events", List.of(wrongDevice)), 400);
        Map<String, Object> rightDevice =
                sdkEvent(world, "INSTALL", "SUCCEEDED", UUID.randomUUID().toString());
        rightDevice.put("deviceId", ownDevice.toString());
        post(bound, ingest, Map.of("events", List.of(rightDevice)), 201);
        put(
                world.tenantAdminToken,
                "/api/v1/tenants/" + world.tenantA + "/devices/" + ownDevice + "/status",
                Map.of("status", "DEACTIVATED"),
                200);
        Map<String, Object> afterPark =
                sdkEvent(world, "INSTALL", "SUCCEEDED", UUID.randomUUID().toString());
        afterPark.put("deviceId", ownDevice.toString());
        assertThat(post(bound, ingest, Map.of("events", List.of(afterPark)), 400)
                        .get("errors")
                        .get("device_id")
                        .asString())
                .contains("device did not resolve");
    }

    // -----------------------------------------------------------------------
    // AC3's missing leg (review round 1): a token whose tester record
    // no longer resolves may not name ANY device
    // -----------------------------------------------------------------------

    @Test
    void aDeletedTesterSTokenCannotNameAnyDevice() throws Exception {
        World world = world("sdk-gone");
        grantExplicitVersion(world.tenantAdminToken, world.tenantA, world.testerId, world.app, world.version);
        String token = tokenOf(world, world.tenantA, null);
        String ingest = "/api/v1/tenants/" + world.tenantA + "/analytics/events";
        UUID foreignDevice = registerDevice(world, "sdk-gone-device", otherTester(world, "sdk-gone-other"));

        // The tester record dies while the 90-day unbound token lives:
        // memberships, grants, invitation, then the tester itself.
        jdbc.update("DELETE FROM user_invitations WHERE tester_id = ?", world.testerId);
        jdbc.update("DELETE FROM tenant_tester_memberships WHERE tester_id = ?", world.testerId);
        jdbc.update("DELETE FROM version_access WHERE tester_id = ?", world.testerId);
        jdbc.update("DELETE FROM testers WHERE id = ?", world.testerId);

        // The ownership narrowing must REFUSE rather than silently
        // widen: without a resolvable tester the token may not name
        // another tester's ACTIVE device (or any device) — the same
        // field-addressed refusal, never a landed row the gone token
        // attributed to someone else.
        Map<String, Object> named =
                sdkEvent(world, "INSTALL", "SUCCEEDED", UUID.randomUUID().toString());
        named.put("deviceId", foreignDevice.toString());
        assertThat(post(token, ingest, Map.of("events", List.of(named)), 400)
                        .get("errors")
                        .get("device_id")
                        .asString())
                .contains("device did not resolve");

        // Device-less events still land: there is no device identity to
        // spoof, and the token's own bindings (tenant, release) still
        // hold — the guard refuses NAMING, not the whole credential.
        assertThat(post(
                                token,
                                ingest,
                                Map.of(
                                        "events",
                                        List.of(sdkEvent(
                                                world,
                                                "INSTALL",
                                                "SUCCEEDED",
                                                UUID.randomUUID().toString()))),
                                201)
                        .get("ingested")
                        .asInt())
                .isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // AC4: offline batching's at-least-once retries are idempotent by
    // (device, release, event id); batches without ids keep the
    // contract's original semantics
    // -----------------------------------------------------------------------

    @Test
    void retriedBatchesDeduplicateByDeviceReleaseAndEventId() throws Exception {
        World world = world("sdk-dedup");
        grantExplicitVersion(world.tenantAdminToken, world.tenantA, world.testerId, world.app, world.version);
        String token = tokenOf(world, world.tenantA, null);
        String ingest = "/api/v1/tenants/" + world.tenantA + "/analytics/events";

        // The offline queue's retry: the SAME batch twice (the queue's
        // whole failure mode) lands once; the receipt says so.
        String firstId = UUID.randomUUID().toString();
        String secondId = UUID.randomUUID().toString();
        Map<String, Object> batch = Map.of(
                "events",
                List.of(
                        sdkEvent(world, "INSTALL", "SUCCEEDED", firstId),
                        sdkEvent(world, "SESSION", null, secondId, 50, 0, 0)));
        assertThat(post(token, ingest, batch, 201).get("ingested").asInt()).isEqualTo(2);
        JsonNode retry = post(token, ingest, batch, 201);
        assertThat(retry.get("ingested").asInt()).isZero();
        assertThat(retry.get("deduplicated").asInt()).isEqualTo(2);
        assertThat(eventCount(world)).isEqualTo(2);

        // A partial retry (half the batch already landed): the new half
        // lands, the known half dedups.
        String thirdId = UUID.randomUUID().toString();
        Map<String, Object> partial = Map.of(
                "events",
                List.of(sdkEvent(world, "INSTALL", "FAILED", firstId), sdkEvent(world, "UNINSTALL", null, thirdId)));
        JsonNode mixed = post(token, ingest, partial, 201);
        assertThat(mixed.get("ingested").asInt()).isEqualTo(1);
        assertThat(mixed.get("deduplicated").asInt()).isEqualTo(1);
        assertThat(eventCount(world)).isEqualTo(3);

        // The metrics stay exact through the retries: INSTALLATIONS
        // counts LANDED events — the retry's FAILED report carried the
        // SAME client id as the landed SUCCEEDED one, so it deduped;
        // the count is 1, never the retry-doubled 2.
        JsonNode metrics = get(
                world.managerToken,
                "/api/v1/applications/" + world.app + "/releases/" + world.releaseId + "/metrics",
                200);
        Map<String, JsonNode> byMetric = new HashMap<>();
        metrics.get("metrics").forEach(entry -> byMetric.put(entry.get("metric").asString(), entry));
        assertThat(byMetric.get("INSTALLATIONS").get("value").asDouble()).isEqualTo(1.0);

        // Events WITHOUT client ids keep the original contract: a
        // retried batch lands twice (the count metrics' documented
        // hazard the id exists to fix).
        Map<String, Object> idless = Map.of("events", List.of(rawEvent(world, "INSTALL", "SUCCEEDED")));
        post(token, ingest, idless, 201);
        post(token, ingest, idless, 201);
        assertThat(eventCount(world)).isEqualTo(5);

        // A malformed client id is a field-addressed refusal.
        Map<String, Object> malformed = sdkEvent(world, "INSTALL", "SUCCEEDED", "not-a-uuid");
        assertThat(post(token, ingest, Map.of("events", List.of(malformed)), 400)
                        .get("errors")
                        .get("event_id")
                        .asString())
                .contains("UUID");
    }

    // -----------------------------------------------------------------------
    // The mint's own legs: additive evidence, never a download gate
    // -----------------------------------------------------------------------

    @Test
    void telemetryIsAbsentWhenThePairingDoesNotResolveAndDevicesRefuseExplicitly() throws Exception {
        World world = world("sdk-mint");
        grantExplicitVersion(world.tenantAdminToken, world.tenantA, world.testerId, world.app, world.version);

        // No tenant_id on a tenant-owned application: the version's
        // owning tenant IS the reporter tenant — the block mints from
        // the fallback binding.
        JsonNode unbound = get(
                world.testerToken,
                "/api/v1/artifacts/" + world.androidArtifact + "/download?version_id=" + world.version,
                200);
        assertThat(unbound.get("telemetry")).isNotNull();
        assertThat(UUID.fromString(unbound.get("telemetry").get("tenantId").asString()))
                .isEqualTo(world.tenantA);

        // The console persona (no tester record): no telemetry block,
        // the same URL, the same counters.
        JsonNode console = get(
                world.managerToken,
                "/api/v1/artifacts/" + world.androidArtifact + "/download?version_id=" + world.version + "&tenant_id="
                        + world.tenantA,
                200);
        assertThat(console.path("telemetry").isNull()).isTrue();

        // A tenant the release does not target: no block, no refusal.
        UUID thirdTenant = createTenant(world.adminToken, world.org, "SDK Untargeted Bank", "sdk-untargeted");
        JsonNode untargeted = get(
                world.testerToken,
                "/api/v1/artifacts/" + world.androidArtifact + "/download?version_id=" + world.version + "&tenant_id="
                        + thirdTenant,
                200);
        assertThat(untargeted.path("telemetry").isNull()).isTrue();

        // An explicit device pairing that does not resolve as the
        // tester's own ACTIVE device is the field-addressed refusal —
        // a typo'd pairing must not mint a dead token silently.
        UUID otherDevice = registerDevice(world, "sdk-mint-device", otherTester(world, "sdk-mint-other"));
        get(
                world.testerToken,
                "/api/v1/artifacts/" + world.androidArtifact + "/download?version_id=" + world.version + "&tenant_id="
                        + world.tenantA + "&device_id=" + otherDevice,
                400);
    }

    // -----------------------------------------------------------------------
    // Fixtures: the world and its facts
    // -----------------------------------------------------------------------

    private static final class World {
        UUID org;
        String adminToken;
        String ownerToken;
        String managerToken;
        String tenantAdminToken;
        UUID tenantA;
        UUID tenantB;
        UUID app;
        UUID version;
        UUID iosArtifact;
        UUID androidArtifact;
        UUID releaseId;
        UUID testerId;
        UUID testerUserId;
        String testerToken;
    }

    /** An organization, two tenants, a released shared application targeting both, one linked tester with a login. */
    private World world(String slug) throws Exception {
        World world = new World();
        world.org = createOrg("SDK " + slug + " Corp");
        world.adminToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        world.ownerToken = loginOfOrgRoleIn(world.org, slug + "-owner", "APP_OWNER");
        world.managerToken = loginOfOrgRoleIn(world.org, slug + "-manager", "RELEASE_MANAGER");
        world.tenantA = createTenant(world.adminToken, world.org, "SDK " + slug + " Bank", "sdk-" + slug + "-a");
        world.tenantB = createTenant(world.adminToken, world.org, "SDK " + slug + " Bank B", "sdk-" + slug + "-b");
        world.tenantAdminToken = loginOfTenantAdminRoleIn(world.org, world.tenantA, slug + "-tenant-admin");
        world.app = createTenantApp(
                world.adminToken, world.tenantA, "SDK " + slug + " Banking", "com." + slug.replace("-", "") + ".sdk");
        world.version = createVersion(world.ownerToken, world.app, "1.0.0");
        world.iosArtifact = insertArtifact(world.version, "IOS", 1);
        world.androidArtifact = insertArtifact(world.version, "ANDROID", 2);
        world.releaseId = createRelease(world, "1.0.0");

        world.testerId = linkedTester(world, slug + "-tester");
        world.testerUserId =
                jdbc.queryForObject("SELECT user_id FROM testers WHERE id = ?", UUID.class, world.testerId);
        world.testerToken = loginOfTester(world, slug + "-tester");
        return world;
    }

    private UUID createRelease(World world, String versionName) {
        boolean first = versionName.equals("1.0.0");
        UUID versionId = first ? world.version : createVersion(world.ownerToken, world.app, versionName);
        UUID ios = first ? world.iosArtifact : insertArtifact(versionId, "IOS", 11);
        UUID android = first ? world.androidArtifact : insertArtifact(versionId, "ANDROID", 12);
        Map<String, Object> release = new LinkedHashMap<>();
        release.put("applicationId", world.app.toString());
        release.put("versionId", versionId.toString());
        release.put("ring", "qa");
        release.put(
                "artifacts",
                List.of(
                        Map.of("platform", "IOS", "artifactId", ios.toString()),
                        Map.of("platform", "ANDROID", "artifactId", android.toString())));
        release.put("targets", List.of(Map.of("tenantId", world.tenantA.toString(), "testerGroupIds", List.of())));
        return UUID.fromString(post(world.managerToken, "/api/v1/releases", release, 201)
                .get("id")
                .asText());
    }

    /** The telemetry token of one download (the SDK's configuration shape). */
    private String tokenOf(World world, UUID tenantId, UUID deviceId) {
        JsonNode issued = get(
                world.testerToken,
                "/api/v1/artifacts/" + world.androidArtifact + "/download?version_id=" + world.version + "&tenant_id="
                        + tenantId + (deviceId == null ? "" : "&device_id=" + deviceId),
                200);
        JsonNode telemetry = issued.get("telemetry");
        assertThat(telemetry)
                .as("the telemetry block mints for the paired download")
                .isNotNull();
        return telemetry.get("token").asString();
    }

    private void grantExplicitVersion(String token, UUID tenantId, UUID testerId, UUID applicationId, UUID versionId) {
        post(
                token,
                "/api/v1/tenants/" + tenantId + "/authorization-grants",
                Map.of(
                        "testerId", testerId.toString(),
                        "applicationId", applicationId.toString(),
                        "accessType", "EXPLICIT_VERSION",
                        "versionId", versionId.toString(),
                        "reason", "sdk e2e fixture grant"),
                201);
    }

    private UUID registerDevice(World world, String identifier, UUID testerId) {
        return UUID.fromString(post(
                        world.tenantAdminToken,
                        "/api/v1/tenants/" + world.tenantA + "/devices",
                        Map.of(
                                "testerId", testerId.toString(),
                                "platform", "ANDROID",
                                "model", "Pixel 9",
                                "osVersion", "16",
                                "identifier", identifier),
                        201)
                .get("id")
                .asText());
    }

    private UUID otherTester(World world, String slug) {
        UUID testerId = linkedTester(world, slug);
        return testerId;
    }

    private static Map<String, Object> sdkEvent(World world, String type, String result, String eventId) {
        Map<String, Object> event = rawEvent(world, type, result);
        event.put("eventId", eventId);
        return event;
    }

    private static Map<String, Object> sdkEvent(
            World world, String type, String result, String eventId, int sessions, int crash, int anr) {
        Map<String, Object> event = sdkEvent(world, type, result, eventId);
        event.put("sessions", sessions);
        event.put("crashSessions", crash);
        event.put("anrSessions", anr);
        return event;
    }

    private static Map<String, Object> rawEvent(World world, String type, String result) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.put("releaseId", world.releaseId.toString());
        if (result != null) {
            event.put("result", result);
        }
        event.put("occurredAt", Instant.now().toString());
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
    // HTTP helpers (the analytics suite's shapes)
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

    private JsonNode put(String bearer, String uri, Map<String, Object> body, int expectedStatus) {
        Exchange recorded = client.put()
                .uri(uri)
                .headers(headers -> headers.setBearerAuth(bearer))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) -> exchangeOf(
                        response.getStatusCode().value(),
                        response.getHeaders().getFirst(org.springframework.http.HttpHeaders.CONTENT_TYPE),
                        response.getBody().readAllBytes()));
        assertThat(recorded.status()).as("PUT %s -> %d", uri, expectedStatus).isEqualTo(expectedStatus);
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

    // -----------------------------------------------------------------------
    // Provisioning (the analytics suite's shapes)
    // -----------------------------------------------------------------------

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

    private UUID createTenantApp(String token, UUID tenantId, String name, String packageId) {
        Map<String, Object> body = new HashMap<>();
        body.put("tenantId", tenantId.toString());
        body.put("name", name);
        body.put("platforms", List.of("IOS", "ANDROID"));
        body.put("bundleId", packageId);
        body.put("packageName", packageId);
        return UUID.fromString(
                post(token, "/api/v1/applications", body, 201).get("id").asText());
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
                        "SDK " + role))
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
                        "SDK Tenant Admin"))
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
                Map.of("organizationId", world.org.toString(), "email", email, "fullName", "SDK Tester " + slug),
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
                        "SDK Tester " + slug))
                .retrieve()
                .body(String.class);
        post(
                world.adminToken,
                "/api/v1/tenants/" + world.tenantA + "/testers",
                Map.of("testerId", invited.get("id").asText()),
                201);
        return UUID.fromString(invited.get("id").asText());
    }

    private String loginOfTester(World world, String slug) {
        return accessToken(login(slug + "@example.com", PASSWORD));
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
