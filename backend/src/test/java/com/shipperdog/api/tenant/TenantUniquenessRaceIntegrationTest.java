package com.shipperdog.api.tenant;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Tenant uniqueness under genuine concurrency (issue #9 guards, exercised
 * by issue #13's race discipline): two creations of the same organization
 * name or the same external id, submitted together, must resolve to one
 * 201 and one precisely-coded 409 — the losing request may fail either
 * the service pre-check or the database constraint, and the conflict
 * re-derivation must classify it on a FRESH transaction either way,
 * because PostgreSQL has aborted the loser's own transaction.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
class TenantUniquenessRaceIntegrationTest {

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

    @Test
    void concurrentDuplicateTenantNameYieldsOneCreateAndOneNamedConflict() throws Exception {
        UUID orgId = createOrg("Tenant Race Name Corp");
        String token = loginOfOrgRoleIn(orgId, "tenant-race-name@example.com");

        List<Recording> results = postTenantsConcurrently(
                token,
                Map.of(
                        "organizationId", orgId.toString(),
                        "name", "Raced Tenant",
                        "externalId", "crm-tenant-race-a"),
                Map.of(
                        "organizationId", orgId.toString(),
                        "name", "Raced Tenant",
                        "externalId", "crm-tenant-race-b"));

        assertThat(results.stream().map(r -> r.status).sorted().toList())
                .as("one creation wins; the loser receives the named conflict, never a 500")
                .containsExactly(201, 409);
        Recording loser =
                results.stream().filter(r -> r.status == 409).findFirst().orElseThrow();
        assertThat(loser.body.get("code").asText()).isEqualTo("TENANT_NAME_TAKEN");
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM tenants WHERE organization_id = ? AND name = ?",
                Integer.class,
                orgId,
                "Raced Tenant");
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateExternalIdYieldsOneCreateAndOneNamedConflict() throws Exception {
        UUID orgId = createOrg("Tenant Race Ext Corp");
        String token = loginOfOrgRoleIn(orgId, "tenant-race-ext@example.com");

        List<Recording> results = postTenantsConcurrently(
                token,
                Map.of(
                        "organizationId", orgId.toString(),
                        "name", "Raced Ext A",
                        "externalId", "crm-tenant-race-shared"),
                Map.of(
                        "organizationId", orgId.toString(),
                        "name", "Raced Ext B",
                        "externalId", "crm-tenant-race-shared"));

        assertThat(results.stream().map(r -> r.status).sorted().toList()).containsExactly(201, 409);
        Recording loser =
                results.stream().filter(r -> r.status == 409).findFirst().orElseThrow();
        assertThat(loser.body.get("code").asText()).isEqualTo("TENANT_EXTERNAL_ID_TAKEN");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private List<Recording> postTenantsConcurrently(String token, Map<String, Object> bodyA, Map<String, Object> bodyB)
            throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Recording> first = pool.submit(() -> gatedPost(barrier, token, bodyA));
            Future<Recording> second = pool.submit(() -> gatedPost(barrier, token, bodyB));
            return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private Recording gatedPost(CyclicBarrier barrier, String token, Map<String, Object> body) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        return exchange("/api/v1/tenants", token, "POST", body);
    }

    /** Creates one organization per test; repeated calls for the same name are a fixture bug. */
    private UUID createOrg(String orgName) {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        return UUID.fromString(exchange("/api/v1/organizations", devToken, "POST", Map.of("name", orgName))
                .expect(201)
                .get("id")
                .asText());
    }

    private String loginOfOrgRoleIn(UUID orgId, String email) {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        JsonNode invitation = exchange(
                        "/api/v1/auth/invitations",
                        devToken,
                        "POST",
                        Map.of("email", email, "organizationId", orgId.toString(), "role", "ORG_ADMIN"))
                .expect(201);
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
                        "Tenant Race User"))
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
