package com.shipperdog.api.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.net.URI;
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
 * The S16-10 acceptance flow (issue #172): the OTA dashboard endpoints
 * serve the S16-09 query layer over HTTP — the funnel document (stage
 * counts, failure-reason breakdown, adoption curve, ring filter) and
 * the bundle-health crash-rate comparison — under the tenant
 * dashboard's own permission ({@code tenant:dashboard:read}: the
 * tenant admin's grain, the owning organization's overlay), with every
 * miss concealing as the problem+json 404 the platform's envelope
 * renders. The fixture walks the same funnel the S16-09 suite seeds —
 * two released bundles, the fleet's lifecycle reports, the
 * server-side check rows — so the served numbers reconcile with that
 * suite's service-level pins assertion for assertion.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef",
            "shipperdog.analytics.rollup.enabled=false"
        })
@Testcontainers
class OtaDashboardEndpointsIntegrationTest {

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

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    // -----------------------------------------------------------------------
    // AC1: the funnel endpoint — stages, failure breakdown, adoption
    // curve, and the ring filter's seam, all over HTTP
    // -----------------------------------------------------------------------

    @Test
    void funnelEndpointServesStagesFailuresAdoptionAndTheRingFilter() {
        World world = world("e-funnel");
        seedFunnel(world, "device-e");

        JsonNode funnel = get(
                world.tenantAdminToken,
                "/api/v1/tenants/" + world.tenantA + "/dashboard/ota/bundles/" + world.bundle2 + "/funnel",
                200);
        assertThat(funnel.get("bundleId").asString()).isEqualTo(world.bundle2.toString());
        assertThat(funnel.get("bundleVersion").asString()).isEqualTo("1.4.2");
        assertThat(funnel.get("applicationId").asString()).isEqualTo(world.app.toString());
        assertThat(funnel.get("releaseId").asString()).isEqualTo(world.release2.toString());
        assertThat(funnel.get("releaseRef").asString()).startsWith("R-");
        assertThat(funnel.get("stages").get("checks").asLong()).isEqualTo(5);
        assertThat(funnel.get("stages").get("seen").asLong()).isEqualTo(5);
        assertThat(funnel.get("stages").get("downloads").asLong()).isEqualTo(4);
        assertThat(funnel.get("stages").get("applied").asLong()).isEqualTo(3);
        assertThat(funnel.get("stages").get("failed").asLong()).isEqualTo(1);
        assertThat(funnel.get("stages").get("rolledBack").asLong()).isEqualTo(1);

        assertThat(funnel.get("failures")).hasSize(1);
        assertThat(funnel.get("failures").get(0).get("reason").asString()).isEqualTo("checksum_mismatch");
        assertThat(funnel.get("failures").get(0).get("count").asLong()).isEqualTo(1);

        // The adoption curve over time: day one the fleet's 2-of-4
        // observed devices applied, day two 3-of-5 — cumulative and
        // monotone from the facts, exactly the S16-09 pin.
        assertThat(funnel.get("adoption")).hasSize(2);
        JsonNode first = funnel.get("adoption").get(0);
        JsonNode second = funnel.get("adoption").get(1);
        assertThat(first.get("seenDevices").asLong()).isEqualTo(4);
        assertThat(first.get("appliedDevices").asLong()).isEqualTo(2);
        assertThat(first.get("adoptionRate").asDouble()).isCloseTo(0.5, within(1e-9));
        assertThat(second.get("seenDevices").asLong()).isEqualTo(5);
        assertThat(second.get("appliedDevices").asLong()).isEqualTo(3);
        assertThat(second.get("adoptionRate").asDouble()).isCloseTo(0.6, within(1e-9));

        // The ring filter narrows to the events that occurred while
        // the release occupied the named ring: the funnel's own ring
        // carries everything, a ring it never occupied nothing.
        JsonNode onOtaRing = get(
                world.tenantAdminToken,
                "/api/v1/tenants/" + world.tenantA + "/dashboard/ota/bundles/" + world.bundle2 + "/funnel?ring=ota",
                200);
        assertThat(onOtaRing.get("stages").get("seen").asLong()).isEqualTo(5);
        assertThat(onOtaRing.get("adoption")).hasSize(2);
        assertThat(onOtaRing.get("failures")).hasSize(1);
        JsonNode onQaRing = get(
                world.tenantAdminToken,
                "/api/v1/tenants/" + world.tenantA + "/dashboard/ota/bundles/" + world.bundle2 + "/funnel?ring=qa",
                200);
        assertThat(onQaRing.get("stages").get("seen").asLong()).isZero();
        assertThat(onQaRing.get("stages").get("checks").asLong()).isZero();
        assertThat(onQaRing.get("adoption")).isEmpty();
        assertThat(onQaRing.get("failures")).isEmpty();

        // An unknown ring code is refused as problem+json, never
        // silently empty.
        Recording refused = exchange(
                HttpMethod.GET,
                "/api/v1/tenants/" + world.tenantA + "/dashboard/ota/bundles/" + world.bundle2
                        + "/funnel?ring=not-a-ring",
                world.tenantAdminToken);
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.contentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(refused.body().get("code").asString()).isEqualTo("INVALID_REQUEST");
        assertThat(refused.body().get("errors").get("ring").asString()).contains("unknown ring");

        // The rollback crossing detaches the bundle — attribution is
        // history, so the unfiltered funnel keeps the whole incident
        // evidence while the ring-filtered read answers zero.
        post(
                world.managerToken,
                "/api/v1/releases/" + world.release2 + "/transitions",
                Map.of(
                        "applicationId",
                        world.app.toString(),
                        "to",
                        "ROLLED_BACK",
                        "reason",
                        "crash loop observed in the fleet"),
                200);
        JsonNode detached = get(
                world.tenantAdminToken,
                "/api/v1/tenants/" + world.tenantA + "/dashboard/ota/bundles/" + world.bundle2 + "/funnel",
                200);
        assertThat(detached.get("stages").get("checks").asLong()).isEqualTo(5);
        assertThat(detached.get("stages").get("seen").asLong()).isEqualTo(5);
        assertThat(detached.get("stages").get("applied").asLong()).isEqualTo(3);
        assertThat(detached.get("failures")).hasSize(1);
        assertThat(detached.get("adoption")).hasSize(2);
        assertThat(detached.get("adoption").get(1).get("adoptionRate").asDouble())
                .isCloseTo(0.6, within(1e-9));
        JsonNode detachedOnRing = get(
                world.tenantAdminToken,
                "/api/v1/tenants/" + world.tenantA + "/dashboard/ota/bundles/" + world.bundle2 + "/funnel?ring=ota",
                200);
        assertThat(detachedOnRing.get("stages").get("seen").asLong()).isZero();
    }

