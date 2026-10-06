package com.shipperdog.api.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.AuditCapture;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
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
import org.springframework.context.annotation.Import;
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
 * The concurrency core of member assignment (issue #28, the dead-catch
 * fix carried from issues #26/#27): simultaneous same-role assignments
 * of one user must collapse onto one membership row — one caller
 * creates, every loser answers the idempotent 200 re-derived on a
 * fresh transaction, never the 500 the former in-transaction catch
 * produced (PostgreSQL aborts the racing transaction; a normal return
 * out of the catch failed the commit with UnexpectedRollbackException).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
@Import(AuditCapture.class)
class TenantMemberRaceIntegrationTest {

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

    @Test
    void concurrentSameRoleAssignmentsCollapseToOneRow() throws Exception {
        String adminToken = loginOfOrgAdmin("m-race-admin@example.com", "Member Race Corp");
        UUID tenantId = createTenant(adminToken, organizationIdByName("Member Race Corp"), "Race Bank", "crm-m-race-1");
        String memberEmail = insertOrglessUser("m-race-member@example.com");
        UUID memberId = userIdByEmail(memberEmail);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Recording>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return exchange(
                            "/api/v1/tenants/" + tenantId + "/members",
                            adminToken,
                            "POST",
                            Map.of("userId", memberId.toString(), "role", "TENANT_ADMIN"),
                            null);
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Recording> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS).status());
            }
            assertThat(statuses)
                    .as("every racing assignment answers 201 or the idempotent 200, never a 500 "
                            + "(the former in-transaction catch dead-code finding)")
                    .containsOnly(201, 200);
            assertThat(statuses.stream().filter(status -> status == 201).count())
                    .as("exactly one caller created the membership")
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM tenant_members WHERE tenant_id = ? AND user_id = ?",
                            Integer.class,
                            tenantId,
                            memberId))
                    .isEqualTo(1);
            assertThat(audit.eventsOfType(AuditEventType.TENANT_MEMBER_ADDED))
                    .as("one audit event for one mutation")
                    .hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private UUID createTenant(String token, UUID organizationId, String name, String externalId) {
        JsonNode created = post(
                token,
                "/api/v1/tenants",
                Map.of("organizationId", organizationId.toString(), "name", name, "externalId", externalId),
                201);
        return UUID.fromString(created.get("id").asText());
    }

    private UUID organizationIdByName(String name) {
        return jdbc.queryForObject("SELECT id FROM organizations WHERE name = ?", UUID.class, name);
    }

    private UUID userIdByEmail(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
    }

    /** Registers an orgless user through a plain invitation; returns the email. */
    private String insertOrglessUser(String email) {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        JsonNode invitation = post(devToken, "/api/v1/auth/invitations", Map.of("email", email), 201);
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
                        "Race Test User"))
                .retrieve()
                .body(String.class);
        return email;
    }

    /** Creates an organization as dev admin and registers its named ORG_ADMIN. */
    private String loginOfOrgAdmin(String email, String orgName) {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        UUID orgId = UUID.fromString(post(devToken, "/api/v1/organizations", Map.of("name", orgName), 201)
                .get("id")
                .asText());
        JsonNode invitation = post(
                devToken,
                "/api/v1/auth/invitations",
                Map.of("email", email, "organizationId", orgId.toString(), "role", "ORG_ADMIN"),
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
                        "Org Admin"))
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

    private JsonNode post(String bearer, String uri, Map<String, Object> body, int expectedStatus) {
        Recording response = exchange(uri, bearer, "POST", body, null);
        assertThat(response.status).as("POST %s", uri).isEqualTo(expectedStatus);
        return response.body;
    }

    private Recording exchange(
            String uri, String bearer, String method, Map<String, Object> body, String idempotencyKey) {
        RestClient.RequestBodySpec spec =
                client.method(HttpMethod.valueOf(method)).uri(uri);
        if (bearer != null) {
            spec.headers(headers -> headers.setBearerAuth(bearer));
        }
        if (idempotencyKey != null) {
            spec.headers(headers -> headers.set("Idempotency-Key", idempotencyKey));
        }
        if (body != null) {
            spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((request, response) -> {
            byte[] bytes = response.getBody().readAllBytes();
            return new Recording(
                    response.getStatusCode().value(),
                    response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                    bytes.length == 0 ? mapper.createObjectNode() : mapper.readTree(bytes),
                    Boolean.parseBoolean(response.getHeaders().getFirst("Idempotency-Replayed")));
        });
    }

    private record Recording(int status, String contentType, JsonNode body, boolean replayed) {}
}
