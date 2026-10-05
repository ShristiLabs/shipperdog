package com.shipperdog.api.version;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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
 * Race and validation depth for the version layer (issue #17): the
 * per-application name uniqueness must stay precise under genuinely
 * concurrent creation — one winner, one named 409, never a 500 from a
 * constraint violation leaking past the translation — and the V12
 * database checks (semver name shape, sort-key shape) hold as the last
 * line of defense for any writer that bypasses the service.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
class VersionRegistryConcurrencyIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

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
    // Concurrent duplicate creation (AC1 under race)
    // -----------------------------------------------------------------------

    @Test
    void concurrentDuplicateVersionNameYieldsOneCreateAndOneNamedConflict() throws Exception {
        UUID orgId = createOrg("Ver Race Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "ver-race-admin@example.com", "ORG_ADMIN");
        String token = loginOfOrgRoleIn(orgId, "ver-race@example.com", "APP_OWNER");
        UUID tenantId = createTenant(adminToken, orgId, "Ver Race Bank", "crm-ver-race-1");
        UUID appId = createTenantApp(token, tenantId, "Raced", "com.verrace.app");

        List<Recording> results = postConcurrently(
                token, versionsUri(appId), Map.of("versionName", "1.0.0"), Map.of("versionName", "1.0.0"));

        assertThat(results.stream().map(r -> r.status).sorted().toList())
                .as("exactly one creation wins; the loser gets the precise conflict, never a 500")
                .containsExactly(201, 409);
        Recording loser =
                results.stream().filter(r -> r.status == 409).findFirst().orElseThrow();
        assertThat(loser.body.get("code").asText()).isEqualTo("VERSION_NAME_TAKEN");
        Integer rows =
                jdbc.queryForObject("SELECT count(*) FROM versions WHERE application_id = ?", Integer.class, appId);
        assertThat(rows)
                .as("the index admits exactly one row per application and name")
                .isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // V12 database checks: defense in depth for non-service writers
    // -----------------------------------------------------------------------

    @Test
    void databaseChecksRejectNonSemverNamesAndMalformedSortKeys() throws Exception {
        UUID orgId = createOrg("Ver Db Check Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "ver-dbcheck-admin@example.com", "ORG_ADMIN");
        String token = loginOfOrgRoleIn(orgId, "ver-dbcheck@example.com", "APP_OWNER");
        UUID tenantId = createTenant(adminToken, orgId, "Ver Db Check Bank", "crm-ver-db-1");
        UUID appId = createTenantApp(token, tenantId, "Db Check", "com.verdbcheck.app");

        assertThatThrownBy(() -> insertVersion(appId, orgId, tenantId, "not-semver", "00001.00000.00000"))
                .as("ck_versions_version_name_semver holds at the database")
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertVersion(appId, orgId, tenantId, "1.0.0", "1.0.0"))
                .as("ck_versions_sort_key_shape holds at the database")
                .isInstanceOf(DataIntegrityViolationException.class);

        Integer count =
                jdbc.queryForObject("SELECT count(*) FROM versions WHERE application_id = ?", Integer.class, appId);
        assertThat(count).isEqualTo(0);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String versionsUri(UUID applicationId) {
        return "/api/v1/applications/" + applicationId + "/versions";
    }

    private void insertVersion(UUID applicationId, UUID organizationId, UUID tenantId, String name, String sortKey) {
        jdbc.update(
                "INSERT INTO versions (application_id, organization_id, tenant_id, version_name, sort_key)"
                        + " VALUES (?, ?, ?, ?, ?)",
                applicationId,
                organizationId,
                tenantId,
                name,
                sortKey);
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
    private String loginOfOrgRoleIn(UUID orgId, String email, String role) {
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
                        "a-perfectly-valid-password",
                        "fullName",
                        "Version Test User"))
                .retrieve()
                .body(String.class);
        return accessToken(login(email, "a-perfectly-valid-password"));
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

    /**
     * Posts both bodies from two threads released by a shared barrier, so
     * the two requests are in flight together: each may pass the service
     * pre-check before the other commits, which is exactly the race the
     * unique index and the flush-point translation exist for. Either
     * interleaving must produce the same external outcome.
     */
    private List<Recording> postConcurrently(
            String token, String uri, Map<String, Object> bodyA, Map<String, Object> bodyB) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Recording> first = pool.submit(() -> gatedPost(barrier, token, uri, bodyA));
            Future<Recording> second = pool.submit(() -> gatedPost(barrier, token, uri, bodyB));
            return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private Recording gatedPost(CyclicBarrier barrier, String token, String uri, Map<String, Object> body)
            throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        return exchange(uri, token, "POST", body);
    }

    private JsonNode post(String bearer, String uri, Map<String, Object> body, int expectedStatus) {
        return exchange(uri, bearer, "POST", body).expect(expectedStatus);
    }

    private Recording exchange(String uri, String bearer, String method, Map<String, Object> body) {
        RestClient.RequestBodySpec spec =
                client.method(HttpMethod.valueOf(method)).uri(uri);
        if (bearer != null) {
            spec.headers(headers -> headers.setBearerAuth(bearer));
        }
        if (body != null) {
            spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((request, response) -> {
            byte[] bytes = response.getBody().readAllBytes();
            return new Recording(
                    response.getStatusCode().value(),
                    response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                    bytes.length == 0 ? mapper.createObjectNode() : mapper.readTree(bytes));
        });
    }

    private record Recording(int status, String contentType, JsonNode body) {

        JsonNode expect(int expectedStatus) {
            assertThat(status).as("HTTP status for %s", body.toString()).isEqualTo(expectedStatus);
            return body;
        }
    }
}