    // -----------------------------------------------------------------------
    // AC1: the health endpoint — current-vs-new crash rates, the
    // NO_DATA doctrine, over HTTP
    // -----------------------------------------------------------------------

    @Test
    void healthEndpointComparesCurrentAndNewCrashRates() {
        World world = world("e-health");
        postSessions(world, world.release2, 100, 1);
        postSessions(world, world.release1, 200, 10);

        JsonNode health = get(
                world.tenantAdminToken,
                "/api/v1/tenants/" + world.tenantA + "/dashboard/ota/bundles/" + world.bundle2 + "/health",
                200);
        assertThat(health.get("newBundle").get("bundleId").asString()).isEqualTo(world.bundle2.toString());
        assertThat(health.get("newBundle").get("releaseId").asString()).isEqualTo(world.release2.toString());
        assertThat(health.get("newBundle").get("releaseRef").asString()).startsWith("R-");
        assertThat(health.get("newBundle").get("sessions").asLong()).isEqualTo(100);
        assertThat(health.get("newBundle").get("crashSessions").asLong()).isEqualTo(1);
        assertThat(health.get("newBundle").get("crashFreeRate").asDouble()).isCloseTo(0.99, within(1e-9));

        assertThat(health.get("currentBundle").get("bundleId").asString()).isEqualTo(world.bundle1.toString());
        assertThat(health.get("currentBundle").get("sessions").asLong()).isEqualTo(200);
        assertThat(health.get("currentBundle").get("crashSessions").asLong()).isEqualTo(10);
        assertThat(health.get("currentBundle").get("crashFreeRate").asDouble()).isCloseTo(0.95, within(1e-9));
        assertThat(health.get("crashFreeRateDelta").asDouble()).isCloseTo(0.04, within(1e-9));

        // A bundle with no sessions yet answers NO_DATA on its side —
        // never a zero — and the delta stays unevidenced.
        JsonNode bare = authorSignedBundle(world.developerToken, world.app, "1.5.0", "ios", payload(256));
        JsonNode bareHealth = get(
                world.tenantAdminToken,
                "/api/v1/tenants/" + world.tenantA + "/dashboard/ota/bundles/"
                        + bare.get("id").asText() + "/health",
                200);
        assertThat(bareHealth.get("newBundle").get("crashFreeRate").isNull()).isTrue();
        assertThat(bareHealth.get("newBundle").get("sessions").asLong()).isZero();
        assertThat(bareHealth.get("newBundle").get("releaseId").isNull()).isTrue();
        assertThat(bareHealth.get("newBundle").get("releaseRef").isNull()).isTrue();
        assertThat(bareHealth.get("currentBundle").get("bundleId").asString()).isEqualTo(world.bundle2.toString());
        assertThat(bareHealth.get("crashFreeRateDelta").isNull()).isTrue();
    }

