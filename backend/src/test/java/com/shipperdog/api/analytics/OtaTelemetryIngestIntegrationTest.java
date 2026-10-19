package com.shipperdog.api.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
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
 * The S16-08 acceptance flow (issue #170, ADR-0010 workstream 5, D9):
 * the five device lifecycle types riding the EXISTING {@code sdt1.}-
 * bound ingest end to end. The fixture walks the real funnel — the
 * {@code sdc1.} check, the served manifest (whose {@code bundleId} and
 * {@code ingestToken} are exactly what a lifecycle report names), and
 * the batch POST — then pins the landed rows' OTA shape (bundle
 * attribution, payload-carried device identity, per-type result
 * vocabulary, NULL session columns, the bundle's version dimension
 * shared with the server-side {@code OTA_CHECK} row), the V54
 * event-id idempotence, and the refusals: the server-authoritative
 * {@code OTA_CHECK}, the closed result vocabularies, the bundle
 * attribution's required-and-concealed resolution, and the legacy
 * family's OTA-member exclusion (V57's ota_shape guarded in code).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
class OtaTelemetryIngestIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";
    private static final String PASSWORD = "a-perfectly-valid-password";

    /** The compose GCS emulator image, in a container (ADR-0003) — the bundle fixture streams bytes through it. */
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
    // AC1: the five lifecycle types ingest through the check-minted
    // sdt1., bundle-attributed and idempotent (V54)
    // -----------------------------------------------------------------------

    @Test
    void lifecycleEventsRideTheCheckMintedTokenAndDeduplicateIdempotently() throws Exception {
        Funnel funnel = funnel("tel-flow");
        UUID seenId = UUID.randomUUID();
        UUID downloadId = UUID.randomUUID();
        UUID appliedId = UUID.randomUUID();
        UUID failedId = UUID.randomUUID();
        UUID rolledBackId = UUID.randomUUID();
        List<Map<String, Object>> batch = List.of(
                lifecycle(funnel, "OTA_SEEN", null, seenId, payload("device-tel-1", "screen", "foreground")),
                lifecycle(funnel, "OTA_DOWNLOAD", "SUCCEEDED", downloadId, payload("device-tel-1", "network", "wifi")),
                lifecycle(funnel, "OTA_APPLIED", "SUCCEEDED", appliedId, payload("device-tel-1", "restartMs", 4200)),
                lifecycle(
                        funnel,
                        "OTA_FAILED",
                        "checksum_mismatch",
                        failedId,
                        payload("device-tel-1", "stage", "verify")),
                lifecycle(
                        funnel,
                        "OTA_ROLLED_BACK",
                        null,
                        rolledBackId,
                        payload("device-tel-1", "reason", "crash_loop")));

        JsonNode report = ingest(funnel, batch).expect(201).body;
        assertThat(report.get("ingested").asInt()).isEqualTo(5);
        assertThat(report.get("deduplicated").asInt()).isZero();

        // The landed shape, per type: the manifest's bundle attributed,
        // the payload's device identity carried, the result vocabulary
        // stored as validated, session columns NULL, no registry
        // device or tester (a fleet device is not tester hardware),
        // and the version dimension the server-side check row shares.
        assertLanded(seenId, funnel, "OTA_SEEN", null);
        assertLanded(downloadId, funnel, "OTA_DOWNLOAD", "SUCCEEDED");
        assertLanded(appliedId, funnel, "OTA_APPLIED", "SUCCEEDED");
        assertLanded(failedId, funnel, "OTA_FAILED", "checksum_mismatch");
        assertLanded(rolledBackId, funnel, "OTA_ROLLED_BACK", null);

        // The at-least-once retry: the same event ids land nothing new
        // (the V54 unique index's probe re-filter — idempotent).
        JsonNode retry = ingest(funnel, batch).expect(201).body;
        assertThat(retry.get("ingested").asInt()).isZero();
        assertThat(retry.get("deduplicated").asInt()).isEqualTo(5);
        assertThat(countLanded(funnel)).isEqualTo(5);

        // One funnel, both writers: the check's server-side row and
        // the device lifecycle rows share tenant, release, bundle, and
        // version dimensions.
        Map<String, Object> checkRow = jdbc.queryForMap(
                "SELECT tenant_id, release_id, ota_bundle_id, version_id FROM analytics_events "
                        + "WHERE release_id = ? AND event_type = 'OTA_CHECK'",
                funnel.releaseId);
        Map<String, Object> seenRow = rowOf(seenId);
        assertThat(seenRow.get("tenant_id")).isEqualTo(checkRow.get("tenant_id"));
        assertThat(seenRow.get("release_id")).isEqualTo(checkRow.get("release_id"));
        assertThat(seenRow.get("ota_bundle_id")).isEqualTo(checkRow.get("ota_bundle_id"));
        assertThat(seenRow.get("version_id")).isEqualTo(checkRow.get("version_id"));
    }

    // -----------------------------------------------------------------------
    // AC2: the vocabularies and the attribution are validated as
    // field-addressed problems — nothing lands
    // -----------------------------------------------------------------------

    @Test
    void theServerAuthoritativeAndMalformedReportsAreRefusedAsProblems() throws Exception {
        Funnel funnel = funnel("tel-refuse");

        // D9's one server word: a device cannot post the platform's
        // own check counting — the byte-identical unknown-type refusal.
        assertThat(refusal(funnel, List.of(lifecycle(funnel, "OTA_CHECK", "update_available", UUID.randomUUID(), null)))
                        .get("type")
                        .asText())
                .isEqualTo("unknown type OTA_CHECK");

        // The per-type result vocabularies.
        assertThat(refusal(funnel, List.of(lifecycle(funnel, "OTA_FAILED", "exploded", UUID.randomUUID(), null)))
                        .get("result")
                        .asText())
                .isEqualTo("exploded is not a OTA_FAILED result");
        assertThat(refusal(funnel, List.of(lifecycle(funnel, "OTA_APPLIED", "MAYBE", UUID.randomUUID(), null)))
                        .get("result")
                        .asText())
                .isEqualTo("MAYBE is not a OTA_APPLIED result");
        assertThat(refusal(funnel, List.of(lifecycle(funnel, "OTA_SEEN", "SUCCEEDED", UUID.randomUUID(), null)))
                        .get("result")
                        .asText())
                .isEqualTo("SUCCEEDED is not a OTA_SEEN result");

        // The attribution: absent, malformed, and unknown-or-foreign
        // are each refused (the last two byte-identical — no oracle).
        Map<String, Object> noBundle = lifecycle(funnel, "OTA_SEEN", null, UUID.randomUUID(), null);
        noBundle.remove("otaBundleId");
        assertThat(refusal(funnel, List.of(noBundle)).get("ota_bundle_id").asText())
                .isEqualTo("ota_bundle_id is required for OTA_SEEN");
        assertThat(refusal(funnel, List.of(lifecycle(funnel, "OTA_SEEN", null, "not-a-uuid", UUID.randomUUID(), null)))
                        .get("ota_bundle_id")
                        .asText())
                .isEqualTo("ota_bundle_id must be a UUID");
        assertThat(refusal(
                                funnel,
                                List.of(lifecycle(
                                        funnel,
                                        "OTA_SEEN",
                                        null,
                                        UUID.randomUUID().toString(),
                                        UUID.randomUUID(),
                                        null)))
                        .get("ota_bundle_id")
                        .asText())
                .isEqualTo("the bundle did not resolve");

        // The ota_shape disjunction guarded in code: the legacy family
        // carries no OTA members, and the OTA family no session counts.
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("type", "INSTALL");
        legacy.put("result", "SUCCEEDED");
        legacy.put("releaseId", funnel.releaseId.toString());
        legacy.put("eventId", UUID.randomUUID().toString());
        legacy.put("occurredAt", Instant.now().toString());
        legacy.put("otaBundleId", funnel.bundleId.toString());
        assertThat(refusal(funnel, List.of(legacy)).get("ota_bundle_id").asText())
                .isEqualTo("ota_bundle_id belongs to the OTA event types only");
        Map<String, Object> withSessions = lifecycle(funnel, "OTA_DOWNLOAD", "SUCCEEDED", UUID.randomUUID(), null);
        withSessions.put("sessions", 5);
        assertThat(refusal(funnel, List.of(withSessions)).get("sessions").asText())
                .isEqualTo("session counts belong to SESSION events only");

        // Whole-batch discipline: nothing landed but the check's own row.
        assertThat(countLanded(funnel)).isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM analytics_events WHERE release_id = ? AND event_type = 'OTA_CHECK'",
                        Integer.class,
                        funnel.releaseId))
                .isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // The world and its helpers
    // -----------------------------------------------------------------------

    /**
     * One complete funnel per test: the org, the OTA-enabled app, the
     * ota ring, a signed bundle, the ACTIVE release targeting the
     * tenant, the full-space rollout, and the device check whose
     * manifest mints the {@code sdt1.} grant and names the bundle.
     */
    private Funnel funnel(String suffix) {
        UUID orgId = createOrg("Tel " + suffix + " Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "tel-" + suffix + "-admin", "ORG_ADMIN");
        String managerToken = loginOfOrgRoleIn(orgId, "tel-" + suffix + "-manager", "RELEASE_MANAGER");
        String developerToken = loginOfOrgRoleIn(orgId, "tel-" + suffix + "-dev", "DEVELOPER");
        UUID tenantId = createTenant(adminToken, orgId, "Tel " + suffix + " Bank", "crm-tel-" + suffix);
        UUID appId = createTenantApp(
                adminToken, tenantId, "Tel " + suffix + " App", "com.tel." + suffix.replace("-", "") + ".bank");
        jdbc.update("UPDATE applications SET ota_enabled = true WHERE id = ?", appId);

        Map<String, Object> ring = new LinkedHashMap<>();
        ring.put("organizationId", orgId.toString());
        ring.put("code", "ota");
        ring.put("name", "Tel OTA");
        ring.put("position", 3);
        ring.put("distributionChannel", "ota");
        post(adminToken, "/api/v1/rings", ring, 201);

        byte[] bytes = payload(512);
        JsonNode bundle = authorSignedBundle(developerToken, appId, "1.4.2", "ios", bytes);
        Map<String, Object> releaseBody = new LinkedHashMap<>();
        releaseBody.put("applicationId", appId.toString());
        releaseBody.put("versionId", bundle.get("versionId").asText());
        releaseBody.put("ring", "ota");
        releaseBody.put(
                "artifacts",
                List.of(Map.of("platform", "IOS", "artifactId", bundle.get("id").asText())));
        releaseBody.put("targets", List.of(Map.of("tenantId", tenantId.toString(), "testerGroupIds", List.of())));
        UUID releaseId = UUID.fromString(post(managerToken, "/api/v1/releases", releaseBody, 201)
                .get("id")
                .asText());
        for (String state : List.of("VALIDATING", "READY", "APPROVAL_PENDING", "APPROVED", "DEPLOYING", "ACTIVE")) {
            post(
                    managerToken,
                    "/api/v1/releases/" + releaseId + "/transitions",
                    Map.of("applicationId", appId.toString(), "to", state),
                    200);
        }
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("applicationId", appId.toString());
        plan.put("releaseId", releaseId.toString());
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
        JsonNode manifest = check(appToken, "device-tel-1", "ios", "2.0.0", "ota");
        assertThat(manifest.get("bundleId").asText()).isEqualTo(bundle.get("id").asText());
        assertThat(manifest.get("ingestToken").asText()).startsWith("sdt1.");
        return new Funnel(
                tenantId,
                appId,
                releaseId,
                UUID.fromString(bundle.get("id").asText()),
                UUID.fromString(bundle.get("versionId").asText()),
                manifest.get("ingestToken").asText());
    }

    private record Funnel(
            UUID tenantId, UUID appId, UUID releaseId, UUID bundleId, UUID bundleVersionId, String ingestToken) {}

    /** One lifecycle event body: the funnel's release and bundle, a client event id, the optional payload. */
    private Map<String, Object> lifecycle(
            Funnel funnel, String type, String result, UUID eventId, Map<String, Object> payload) {
        return lifecycle(funnel, type, result, funnel.bundleId.toString(), eventId, payload);
    }

    /** The same body with an explicit bundle id (the malformed and unknown refusals' own legs). */
    private Map<String, Object> lifecycle(
            Funnel funnel, String type, String result, String bundleId, UUID eventId, Map<String, Object> payload) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        if (result != null) {
            event.put("result", result);
        }
        event.put("releaseId", funnel.releaseId.toString());
        event.put("eventId", eventId.toString());
        event.put("occurredAt", Instant.now().toString());
        event.put("otaBundleId", bundleId);
        if (payload != null) {
            event.put("otaPayload", payload);
        }
        return event;
    }

    private static Map<String, Object> payload(String deviceId, String key, Object value) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("device_id", deviceId);
        payload.put(key, value);
        return payload;
    }

    /** Posts one batch under the funnel's check-minted sdt1. grant. */
    private Recording ingest(Funnel funnel, List<Map<String, Object>> events) {
        return exchange(
                "/api/v1/tenants/" + funnel.tenantId + "/analytics/events",
                funnel.ingestToken,
                Map.of("events", events));
    }

    /** The refusal legs' own helper: the 400's problem+json errors object. */
    private JsonNode refusal(Funnel funnel, List<Map<String, Object>> events) {
        return ingest(funnel, events).expect(400).body.get("errors");
    }

    /** The lifecycle rows landed for the funnel's release (OTA_CHECK excluded). */
    private int countLanded(Funnel funnel) {
        return jdbc.queryForObject(
                        "SELECT count(*) FROM analytics_events WHERE release_id = ? AND event_type <> 'OTA_CHECK'",
                        Integer.class,
                        funnel.releaseId)
                .intValue();
    }

    private Map<String, Object> rowOf(UUID eventId) {
        return jdbc.queryForMap(
                "SELECT tenant_id, release_id, event_type, result, ota_bundle_id, "
                        + "ota_payload ->> 'device_id' AS device, device_id, tester_id, sessions, version_id "
                        + "FROM analytics_events WHERE event_id = ?",
                eventId);
    }

    private void assertLanded(UUID eventId, Funnel funnel, String type, String result) {
        Map<String, Object> row = rowOf(eventId);
        assertThat(row.get("event_type")).isEqualTo(type);
        assertThat(row.get("result")).isEqualTo(result);
        assertThat(row.get("ota_bundle_id")).isEqualTo(funnel.bundleId);
        assertThat(row.get("device")).isEqualTo("device-tel-1");
        assertThat(row.get("device_id")).isNull();
        assertThat(row.get("tester_id")).isNull();
        assertThat(row.get("sessions")).isNull();
        assertThat(row.get("version_id")).isEqualTo(funnel.bundleVersionId);
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

    /** One device check, expecting the manifest (the fixture's funnel always serves). */
    private JsonNode check(String token, String deviceId, String platform, String appVersion, String channel) {
        StringBuilder uri = new StringBuilder("/api/v1/ota/check");
        uri.append("?deviceId=").append(deviceId);
        uri.append("&platform=").append(platform);
        uri.append("&appVersion=").append(appVersion);
        uri.append("&channel=").append(channel);
        return client.get()
                .uri(URI.create(uri.toString()))
                .headers(headers -> headers.setBearerAuth(token))
                .exchange((request, response) -> {
                    byte[] bytes = response.getBody().readAllBytes();
                    return new Recording(
                            response.getStatusCode().value(),
                            response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                            bytes.length == 0 ? mapper.createObjectNode() : mapper.readTree(bytes));
                })
                .expect(200)
                .body;
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
                        "Tel " + role))
                .retrieve()
                .body(String.class);
        return accessToken(login(email, PASSWORD));
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

    private JsonNode post(String bearer, String uri, Map<String, Object> body, Integer expectedStatus) {
        Recording recording = exchange(uri, bearer, body);
        if (expectedStatus != null) {
            assertThat(recording.status)
                    .as("POST %s -> %s", uri, recording.body)
                    .isEqualTo(expectedStatus);
        }
        return recording.body;
    }

    private Recording exchange(String uri, String bearer, Map<String, Object> body) {
        RestClient.RequestBodySpec spec = client.method(HttpMethod.POST).uri(URI.create(uri));
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
