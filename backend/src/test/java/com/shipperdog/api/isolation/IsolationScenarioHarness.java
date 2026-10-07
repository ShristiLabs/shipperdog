package com.shipperdog.api.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.artifact.AndroidFixtures;
import com.shipperdog.api.identity.AuditCapture;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The scenario harness of the isolation & authorization suite (S7-05,
 * issue #34): one seeded, two-tenant world per test — an organization,
 * a tenant, an ANDROID application, versions with artifacts, tester
 * people with memberships, and access grants — provisioned live over
 * the public API exactly the way the platform's own administrators
 * would, never by writing rows directly (the harness proves the
 * product; fixtures that bypass it prove nothing).
 *
 * <p>Every suite class extends this harness and therefore shares ONE
 * Spring context and ONE container set (PostgreSQL, Redis for the
 * decision-input cache, and the GCS emulator): the suite's CI budget
 * (issue acceptance criterion: the whole suite must run in under five
 * minutes) is spent on scenarios, not on booting the stack per test.
 * Tests NEVER share world state — each provisions its own with a
 * unique slug, so the suite is order-independent and parallel-safe
 * within the shared context.
 *
 * <p><strong>The named entry point:</strong> every suite class
 * carries the {@code isolation-suite} JUnit tag (inherited from this
 * harness), so the whole suite runs as one named stage —
 * {@code mvn test -Dgroups=isolation-suite} — and CI runs it inside
 * {@code mvn verify} on every PR, blocking merge on any invariant
 * regression.
 *
 * <p><strong>How to add a scenario</strong> (the documented recipe,
 * issue acceptance criterion 6): provision the world, mutate it
 * through the public API, and observe through the surface under
 * proof —
 * <ol>
 *   <li>{@code IsolatedTenant side = world.tenantA();} (or
 *       {@link #tenant(String)} for a third party) — each side is an
 *       org + tenant + application + version with a platform-admin
 *       token, a tenant-admin login, and a linked tester persona.</li>
 *   <li>Shape the state: {@link #uploadArtifact}, {@link #grant},
 *       {@link #revokeGrant}, or any public API call through
 *       {@link #exchange}/{@link #get}/{@link #post}.</li>
 *   <li>Assert the invariant over EVERY surface it governs — add the
 *       endpoint to {@link IsolationEndpointRegistry} if it is a new
 *       read/download route, so the cross-tenant matrix and the
 *       OpenAPI completeness guard pick it up automatically.</li>
 * </ol>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef",
            "shipperdog.authorization-cache.enabled=true"
        })
@Tag("isolation-suite")
@Import(AuditCapture.class)
abstract class IsolationScenarioHarness {

    protected static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    protected static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";
    protected static final String PASSWORD = "a-perfectly-valid-password";

    // -----------------------------------------------------------------------
    // The suite's SINGLETON container set: the whole harness family
    // shares ONE Spring context, and a cached context must never
    // outlive its containers. The JUnit @Testcontainers per-class
    // lifecycle (stop after each class) would break the cached
    // context's connections for every following class, so the three
    // containers start ONCE in this class's static initializer and
    // live for the whole surefire JVM (Ryuk reaps them at exit);
    // the context wires to them through explicit dynamic properties
    // instead of @ServiceConnection's per-class field scanning.
    // -----------------------------------------------------------------------

    /** The compose GCS emulator image, in a container (ADR-0003). */
    static final GenericContainer<?> GCS = new GenericContainer<>(
                    DockerImageName.parse("fsouza/fake-gcs-server:1.56.1"))
            .withCommand("-scheme", "http", "-host", "0.0.0.0", "-port", "4443")
            .withExposedPorts(4443);

    /** The decision-input cache's own store: real Redis (S7-03). */
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:8-alpine")).withExposedPorts(6379);

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    static {
        GCS.start();
        REDIS.start();
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void suiteInfrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> String.valueOf(REDIS.getMappedPort(6379)));
        registry.add(
                "shipperdog.artifact.storage.host", () -> "http://" + GCS.getHost() + ":" + GCS.getMappedPort(4443));
    }

    private static final AtomicInteger SLUG_COUNTER = new AtomicInteger();

    private static final AtomicInteger VERSION_CODE_COUNTER = new AtomicInteger(10_000);

    /**
     * A distinct versionCode per fixture: the manifest must agree with
     * the version record on name (VERSION_MATCH) and each upload
     * carries a fresh code, exactly like real releases.
     */
    private static byte[] androidFixture(String versionName) throws Exception {
        return AndroidFixtures.signedApk(versionName, String.valueOf(VERSION_CODE_COUNTER.incrementAndGet()));
    }

    @LocalServerPort
    int port;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected AuditCapture audit;

    @Autowired
    protected MeterRegistry meters;

    @Autowired
    protected ObjectMapper mapper;

    protected RestClient client;

    @BeforeEach
    void harnessSetUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        audit.reset();
    }

    // -----------------------------------------------------------------------
    // The seeded world
    // -----------------------------------------------------------------------

    /**
     * One side of the isolation world: an organization with its tenant,
     * an ANDROID application with its first version and a READY
     * artifact, a named tester group, a TENANT_ADMIN persona, an
     * ORG_ADMIN persona, and a linked tester person holding an ACTIVE
     * membership — the shape every invariant scenario addresses. A
     * unique slug keeps concurrent tests' rows disjoint.
     */
    protected record IsolatedTenant(
            String slug,
            String platformAdminToken,
            UUID organizationId,
            UUID tenantId,
            UUID applicationId,
            UUID versionId,
            UUID artifactId,
            UUID groupId,
            String tenantAdminToken,
            UUID tenantAdminUserId,
            String orgAdminToken,
            UUID orgAdminUserId,
            String testerEmail,
            UUID testerId,
            String testerToken) {}

    /** Two disjoint sides, freshly provisioned: the INV-001 stage. */
    protected record World(IsolatedTenant sideA, IsolatedTenant sideB) {}

    /** Provisions the canonical two-tenant world with unique slugs. */
    protected World world() throws Exception {
        int run = SLUG_COUNTER.incrementAndGet();
        return new World(tenant("alpha-" + run), tenant("beta-" + run));
    }

    /**
     * Provisions one side: org (created by the platform admin), tenant,
     * ANDROID application, first version, a TENANT_ADMIN persona, and a
     * linked tester person holding an ACTIVE membership — the seed shape
     * of every grant/decision scenario.
     */
    protected IsolatedTenant tenant(String slug) throws Exception {
        String platformAdmin = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        UUID organizationId = UUID.fromString(
                post(platformAdmin, "/api/v1/organizations", Map.of("name", "Suite " + slug + " Corp"), 201)
                        .get("id")
                        .asText());
        UUID tenantId = UUID.fromString(post(
                        platformAdmin,
                        "/api/v1/tenants",
                        Map.of(
                                "organizationId", organizationId.toString(),
                                "name", "Suite " + slug + " Bank",
                                "externalId", "ext-suite-" + slug),
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
                                "Suite " + slug + " App",
                                "platforms",
                                List.of("ANDROID"),
                                "packageName",
                                AndroidFixtures.PACKAGE),
                        201)
                .get("id")
                .asText());
        UUID versionId = createVersion(platformAdmin, applicationId, AndroidFixtures.VERSION_NAME);
        UUID artifactId = readyArtifact(platformAdmin, versionId, AndroidFixtures.VERSION_NAME);

        // The tenant-admin persona: an orgless user granted TENANT_ADMIN
        // on the tenant through the public role-assignment surface.
        String adminEmail = "suite-" + slug + "-admin@example.com";
        UUID adminId = userIdByEmail(insertOrglessUser(adminEmail));
        put(
                platformAdmin,
                "/api/v1/tenants/" + tenantId + "/members/" + adminId + "/role",
                Map.of("role", "TENANT_ADMIN"),
                200);
        String adminToken = accessToken(login(adminEmail, PASSWORD));

        // The org-admin persona: an ORG_ADMIN member of the owning
        // organization through the org-scoped invitation surface (the
        // established provisioning pattern — the member row is born
        // with the role at registration).
        String orgAdminEmail = "suite-" + slug + "-orgadmin@example.com";
        JsonNode orgAdminInvitation = post(
                platformAdmin,
                "/api/v1/auth/invitations",
                Map.of("email", orgAdminEmail, "organizationId", organizationId.toString(), "role", "ORG_ADMIN"),
                201);
        client.post()
                .uri("/api/v1/auth/register")
                .body(Map.of(
                        "invitationToken",
                        orgAdminInvitation.get("invitationToken").asString(),
                        "email",
                        orgAdminEmail,
                        "password",
                        PASSWORD,
                        "fullName",
                        "Suite Org Admin"))
                .retrieve()
                .body(String.class);
        UUID orgAdminId = userIdByEmail(orgAdminEmail);
        String orgAdminToken = accessToken(login(orgAdminEmail, PASSWORD));

        // A named tester group of the tenant (the seeded defaults exist
        // too; a named one keeps the group-grain probes unambiguous).
        // Group management is an app-scope permission the ORG_ADMIN
        // persona holds (the RBAC matrix), so the org admin creates it.
        UUID groupId = UUID.fromString(post(
                        orgAdminToken,
                        "/api/v1/tester-groups",
                        Map.of("tenantId", tenantId.toString(), "name", "Suite " + slug + " Pilots"),
                        201)
                .get("id")
                .asText());

        // The tester persona: invited, registered, linked to the tenant.
        String testerEmail = "suite-" + slug + "-tester@example.com";
        JsonNode invited = post(
                platformAdmin,
                "/api/v1/testers",
                Map.of(
                        "organizationId",
                        organizationId.toString(),
                        "email",
                        testerEmail,
                        "fullName",
                        "Suite " + slug + " Tester"),
                201);
        client.post()
                .uri("/api/v1/auth/register")
                .body(Map.of(
                        "invitationToken",
                        invited.get("invitation").get("invitationToken").asString(),
                        "email",
                        testerEmail,
                        "password",
                        PASSWORD,
                        "fullName",
                        "Suite " + slug + " Tester"))
                .retrieve()
                .body(String.class);
        post(
                platformAdmin,
                "/api/v1/tenants/" + tenantId + "/testers",
                Map.of("testerId", invited.get("id").asText()),
                201);
        UUID testerId = UUID.fromString(invited.get("id").asText());
        return new IsolatedTenant(
                slug,
                platformAdmin,
                organizationId,
                tenantId,
                applicationId,
                versionId,
                artifactId,
                groupId,
                adminToken,
                adminId,
                orgAdminToken,
                orgAdminId,
                testerEmail,
                testerId,
                accessToken(login(testerEmail, PASSWORD)));
    }

    // -----------------------------------------------------------------------
    // State shaping
    // -----------------------------------------------------------------------

    /** Creates a version under the application through the public API. */
    protected UUID createVersion(String token, UUID applicationId, String versionName) {
        return UUID.fromString(post(
                        token,
                        "/api/v1/applications/" + applicationId + "/versions",
                        Map.of("versionName", versionName),
                        201)
                .get("id")
                .asText());
    }

    /** Replaces a version's tag set through the public API. */
    protected void setTags(String token, UUID applicationId, UUID versionId, String... tags) {
        put(
                token,
                "/api/v1/applications/" + applicationId + "/versions/" + versionId + "/tags",
                Map.of("tags", List.of(tags)),
                200);
    }

    /** Uploads the valid Android fixture for the seeded version and waits for READY. */
    protected UUID readyArtifact(String token, UUID versionId, String versionName) throws Exception {
        byte[] bytes = androidFixture(versionName);
        org.springframework.util.MultiValueMap<String, Object> parts =
                new org.springframework.util.LinkedMultiValueMap<>();
        parts.add("version_id", versionId.toString());
        parts.add("platform", "ANDROID");
        parts.add("file", new org.springframework.core.io.ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "suite-" + versionName + ".apk";
            }
        });
        Recording uploaded = exchange("/api/v1/artifacts", token, HttpMethod.POST, parts);
        assertThat(uploaded.status).as("artifact upload: %s", uploaded.body).isEqualTo(201);
        UUID artifactId = UUID.fromString(uploaded.body.get("id").asText());
        long deadline = System.currentTimeMillis() + 20_000;
        String status = "";
        while (System.currentTimeMillis() < deadline) {
            status = get(token, artifactUri(artifactId, versionId), 200)
                    .get("status")
                    .asString();
            if ("READY".equals(status)) {
                return artifactId;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("artifact fixture never settled READY (last: " + status + ")");
    }

    /** Grants a tester one explicit version (never-expires by default). */
    protected UUID grantExplicitVersion(
            String adminToken, UUID tenantId, UUID testerId, UUID applicationId, UUID versionId) {
        return grantExplicitVersion(adminToken, tenantId, testerId, applicationId, versionId, null);
    }

    /** Grants a tester one explicit version with an optional expiry. */
    protected UUID grantExplicitVersion(
            String adminToken, UUID tenantId, UUID testerId, UUID applicationId, UUID versionId, String expiresAt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("testerId", testerId.toString());
        body.put("applicationId", applicationId.toString());
        body.put("accessType", "EXPLICIT_VERSION");
        body.put("versionId", versionId.toString());
        if (expiresAt != null) {
            body.put("expiresAt", expiresAt);
        }
        body.put("reason", "suite scenario grant");
        return UUID.fromString(post(adminToken, grantsPath(tenantId), body, 201)
                .get("grant")
                .get("id")
                .asText());
    }

    /** Grants a tester the newest N versions window. */
    protected void grantLatestN(String adminToken, UUID tenantId, UUID testerId, UUID applicationId, int n) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("testerId", testerId.toString());
        body.put("applicationId", applicationId.toString());
        body.put("accessType", "LATEST_N");
        body.put("latestN", n);
        body.put("reason", "suite scenario window");
        post(adminToken, grantsPath(tenantId), body, 201);
    }

    /** Revokes a grant through the S7-04 DELETE route (reason mandatory). */
    protected void revokeGrant(String adminToken, UUID tenantId, UUID grantId, String reason) {
        Recording revoked =
                exchange(grantUri(tenantId, grantId) + "?reason=" + reason, adminToken, HttpMethod.DELETE, null);
        assertThat(revoked.status).as("revoke %s: %s", grantId, revoked.body).isEqualTo(204);
    }

    // -----------------------------------------------------------------------
    // The surfaces under proof (URI builders)
    // -----------------------------------------------------------------------

    protected static String grantsPath(UUID tenantId) {
        return "/api/v1/tenants/" + tenantId + "/authorization-grants";
    }

    protected static String grantUri(UUID tenantId, UUID grantId) {
        return grantsPath(tenantId) + "/" + grantId;
    }

    protected static String downloadUri(UUID tenantId, UUID versionId) {
        return "/api/v1/authorization/decisions/download?tenantId="
                + tenantId
                + "&versionId="
                + versionId
                + "&platform=ANDROID";
    }

    protected static String eligibilityUri(UUID tenantId, UUID applicationId) {
        return "/api/v1/authorization/decisions/eligibility?tenantId=" + tenantId + "&applicationId=" + applicationId;
    }

    protected static String artifactUri(UUID artifactId, UUID versionId) {
        return "/api/v1/artifacts/" + artifactId + "?version_id=" + versionId;
    }

    // -----------------------------------------------------------------------
    // HTTP plumbing (the integration suites' shared shape)
    // -----------------------------------------------------------------------

    /** A raw exchange outcome: status plus the parsed body (null on empty). */
    protected record Recording(int status, JsonNode body) {}

    protected String login(String email, String password) {
        return client.post()
                .uri("/api/v1/auth/login")
                .body(Map.of("email", email, "password", password))
                .retrieve()
                .body(String.class);
    }

    protected String accessToken(String tokensJson) {
        try {
            return mapper.readTree(tokensJson).get("accessToken").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected JsonNode post(String token, String uri, Map<String, Object> body, int expectedStatus) {
        Recording response = exchange(uri, token, HttpMethod.POST, body);
        assertThat(response.status).as("POST %s: %s", uri, response.body).isEqualTo(expectedStatus);
        return response.body;
    }

    protected JsonNode put(String token, String uri, Map<String, Object> body, int expectedStatus) {
        Recording response = exchange(uri, token, HttpMethod.PUT, body);
        assertThat(response.status).as("PUT %s: %s", uri, response.body).isEqualTo(expectedStatus);
        return response.body;
    }

    protected JsonNode get(String token, String uri, int expectedStatus) {
        Recording response = getRaw(token, uri);
        assertThat(response.status).as("GET %s: %s", uri, response.body).isEqualTo(expectedStatus);
        return response.body;
    }

    protected Recording getRaw(String token, String uri) {
        return exchange(uri, token, HttpMethod.GET, null);
    }

    protected Recording postRaw(String token, String uri, Map<String, Object> body) {
        return exchange(uri, token, HttpMethod.POST, body);
    }

    protected Recording exchange(String uri, String token, HttpMethod method, Object body) {
        // Fully-built URIs, never templates: query strings carry
        // pre-encoded values (the integration suites' discipline).
        RestClient.RequestBodySpec spec = client.method(method).uri(java.net.URI.create(uri));
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
            if (raw.length == 0) {
                return new Recording(response.getStatusCode().value(), mapper.createObjectNode());
            }
            try {
                return new Recording(response.getStatusCode().value(), mapper.readTree(raw));
            } catch (Exception parseFailure) {
                // Storage-guess probes may hit the servlet's HTML error
                // page; the status is the assertion, the text is kept
                // verbatim for diagnostics.
                return new Recording(
                        response.getStatusCode().value(),
                        mapper.createObjectNode().put("raw", new String(raw, java.nio.charset.StandardCharsets.UTF_8)));
            }
        });
    }

    /** Registers an orgless user through a platform invitation (a real login works). */
    protected String insertOrglessUser(String email) {
        JsonNode invitation = post(
                accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD)),
                "/api/v1/auth/invitations",
                Map.of("email", email),
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
                        "Suite User"))
                .retrieve()
                .body(String.class);
        return email;
    }

    protected UUID userIdByEmail(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
    }

    protected UUID userIdOfTester(UUID testerId) {
        return jdbc.queryForObject("SELECT user_id FROM testers WHERE id = ?", UUID.class, testerId);
    }

    protected UUID platformAdminUserId() {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, DEV_ADMIN_EMAIL);
    }

    protected double cacheHits(String family) {
        var counter = meters.find(com.shipperdog.api.config.MetricNames.AUTHORIZATION_DECISION_CACHE)
                .tag("family", family)
                .tag("outcome", "hit")
                .counter();
        return counter == null ? 0 : counter.count();
    }

    protected Map<String, Object> problemOf(Recording recording) {
        Map<String, Object> shape = new HashMap<>();
        shape.put("status", recording.status);
        shape.put("code", recording.body.get("code").asString());
        return shape;
    }

    protected String codeOf(Recording recording) {
        return recording.body.get("code").asString();
    }

    /**
     * Strips the per-request trace id and the instance path (which
     * carries the probe's own resource ids) so two problem bodies
     * compare by SHAPE: a concealed denial and a genuine miss must be
     * indistinguishable except for the request-identifying fields.
     */
    protected String withoutTraceId(String body) {
        return body == null
                ? ""
                : body.replaceAll("\"trace_id\"\\s*:\\s*\"[^\"]*\"", "\"trace_id\":\"-\"")
                        .replaceAll("\"instance\"\\s*:\\s*\"[^\"]*\"", "\"instance\":\"-\"");
    }
}
