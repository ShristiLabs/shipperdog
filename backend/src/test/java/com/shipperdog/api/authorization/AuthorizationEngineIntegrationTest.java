package com.shipperdog.api.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.artifact.AndroidFixtures;
import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.AuditCapture;
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
 * The layered engine's end-to-end acceptance flow (issue #30, S7-01,
 * PRD 12's worked example): John asks for Banking 7.12.0 Android and
 * the engine walks the whole PRD 11 chain — the console persona ALLOWs
 * through every layer in order with an audited decision; the tester
 * persona with effective tenant access but no grant denies at the
 * version layer (INV-005: deny-by-default until S7-02) and the HTTP
 * surface renders the denial as the concealed 404 a genuine miss
 * produces; authentication failures stay 401s; the two-switches rule
 * and version revocation flip the failing layer live; the catalog
 * listing runs the engine; and the admin-only inspection API exposes
 * the evaluated chain. The per-layer unit halves live in
 * {@code internal.engine.LayeredEngineUnitTest}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
@Import(AuditCapture.class)
class AuthorizationEngineIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";
    private static final String PASSWORD = "a-perfectly-valid-password";

    /** The compose GCS emulator image, in a container (ADR-0003). */
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
    static void artifactStorage(DynamicPropertyRegistry registry) {
        registry.add(
                "shipperdog.artifact.storage.host", () -> "http://" + GCS.getHost() + ":" + GCS.getMappedPort(4443));
    }

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
    // The PRD 12 worked example: ALLOW through every layer, in order
    // -----------------------------------------------------------------------

    @Test
    void theWorkedExampleAllowsTheConsolePersonaThroughEveryLayerInOrder() throws Exception {
        World world = world("s7-allow");
        UUID artifactId = readyAndroidArtifact(world);

        JsonNode decision = inspect(
                world.platformAdmin(),
                Map.of(
                        "userId", developerUserIdOf(world).toString(),
                        "tenantId", world.tenantId.toString(),
                        "applicationId", world.applicationId.toString(),
                        "versionId", world.versionId.toString(),
                        "artifactId", artifactId.toString(),
                        "platform", "ANDROID",
                        "action", "DOWNLOAD"));

        assertThat(decision.get("allowed").asBoolean())
                .as("PRD 12: John of the organization, tenant access, tenant has Banking, "
                        + "version authorized, Android artifact available, distribution active -> ALLOW")
                .isTrue();
        assertThat(decision.hasNonNull("reason")).isFalse();
        assertThat(decision.hasNonNull("failingLayer")).isFalse();
        assertThat(layerNames(decision))
                .containsExactly(
                        "USER", "ORGANIZATION", "TENANT", "APPLICATION", "VERSION", "ARTIFACT", "DISTRIBUTION");
        assertThat(decision.get("chain"))
                .allSatisfy(layer -> assertThat(layer.get("status").asString()).isEqualTo("PASSED"));

        // The decision is audited (allow and deny alike, PRD 27): the
        // event carries the action, the outcome, the addressed ids,
        // and the request's correlation id.
        AuditEvent event = latestDecisionEvent();
        assertThat(event.success()).isTrue();
        assertThat(event.versionId()).isEqualTo(world.versionId);
        assertThat(event.artifactId()).isEqualTo(artifactId);
        assertThat(event.detail()).containsEntry("action", "DOWNLOAD").containsEntry("outcome", "ALLOW");
        assertThat(event.correlationId())
                .as("the audit binding attaches the correlation id")
                .isNotNull();
    }

    // -----------------------------------------------------------------------
    // The not-authorized arm: deny-by-default and the concealed miss
    // -----------------------------------------------------------------------

    @Test
    void theTesterPersonaWithoutAGrantDeniesAndRendersTheConcealedVersionMiss() throws Exception {
        World world = world("s7-deny");
        UUID artifactId = readyAndroidArtifact(world);
        UUID testerId = activeTester(world.platformAdmin(), world.orgId, "s7-john@example.com", "John Tester");
        linkTesterToTenant(world.platformAdmin(), world.tenantId, testerId);
        UUID testerUserId = jdbc.queryForObject("SELECT user_id FROM testers WHERE id = ?", UUID.class, testerId);
        String testerToken = accessToken(login("s7-john@example.com", PASSWORD));

        // The engine: effective tenant access holds (both switches on)
        // but no entitlement contributor covers a pure tester — INV-005
        // deny-by-default at the version layer until S7-02's grants.
        JsonNode decision = inspect(
                world.platformAdmin(),
                Map.of(
                        "userId", testerUserId.toString(),
                        "tenantId", world.tenantId.toString(),
                        "versionId", world.versionId.toString(),
                        "artifactId", artifactId.toString(),
                        "platform", "ANDROID",
                        "action", "DOWNLOAD"));
        assertThat(decision.get("allowed").asBoolean()).isFalse();
        assertThat(decision.get("failingLayer").asString()).isEqualTo("VERSION");
        assertThat(decision.get("reason").asString()).isEqualTo("ENTITLEMENT_MISSING");
        assertThat(layerStatus(decision, "USER")).isEqualTo("PASSED");
        assertThat(layerStatus(decision, "ORGANIZATION")).isEqualTo("PASSED");
        assertThat(layerStatus(decision, "TENANT")).isEqualTo("PASSED");
        assertThat(layerStatus(decision, "APPLICATION")).isEqualTo("PASSED");
        assertThat(layerStatus(decision, "VERSION")).isEqualTo("FAILED");
        assertThat(layerStatus(decision, "ARTIFACT")).isEqualTo("SKIPPED");
        assertThat(layerStatus(decision, "DISTRIBUTION")).isEqualTo("SKIPPED");

        // The denial audit carries the reason code and the chain.
        AuditEvent event = latestDecisionEvent();
        assertThat(event.success()).isFalse();
        assertThat(event.detail())
                .containsEntry("outcome", "DENY")
                .containsEntry("failingLayer", "VERSION")
                .containsEntry("reason", "ENTITLEMENT_MISSING");

        // The HTTP surface renders the denial as the concealed miss:
        // byte-for-byte the genuine not-found of the same route (the
        // trace id differs per request; the problem shape must not).
        Map<String, Object> denied =
                problemOf(getRaw(testerToken, "/api/v1/artifacts/" + artifactId + "?version_id=" + world.versionId));
        Map<String, Object> genuine =
                problemOf(getRaw(testerToken, "/api/v1/artifacts/" + artifactId + "?version_id=" + UUID.randomUUID()));
        assertThat(denied.get("status")).isEqualTo(404);
        assertThat(genuine.get("status")).isEqualTo(404);
        assertThat(denied)
                .as("a denial must be indistinguishable from a genuine miss (PRD 12)")
                .containsAllEntriesOf(genuine);
        assertThat(denied).containsEntry("code", "VERSION_NOT_FOUND").containsEntry("status", 404);
    }

    // -----------------------------------------------------------------------
    // Conceal grains: the read route's artifact grain vs version grain
    // -----------------------------------------------------------------------

    @Test
    void anArtifactLayerMissRendersTheArtifactGrainNotTheVersionGrain() throws Exception {
        World world = world("s7-grain");
        UUID artifactId = readyAndroidArtifact(world);

        // An authorized subject addressing an unknown artifact inside
        // their real version: the engine denies at the ARTIFACT layer
        // and the route must render the ARTIFACT grain — a different
        // concealed shape than the version-grain miss above.
        Map<String, Object> denied = problemOf(
                getRaw(world.developer(), "/api/v1/artifacts/" + UUID.randomUUID() + "?version_id=" + world.versionId));
        assertThat(denied.get("status")).isEqualTo(404);
        assertThat(denied).containsEntry("code", "ARTIFACT_NOT_FOUND");
        AuditEvent event = latestDecisionEvent();
        assertThat(event.success()).isFalse();
        assertThat(event.detail())
                .containsEntry("failingLayer", "ARTIFACT")
                .containsEntry("reason", "ARTIFACT_MISSING");

        // The control: a version-layer denial on the same route renders
        // the version grain (the two grains of the route's contract).
        assertThat(problemOf(getRaw(
                                world.developer(),
                                "/api/v1/artifacts/" + artifactId + "?version_id=" + UUID.randomUUID()))
                        .get("code"))
                .isEqualTo("VERSION_NOT_FOUND");
    }

    // -----------------------------------------------------------------------
    // Authentication failures are 401s, never decisions
    // -----------------------------------------------------------------------

    @Test
    void unauthenticatedRequestsAre401sAndTheEngineNeverRuns() throws Exception {
        World world = world("s7-authn");
        Recording response = exchange(
                "/api/v1/artifacts/" + UUID.randomUUID() + "?version_id=" + UUID.randomUUID(), null, HttpMethod.GET);
        assertThat(response.status).isEqualTo(401);
        Recording catalog = exchange("/api/v1/tenants/" + world.tenantId + "/applications", null, HttpMethod.GET);
        assertThat(catalog.status).isEqualTo(401);
        assertThat(audit.eventsOfType(AuditEventType.AUTHORIZATION_DECISION))
                .as("no subject, no decision (issue #30 AC3: authn failures stay 401)")
                .isEmpty();
    }

    // -----------------------------------------------------------------------
    // Live semantics: the two-switches rule flips the failing layer
    // -----------------------------------------------------------------------

    @Test
    void suspendingTheMembershipFlipsTheFailingLayerToTenantLive() throws Exception {
        World world = world("s7-switches");
        UUID artifactId = readyAndroidArtifact(world);
        UUID testerId = activeTester(world.platformAdmin(), world.orgId, "s7-swan@example.com", "Suspended Later");
        linkTesterToTenant(world.platformAdmin(), world.tenantId, testerId);
        UUID testerUserId = jdbc.queryForObject("SELECT user_id FROM testers WHERE id = ?", UUID.class, testerId);
        Map<String, Object> context = Map.of(
                "userId", testerUserId.toString(),
                "tenantId", world.tenantId.toString(),
                "versionId", world.versionId.toString(),
                "artifactId", artifactId.toString(),
                "platform", "ANDROID",
                "action", "DOWNLOAD");

        assertThat(layerStatus(inspect(world.platformAdmin(), context), "VERSION"))
                .isEqualTo("FAILED");

        // Suspend the membership: the next evaluation denies at the
        // TENANT layer (live query, no cache — the S6-03 contract the
        // engine consumes).
        put(
                world.platformAdmin(),
                "/api/v1/tenants/" + world.tenantId + "/testers/" + testerId + "/status",
                Map.of("status", "SUSPENDED"),
                200);
        JsonNode suspended = inspect(world.platformAdmin(), context);
        assertThat(suspended.get("allowed").asBoolean()).isFalse();
        assertThat(suspended.get("failingLayer").asString()).isEqualTo("TENANT");
        assertThat(suspended.get("reason").asString()).isEqualTo("TENANT_ACCESS_DENIED");

        // Re-enable: back to the version layer's entitlement answer.
        put(
                world.platformAdmin(),
                "/api/v1/tenants/" + world.tenantId + "/testers/" + testerId + "/status",
                Map.of("status", "ACTIVE"),
                200);
        assertThat(layerStatus(inspect(world.platformAdmin(), context), "VERSION"))
                .isEqualTo("FAILED");
    }

    // -----------------------------------------------------------------------
    // INV-007: a revoked version blocks downloads, not reads
    // -----------------------------------------------------------------------

    @Test
    void aRevokedVersionBlocksDownloadsButKeepsReadsAvailable() throws Exception {
        World world = world("s7-revoke");
        UUID artifactId = readyAndroidArtifact(world);
        post(
                world.platformAdmin(),
                "/api/v1/applications/" + world.applicationId + "/versions/" + world.versionId + "/revoke-distribution",
                Map.of("reason", "hotfix pulled"),
                200);

        Map<String, Object> download = Map.of(
                "userId", developerUserIdOf(world).toString(),
                "tenantId", world.tenantId.toString(),
                "versionId", world.versionId.toString(),
                "artifactId", artifactId.toString(),
                "platform", "ANDROID",
                "action", "DOWNLOAD");
        JsonNode denied = inspect(world.platformAdmin(), download);
        assertThat(denied.get("allowed").asBoolean()).isFalse();
        assertThat(denied.get("failingLayer").asString()).isEqualTo("VERSION");
        assertThat(denied.get("reason").asString()).isEqualTo("VERSION_REVOKED");

        // INV-006: previously authorized versions remain readable —
        // the read decision and the HTTP read both still allow.
        Map<String, Object> read = new HashMap<>(download);
        read.put("action", "READ_ARTIFACT");
        assertThat(inspect(world.platformAdmin(), read).get("allowed").asBoolean())
                .isTrue();
        assertThat(get(world.developer(), "/api/v1/artifacts/" + artifactId + "?version_id=" + world.versionId, 200)
                        .get("status")
                        .asString())
                .isEqualTo("READY");
    }

    // -----------------------------------------------------------------------
    // The catalog listing is decision-guarded (issue #30 AC1)
    // -----------------------------------------------------------------------

    @Test
    void theCatalogListingRunsTheEngineAndConcealsForeignTenants() throws Exception {
        World world = world("s7-catalog");
        JsonNode listed = get(world.platformAdmin(), "/api/v1/tenants/" + world.tenantId + "/applications", 200);
        assertThat(listed.get("items").size()).isEqualTo(1);
        AuditEvent decision = latestDecisionEvent();
        assertThat(decision.success()).isTrue();
        assertThat(decision.detail()).containsEntry("action", "LIST_APPLICATIONS");
        assertThat(decision.detail())
                .containsEntry(
                        "chain",
                        "USER:PASSED,ORGANIZATION:PASSED,TENANT:PASSED,APPLICATION:SKIPPED,"
                                + "VERSION:SKIPPED,ARTIFACT:SKIPPED,DISTRIBUTION:SKIPPED");

        // A foreign organization's administrator never learns the
        // tenant exists: the same concealed envelope a genuine miss
        // produces.
        World foreign = world("s7-foreign");
        // The foreign caller is the foreign organization's DEVELOPER —
        // NOT the global platform admin, whose wildcard reads every
        // tenant by construction.
        String foreigner = foreign.developer();
        Map<String, Object> denied =
                problemOf(getRaw(foreigner, "/api/v1/tenants/" + world.tenantId + "/applications"));
        Map<String, Object> genuine =
                problemOf(getRaw(foreigner, "/api/v1/tenants/" + UUID.randomUUID() + "/applications"));
        assertThat(denied.get("status")).isEqualTo(404);
        assertThat(genuine.get("status")).isEqualTo(404);
        assertThat(denied).containsAllEntriesOf(genuine);
        assertThat(denied).containsEntry("code", "TENANT_NOT_FOUND");

        // The engine's own view of the foreign administrator: denied
        // at the tenant layer.
        JsonNode inspected = inspect(
                world.platformAdmin(),
                Map.of(
                        "userId", developerUserIdOf(foreign).toString(),
                        "tenantId", world.tenantId.toString(),
                        "action", "LIST_APPLICATIONS"));
        assertThat(inspected.get("allowed").asBoolean()).isFalse();
        assertThat(inspected.get("failingLayer").asString()).isEqualTo("TENANT");
    }

    // -----------------------------------------------------------------------
    // The inspection API is admin-only and validates its body
    // -----------------------------------------------------------------------

    @Test
    void theInspectionSurfaceIsAdminOnlyAndValidatesItsBody() throws Exception {
        World world = world("s7-inspect");

        Recording forbidden = exchange(
                "/api/v1/authorization/decisions",
                world.developer(),
                HttpMethod.POST,
                Map.of("userId", developerUserIdOf(world).toString(), "action", "DOWNLOAD"));
        assertThat(forbidden.status).isEqualTo(403);

        Recording malformedAction = exchange(
                "/api/v1/authorization/decisions",
                world.platformAdmin(),
                HttpMethod.POST,
                Map.of("userId", developerUserIdOf(world).toString(), "action", "SIDELOAD"));
        assertThat(malformedAction.status).isEqualTo(400);
        assertThat(malformedAction.body.get("code").asString()).isEqualTo("INVALID_REQUEST");

        Recording missingSubject = exchange(
                "/api/v1/authorization/decisions",
                world.platformAdmin(),
                HttpMethod.POST,
                Map.of("action", "DOWNLOAD"));
        assertThat(missingSubject.status).isEqualTo(400);
    }

    // -----------------------------------------------------------------------
    // World and helpers
    // -----------------------------------------------------------------------

    /** One organization, one tenant, the Banking application, version 7.12.0, and a DEVELOPER John. */
    private World world(String suffix) {
        String platformAdmin = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        UUID orgId = UUID.fromString(
                post(platformAdmin, "/api/v1/organizations", Map.of("name", "E7 " + suffix + " Corp"), 201)
                        .get("id")
                        .asText());
        UUID tenantId = UUID.fromString(post(
                        platformAdmin,
                        "/api/v1/tenants",
                        Map.of(
                                "organizationId", orgId.toString(),
                                "name", "Banking " + suffix,
                                "externalId", "ext-s7-" + suffix),
                        201)
                .get("id")
                .asText());
        UUID applicationId = UUID.fromString(post(
                        platformAdmin,
                        "/api/v1/applications",
                        Map.of(
                                "tenantId",
                                tenantId.toString(),
                                "name",
                                "Banking",
                                "platforms",
                                List.of("ANDROID"),
                                "packageName",
                                AndroidFixtures.PACKAGE),
                        201)
                .get("id")
                .asText());
        UUID versionId = UUID.fromString(post(
                        platformAdmin,
                        "/api/v1/applications/" + applicationId + "/versions",
                        Map.of("versionName", AndroidFixtures.VERSION_NAME),
                        201)
                .get("id")
                .asText());
        String developerEmail = "s7-" + suffix + "-john@example.com";
        String developerToken = loginOfOrgRoleIn(platformAdmin, orgId, developerEmail, "DEVELOPER");
        return new World(platformAdmin, orgId, tenantId, applicationId, versionId, developerEmail, developerToken);
    }

    private record World(
            String platformAdmin,
            UUID orgId,
            UUID tenantId,
            UUID applicationId,
            UUID versionId,
            String developerEmail,
            String developer) {}

    /** Resolves the developer John's user id lazily (the record cannot query). */
    private UUID developerUserIdOf(World world) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, world.developerEmail());
    }

    private UUID readyAndroidArtifact(World world) throws Exception {
        JsonNode uploaded = multipartPost(
                world.developer(),
                "/api/v1/artifacts",
                Map.of("version_id", world.versionId.toString(), "platform", "ANDROID"),
                AndroidFixtures.aabVersionCodeInManifest(),
                201);
        UUID artifactId = UUID.fromString(uploaded.get("id").asText());
        long deadline = System.currentTimeMillis() + 20_000;
        String status = "";
        while (System.currentTimeMillis() < deadline) {
            status = get(world.developer(), "/api/v1/artifacts/" + artifactId + "?version_id=" + world.versionId, 200)
                    .get("status")
                    .asString();
            if ("READY".equals(status) || "FAILED".equals(status)) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(status).as("the §12 fixture must settle READY").isEqualTo("READY");
        return artifactId;
    }

    private JsonNode inspect(String token, Map<String, Object> body) {
        return post(token, "/api/v1/authorization/decisions", body, 200);
    }

    private AuditEvent latestDecisionEvent() {
        List<AuditEvent> events = audit.eventsOfType(AuditEventType.AUTHORIZATION_DECISION);
        assertThat(events).isNotEmpty();
        return events.get(events.size() - 1);
    }

    private static List<String> layerNames(JsonNode decision) {
        List<String> names = new java.util.ArrayList<>();
        for (JsonNode outcome : decision.get("chain")) {
            names.add(outcome.get("layer").asString());
        }
        return names;
    }

    private static String layerStatus(JsonNode decision, String layer) {
        for (JsonNode outcome : decision.get("chain")) {
            if (layer.equals(outcome.get("layer").asString())) {
                return outcome.get("status").asString();
            }
        }
        throw new AssertionError("layer " + layer + " missing from the chain");
    }

    private static Map<String, Object> problemOf(Recording recording) {
        Map<String, Object> shape = new LinkedHashMap<>();
        shape.put("status", recording.status);
        shape.put("code", recording.body.get("code").asString());
        shape.put("title", recording.body.get("title").asString());
        shape.put("detail", recording.body.get("detail").asString());
        return shape;
    }

    private UUID activeTester(String admin, UUID orgId, String email, String fullName) {
        JsonNode invited = post(
                admin,
                "/api/v1/testers",
                Map.of("organizationId", orgId.toString(), "email", email, "fullName", fullName),
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
                        fullName))
                .retrieve()
                .body(String.class);
        return UUID.fromString(invited.get("id").asText());
    }

    private void linkTesterToTenant(String token, UUID tenantId, UUID testerId) {
        post(token, "/api/v1/tenants/" + tenantId + "/testers", Map.of("testerId", testerId.toString()), 201);
    }

    private String loginOfOrgRoleIn(String adminToken, UUID orgId, String email, String role) {
        JsonNode invitation = post(
                adminToken,
                "/api/v1/auth/invitations",
                Map.of("email", email, "organizationId", orgId.toString(), "role", role),
                201);
        client.post()
                .uri("/api/v1/auth/register")
                .body(Map.of(
                        "invitationToken",
                        invitation.get("invitationToken").asString(),
                        "email",
                        email,
                        "password",
                        PASSWORD,
                        "fullName",
                        "E7 " + role))
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

    private JsonNode multipartPost(String token, String uri, Map<String, Object> fields, byte[] bytes, int expected) {
        org.springframework.util.MultiValueMap<String, Object> parts =
                new org.springframework.util.LinkedMultiValueMap<>();
        fields.forEach(parts::add);
        parts.add("file", new org.springframework.core.io.ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "banking.aab";
            }
        });
        Recording recording = exchange(uri, token, HttpMethod.POST, parts);
        assertThat(recording.status).as("multipart %s: %s", uri, recording.body).isEqualTo(expected);
        return recording.body;
    }

    private JsonNode post(String token, String uri, Map<String, Object> body, int expectedStatus) {
        Recording response = exchange(uri, token, HttpMethod.POST, body);
        assertThat(response.status).as("POST %s: %s", uri, response.body).isEqualTo(expectedStatus);
        return response.body;
    }

    private JsonNode put(String token, String uri, Map<String, Object> body, int expectedStatus) {
        Recording response = exchange(uri, token, HttpMethod.PUT, body);
        assertThat(response.status).as("PUT %s: %s", uri, response.body).isEqualTo(expectedStatus);
        return response.body;
    }

    private JsonNode get(String token, String uri, int expectedStatus) {
        Recording response = getRaw(token, uri);
        assertThat(response.status).as("GET %s: %s", uri, response.body).isEqualTo(expectedStatus);
        return response.body;
    }

    private Recording getRaw(String token, String uri) {
        return exchange(uri, token, HttpMethod.GET, null);
    }

    private Recording exchange(String uri, String token, HttpMethod method) {
        return exchange(uri, token, method, null);
    }

    private Recording exchange(String uri, String token, HttpMethod method, Object body) {
        RestClient.RequestBodySpec spec = client.method(method).uri(uri);
        if (token != null) {
            spec.headers(headers -> headers.setBearerAuth(token));
        }
        if (body != null) {
            spec.contentType(
                    body instanceof org.springframework.util.MultiValueMap<?, ?>
                            ? MediaType.MULTIPART_FORM_DATA
                            : MediaType.APPLICATION_JSON);
            spec.body(body);
        }
        return spec.exchange((request, response) -> {
            byte[] raw = response.getBody().readAllBytes();
            return new Recording(
                    response.getStatusCode().value(),
                    response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                    raw.length == 0 ? mapper.createObjectNode() : mapper.readTree(raw));
        });
    }

    private record Recording(int status, String contentType, JsonNode body) {}
}
