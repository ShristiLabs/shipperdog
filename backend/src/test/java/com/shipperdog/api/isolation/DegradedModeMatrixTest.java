package com.shipperdog.api.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

/**
 * The degraded-mode half of the isolation matrix (S7-05, issue #34;
 * the #33 follow-up folded here): with Redis unreachable — every cache
 * operation failing fast, the cache permanently DEGRADED — the full
 * allow/deny matrix still holds: the cache can only make decisions
 * SLOWER, never DIFFERENT (the S7-03 contract). This class cannot
 * share the harness context (its Redis configuration is deliberately
 * broken), so it carries its own container set — the degraded context
 * the S7-03 suite established — and replays the matrix's core
 * allow/deny/concealment probes against it.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef",
            // A port with no listener: connection refused on every
            // operation — the cache's permanent degraded state.
            "spring.data.redis.port=16379",
            "shipperdog.authorization-cache.enabled=true"
        })
@Testcontainers
@org.junit.jupiter.api.Tag("isolation-suite")
@Import(com.shipperdog.api.identity.AuditCapture.class)
class DegradedModeMatrixTest {

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
    private ObjectMapper mapper;

    private RestClient client;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /**
     * The allow/deny/concealment core in degraded mode: grants still
     * authorize, non-grants still deny concealed, revocation is still
     * immediate (there is no cache to be stale), and the cross-tenant
     * denial still renders the genuine miss's exact shape.
     */
    @Test
    void theAllowDenyAndConcealmentMatrixHoldsWithTheCachePermanentlyDegraded() throws Exception {
        // Inline world provisioning (the harness context is unusable
        // here by design); one side suffices plus a foreign tester.
        String platformAdmin = accessToken(login("dev@shipperdog.local", "shipperdog-dev-admin"));
        UUID orgId = id(post(platformAdmin, "/api/v1/organizations", "{ \"name\": \"Degraded Corp\" }", 201));
        UUID tenantId = id(post(
                platformAdmin,
                "/api/v1/tenants",
                "{ \"organizationId\": \""
                        + orgId
                        + "\", \"name\": \"Degraded Bank\", \"externalId\": \"ext-degraded-1\" }",
                201));
        UUID applicationId = id(post(
                platformAdmin,
                "/api/v1/applications",
                "{ \"tenantId\": \""
                        + tenantId
                        + "\", \"name\": \"Degraded App\", \"platforms\": [\"ANDROID\"], \"packageName\": \""
                        + com.shipperdog.api.artifact.AndroidFixtures.PACKAGE
                        + "\" }",
                201));
        UUID versionId = id(post(
                platformAdmin,
                "/api/v1/applications/" + applicationId + "/versions",
                "{ \"versionName\": \"1.0.0\" }",
                201));

        // A READY artifact (a download decision must have bytes to
        // authorize): upload the signed fixture and poll to settled.
        org.springframework.util.MultiValueMap<String, Object> parts =
                new org.springframework.util.LinkedMultiValueMap<>();
        parts.add("version_id", versionId.toString());
        parts.add("platform", "ANDROID");
        parts.add(
                "file",
                new org.springframework.core.io.ByteArrayResource(
                        com.shipperdog.api.artifact.AndroidFixtures.signedApk("1.0.0", "99001")) {
                    @Override
                    public String getFilename() {
                        return "degraded.apk";
                    }
                });
        Recording uploaded = exchange("/api/v1/artifacts", platformAdmin, "POST", null, parts);
        assertThat(uploaded.status()).as("artifact upload: %s", uploaded.body()).isEqualTo(201);
        UUID artifactId = node(uploaded.body()).get("id") == null
                ? null
                : UUID.fromString(node(uploaded.body()).get("id").asText());
        long deadline = System.currentTimeMillis() + 20_000;
        String status = "";
        while (System.currentTimeMillis() < deadline) {
            Recording artifact = exchange(
                    "/api/v1/artifacts/" + artifactId + "?version_id=" + versionId, platformAdmin, "GET", null);
            status = node(artifact.body()).get("status") == null
                    ? ""
                    : node(artifact.body()).get("status").asString();
            if ("READY".equals(status)) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(status).as("the degraded-world artifact must settle READY").isEqualTo("READY");

        String adminEmail = "degraded-admin@example.com";
        registerOrgless(platformAdmin, adminEmail);
        UUID adminId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, adminEmail);
        put(
                platformAdmin,
                "/api/v1/tenants/" + tenantId + "/members/" + adminId + "/role",
                "{ \"role\": \"TENANT_ADMIN\" }",
                200);
        String admin = accessToken(login(adminEmail, "a-perfectly-valid-password"));

        String testerEmail = "degraded-tester@example.com";
        String invited = post(
                platformAdmin,
                "/api/v1/testers",
                "{ \"organizationId\": \"" + orgId + "\", \"email\": \"" + testerEmail
                        + "\", \"fullName\": \"Degraded Tester\" }",
                201);
        client.post()
                .uri("/api/v1/auth/register")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body("{ \"invitationToken\": \""
                        + node(invited).get("invitation").get("invitationToken").asString()
                        + "\", \"email\": \"" + testerEmail
                        + "\", \"password\": \"a-perfectly-valid-password\", \"fullName\": \"Degraded Tester\" }")
                .retrieve()
                .body(String.class);
        post(
                platformAdmin,
                "/api/v1/tenants/" + tenantId + "/testers",
                "{ \"testerId\": \"" + node(invited).get("id").asText() + "\" }",
                201);
        UUID testerId = UUID.fromString(node(invited).get("id").asText());
        String tester = accessToken(login(testerEmail, "a-perfectly-valid-password"));

        String download = "/api/v1/authorization/decisions/download?tenantId=" + tenantId + "&versionId=" + versionId
                + "&platform=ANDROID";
        String eligibility =
                "/api/v1/authorization/decisions/eligibility?tenantId=" + tenantId + "&applicationId=" + applicationId;

        // Deny without a grant — concealed, byte-shaped like the miss.
        Recording denied = exchange(download, tester, "GET", null);
        Recording miss = exchange(
                "/api/v1/authorization/decisions/download?tenantId=" + tenantId + "&versionId=" + UUID.randomUUID()
                        + "&platform=ANDROID",
                tester,
                "GET",
                null);
        assertThat(denied.status()).isEqualTo(404);
        assertThat(withoutTrace(denied.body())).isEqualTo(withoutTrace(miss.body()));

        // Allow with one (live evaluation; the loader answers because
        // the cache cannot).
        post(
                admin,
                "/api/v1/tenants/" + tenantId + "/authorization-grants",
                "{ \"testerId\": \""
                        + testerId
                        + "\", \"applicationId\": \""
                        + applicationId
                        + "\", \"accessType\": \"EXPLICIT_VERSION\", \"versionId\": \""
                        + versionId
                        + "\", \"reason\": \"degraded-mode probe\" }",
                201);
        assertThat(exchange(download, tester, "GET", null).status()).isEqualTo(200);
        assertThat(node(exchange(eligibility, tester, "GET", null).body())
                        .get("entries")
                        .size())
                .isEqualTo(1);

        // Revocation is still immediate (nothing was cached).
        UUID grantId = jdbc.queryForObject(
                "SELECT id FROM version_access WHERE tenant_id = ? AND tester_id = ?", UUID.class, tenantId, testerId);
        assertThat(exchange(
                                "/api/v1/tenants/" + tenantId + "/authorization-grants/" + grantId + "?reason=degraded",
                                admin,
                                "DELETE",
                                null)
                        .status())
                .isEqualTo(204);
        assertThat(exchange(download, tester, "GET", null).status()).isEqualTo(404);

        // Cross-tenant concealment degrades identically: a foreign
        // admin (a second tenant of another org) reads nothing.
        String foreignOrgAdmin = registerOrgAdminOfSecondOrg(platformAdmin);
        Recording foreign = exchange("/api/v1/tenants/" + tenantId, foreignOrgAdmin, "GET", null);
        Recording foreignMiss = exchange("/api/v1/tenants/" + UUID.randomUUID(), foreignOrgAdmin, "GET", null);
        assertThat(foreign.status()).isEqualTo(404);
        assertThat(withoutTrace(foreign.body())).isEqualTo(withoutTrace(foreignMiss.body()));
    }

    private String registerOrgAdminOfSecondOrg(String platformAdmin) throws Exception {
        UUID foreignOrg =
                id(post(platformAdmin, "/api/v1/organizations", "{ \"name\": \"Degraded Foreign Corp\" }", 201));
        // The org-scoped invitation pattern (the member row is born
        // ORG_ADMIN at registration): the role-change route requires an
        // existing member.
        String email = "degraded-foreign-admin@example.com";
        String invitation = post(
                platformAdmin,
                "/api/v1/auth/invitations",
                "{ \"email\": \"" + email + "\", \"organizationId\": \"" + foreignOrg + "\", \"role\": \"ORG_ADMIN\" }",
                201);
        client.post()
                .uri("/api/v1/auth/register")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body("{ \"invitationToken\": \""
                        + node(invitation).get("invitationToken").asString()
                        + "\", \"email\": \"" + email
                        + "\", \"password\": \"a-perfectly-valid-password\", \"fullName\": \"Degraded Foreign\" }")
                .retrieve()
                .body(String.class);
        return accessToken(login(email, "a-perfectly-valid-password"));
    }

    private void registerOrgless(String platformAdmin, String email) {
        String invitation = post(platformAdmin, "/api/v1/auth/invitations", "{ \"email\": \"" + email + "\" }", 201);
        client.post()
                .uri("/api/v1/auth/register")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body("{ \"invitationToken\": \""
                        + node(invitation).get("invitationToken").asString()
                        + "\", \"email\": \"" + email
                        + "\", \"password\": \"a-perfectly-valid-password\", \"fullName\": \"Degraded User\" }")
                .retrieve()
                .body(String.class);
    }

    // ------------------------------------------------------------------
    // Minimal HTTP plumbing (this context cannot use the harness)
    // ------------------------------------------------------------------

    private record Recording(int status, String body) {}

    private String login(String email, String password) {
        return client.post()
                .uri("/api/v1/auth/login")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body("{ \"email\": \"" + email + "\", \"password\": \"" + password + "\" }")
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

    private String post(String token, String uri, String json, int expectedStatus) {
        Recording response = exchange(uri, token, "POST", json);
        assertThat(response.status()).as("POST %s: %s", uri, response.body()).isEqualTo(expectedStatus);
        return response.body();
    }

    private String put(String token, String uri, String json, int expectedStatus) {
        Recording response = exchange(uri, token, "PUT", json);
        assertThat(response.status()).as("PUT %s: %s", uri, response.body()).isEqualTo(expectedStatus);
        return response.body();
    }

    private Recording exchange(String uri, String token, String method, String json) {
        return exchange(uri, token, method, json, null);
    }

    private Recording exchange(
            String uri,
            String token,
            String method,
            String json,
            org.springframework.util.MultiValueMap<String, Object> parts) {
        RestClient.RequestBodySpec spec = client.method(org.springframework.http.HttpMethod.valueOf(method))
                .uri(java.net.URI.create(uri));
        if (token != null) {
            spec.headers(headers -> headers.setBearerAuth(token));
        }
        if (json != null) {
            spec.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(json);
        } else if (parts != null) {
            spec.contentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA)
                    .body(parts);
        }
        return spec.exchange((request, response) -> new Recording(
                response.getStatusCode().value(),
                new String(response.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
    }

    private tools.jackson.databind.JsonNode node(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private UUID id(String json) {
        return UUID.fromString(node(json).get("id").asText());
    }

    /** Strips the request-identifying fields so two problem bodies compare by shape. */
    private static String withoutTrace(String body) {
        return body.replaceAll("\"trace_id\"\\s*:\\s*\"[^\"]*\"", "\"trace_id\":\"-\"")
                .replaceAll("\"instance\"\\s*:\\s*\"[^\"]*\"", "\"instance\":\"-\"");
    }
}