    // -----------------------------------------------------------------------
    // AC2/AC3: the tenant seam and the permission — the concealed miss
    // in the platform's problem+json envelope, the RBAC vocabulary
    // -----------------------------------------------------------------------

    @Test
    void endpointsConcealForeignUnknownAndUnauthorizedAnswers() {
        World world = world("e-scope");

        // The owning organization's overlay reads the funnel and the
        // health comparison (the org admin's standing above the
        // tenant's own admin).
        assertThat(get(
                                world.adminToken,
                                "/api/v1/tenants/" + world.tenantA + "/dashboard/ota/bundles/" + world.bundle2
                                        + "/funnel",
                                200)
                        .get("bundleVersion")
                        .asString())
                .isEqualTo("1.4.2");
        get(
                world.adminToken,
                "/api/v1/tenants/" + world.tenantA + "/dashboard/ota/bundles/" + world.bundle2 + "/health",
                200);

        // The foreign tenant (no catalog standing on the bundle's
        // application), the unknown bundle, and the unknown tenant all
        // conceal — the denial is problem+json, and the foreign
        // concealment is shape-identical to the genuine miss.
        Recording foreignBundle = exchange(
                HttpMethod.GET,
                "/api/v1/tenants/" + world.tenantB + "/dashboard/ota/bundles/" + world.bundle2 + "/funnel",
                world.adminToken);
        Recording unknownBundle = exchange(
                HttpMethod.GET,
                "/api/v1/tenants/" + world.tenantA + "/dashboard/ota/bundles/" + UUID.randomUUID() + "/funnel",
                world.adminToken);
        Recording unknownTenant = exchange(
                HttpMethod.GET,
                "/api/v1/tenants/" + UUID.randomUUID() + "/dashboard/ota/bundles/" + world.bundle2 + "/funnel",
                world.adminToken);
        assertThat(foreignBundle.status()).isEqualTo(404);
        assertThat(unknownBundle.status()).isEqualTo(404);
        assertThat(unknownTenant.status()).isEqualTo(404);
        assertThat(foreignBundle.contentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(unknownBundle.contentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(unknownTenant.contentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(foreignBundle.body().get("code").asString())
                .isEqualTo(unknownBundle.body().get("code").asString())
                .isEqualTo("BUNDLE_NOT_FOUND");
        assertThat(unknownTenant.body().get("code").asString()).isEqualTo("TENANT_NOT_FOUND");
        assertThat(foreignBundle.body().get("title").asString())
                .isEqualTo(unknownBundle.body().get("title").asString());
        assertThat(foreignBundle.body().get("detail").asString())
                .isEqualTo(unknownBundle.body().get("detail").asString());

        // No data in any denial: the bundle's identity, version, and
        // release never appear in a concealed body's descriptive
        // members — the envelope's instance member is the caller's own
        // request URI (RFC 7807), an echo of what they already
        // addressed, never server-held data.
        for (Recording denial : List.of(foreignBundle, unknownBundle, unknownTenant)) {
            String described = denial.body().get("title").asString()
                    + " " + denial.body().get("detail").asString()
                    + " " + denial.body().get("type").asString();
            assertThat(described).doesNotContain(world.bundle2.toString());
            assertThat(described).doesNotContain("1.4.2");
            assertThat(described).doesNotContain(world.release2.toString());
        }

        // The health comparison conceals through the same seam.
        Recording foreignHealth = exchange(
                HttpMethod.GET,
                "/api/v1/tenants/" + world.tenantB + "/dashboard/ota/bundles/" + world.bundle2 + "/health",
                world.adminToken);
        assertThat(foreignHealth.status()).isEqualTo(404);
        assertThat(foreignHealth.body().get("code").asString()).isEqualTo("BUNDLE_NOT_FOUND");

        // The RBAC vocabulary: the DEVELOPER persona (the CLI persona
        // without dashboard read) conceals exactly like the miss — the
        // permission denial renders as the concealed 404 (PRD 12),
        // never a 403 that would reveal the route exists.
        Recording developer = exchange(
                HttpMethod.GET,
                "/api/v1/tenants/" + world.tenantA + "/dashboard/ota/bundles/" + world.bundle2 + "/funnel",
                world.developerToken);
        assertThat(developer.status()).isEqualTo(404);
        assertThat(developer.contentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    }

    // -----------------------------------------------------------------------
    // The fixture: the S16-09 world, walked exactly as that suite walks
    // it, so the served numbers reconcile with its service-level pins
    // -----------------------------------------------------------------------

    private World world(String slug) {
        UUID orgId = createOrg("E " + slug + " Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "e-" + slug + "-admin", "ORG_ADMIN");
        String managerToken = loginOfOrgRoleIn(orgId, "e-" + slug + "-manager", "RELEASE_MANAGER");
        String developerToken = loginOfOrgRoleIn(orgId, "e-" + slug + "-dev", "DEVELOPER");
        UUID tenantA = createTenant(adminToken, orgId, "E " + slug + " Bank", "crm-e-" + slug);
        UUID tenantB = createTenant(adminToken, orgId, "E " + slug + " Bank B", "crm-e-" + slug + "-b");
        String tenantAdminToken = loginOfTenantAdminRoleIn(orgId, tenantA, "e-" + slug + "-tenant-admin");
        UUID appId =
                createTenantApp(adminToken, tenantA, "E " + slug + " App", "com.e." + slug.replace("-", "") + ".bank");
        jdbc.update("UPDATE applications SET ota_enabled = true WHERE id = ?", appId);

        Map<String, Object> ring = new LinkedHashMap<>();
        ring.put("organizationId", orgId.toString());
        ring.put("code", "ota");
        ring.put("name", "E OTA");
        ring.put("position", 3);
        ring.put("distributionChannel", "ota");
        post(adminToken, "/api/v1/rings", ring, 201);

        // The CURRENT bundle: released through the older release.
        JsonNode first = authorSignedBundle(developerToken, appId, "1.4.0", "ios", payload(384));
        UUID release1 = createOtaRelease(managerToken, appId, first);
        // The NEW bundle: the live release the fleet checks against.
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
        plan.put("stages", List.of(Map.of("basisPoints", 10000)));
        post(managerToken, "/api/v1/ota/rollouts", plan, 201);

        String appToken = post(
                        developerToken,
                        "/api/v1/ota/app-tokens",
                        Map.of("applicationId", appId.toString(), "name", "fleet token"),
                        201)
                .get("value")
                .asText();
        return new World(
                tenantA,
                tenantB,
                adminToken,
                tenantAdminToken,
                appId,
                release1,
                release2,
                UUID.fromString(first.get("id").asText()),
                UUID.fromString(second.get("id").asText()),
                appToken,
                managerToken,
                developerToken);
    }

    private record World(
            UUID tenantA,
            UUID tenantB,
            String adminToken,
            String tenantAdminToken,
            UUID app,
            UUID release1,
            UUID release2,
            UUID bundle1,
            UUID bundle2,
            String appToken,
            String managerToken,
            String developerToken) {}

    /**
     * The fleet's fixture funnel over the NEW bundle: four devices walk
     * it on the first day (two fully applied, one download failure, one
     * observer), a fifth applies on the second day, and one early
     * adopter rolls back — five checks, five seen, four downloads,
     * three applied, one failure, one rollback.
     */
    private void seedFunnel(World world, String devicePrefix) {
        for (String device : List.of("a", "b", "c", "d", "e")) {
            check(world.appToken, devicePrefix + "-" + device);
        }
        Instant day1 = Instant.now().minus(Duration.ofHours(30));
        Instant day2 = Instant.now().minus(Duration.ofMinutes(2));
        ingest(
                world,
                List.of(
                        lifecycle(world, "OTA_SEEN", null, day1.plusSeconds(60), device(devicePrefix, "a")),
                        lifecycle(world, "OTA_SEEN", null, day1.plusSeconds(61), device(devicePrefix, "b")),
                        lifecycle(world, "OTA_SEEN", null, day1.plusSeconds(62), device(devicePrefix, "c")),
                        lifecycle(world, "OTA_SEEN", null, day1.plusSeconds(63), device(devicePrefix, "d")),
                        lifecycle(world, "OTA_DOWNLOAD", "SUCCEEDED", day1.plusSeconds(120), device(devicePrefix, "a")),
                        lifecycle(world, "OTA_DOWNLOAD", "SUCCEEDED", day1.plusSeconds(121), device(devicePrefix, "b")),
                        lifecycle(world, "OTA_DOWNLOAD", "FAILED", day1.plusSeconds(122), device(devicePrefix, "c")),
                        lifecycle(world, "OTA_APPLIED", "SUCCEEDED", day1.plusSeconds(180), device(devicePrefix, "a")),
                        lifecycle(world, "OTA_APPLIED", "SUCCEEDED", day1.plusSeconds(181), device(devicePrefix, "b")),
                        lifecycle(
                                world,
                                "OTA_FAILED",
                                "checksum_mismatch",
                                day1.plusSeconds(182),
                                device(devicePrefix, "c"))));
        ingest(
                world,
                List.of(
                        lifecycle(world, "OTA_SEEN", null, day2, device(devicePrefix, "e")),
                        lifecycle(world, "OTA_DOWNLOAD", "SUCCEEDED", day2.plusSeconds(30), device(devicePrefix, "e")),
                        lifecycle(world, "OTA_APPLIED", "SUCCEEDED", day2.plusSeconds(60), device(devicePrefix, "e")),
                        lifecycle(world, "OTA_ROLLED_BACK", null, day2.plusSeconds(90), device(devicePrefix, "b"))));
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

    /** One release-keyed SESSION report (the crash-rate comparison's family). */
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

    /** One device check against the live release (the server-side OTA_CHECK row's writer). */
    private void check(String token, String deviceId) {
        StringBuilder uri = new StringBuilder("/api/v1/ota/check");
        uri.append("?deviceId=").append(deviceId);
        uri.append("&platform=ios&appVersion=2.0.0&channel=ota");
        client.get()
                .uri(URI.create(uri.toString()))
                .headers(headers -> headers.setBearerAuth(token))
                .exchange((request, response) -> new Recording(
                        response.getStatusCode().value(),
                        response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                        mapper.createObjectNode()))
                .expect(200);
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
                        "E " + role))
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
                        "E Tenant Admin"))
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
