package com.shipperdog.api.tester;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.identity.AuditCapture;
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
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Issue #26 (S6-01), acceptance criterion 2 edge: an expired tester
 * invitation is refused with the uniform INVALID_INVITATION error and
 * durably marked EXPIRED (the REQUIRES_NEW marking must survive the
 * rollback the refusal throw causes — the S1-03 semantics, reused).
 * Runs with a zero TTL so every invitation is born expired.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef",
            "shipperdog.tester.invitation-ttl=PT0S"
        })
@Testcontainers
@Import({AuditCapture.class, CapturingEmailSender.class})
class TesterInvitationExpiryIntegrationTest {

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
    private ObjectMapper mapper;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void expiredInvitationIsRefusedUniformlyAndMarkedExpired() {
        String admin = loginOfOrgAdmin("t6-exp-admin@example.com", "Tester Expiry Corp");
        UUID orgId =
                jdbc.queryForObject("SELECT id FROM organizations WHERE name = ?", UUID.class, "Tester Expiry Corp");

        JsonNode created = post(
                admin,
                "/api/v1/testers",
                Map.of("organizationId", orgId.toString(), "email", "late@example.com", "fullName", "Late Tester"),
                201);
        UUID testerId = UUID.fromString(created.get("id").asText());
        String token = created.get("invitation").get("invitationToken").asText();

        Recording accept = exchange(
                "/api/v1/auth/register",
                null,
                "POST",
                Map.of(
                        "invitationToken",
                        token,
                        "email",
                        "late@example.com",
                        "password",
                        PASSWORD,
                        "fullName",
                        "Late Tester"));
        assertThat(accept.status).as("an expired invitation is dead").isEqualTo(400);
        assertThat(accept.body.get("code").asText()).isEqualTo("INVALID_INVITATION");
        assertThat(accept.contentType).startsWith("application/problem+json");

        Map<String, Object> invitationRow =
                jdbc.queryForMap("SELECT status FROM user_invitations WHERE tester_id = ?", testerId);
        assertThat(invitationRow.get("status"))
                .as("the EXPIRED marking survives the refusal (REQUIRES_NEW semantics)")
                .isEqualTo("EXPIRED");

        Map<String, Object> testerRow = jdbc.queryForMap("SELECT status FROM testers WHERE id = ?", testerId);
        assertThat(testerRow.get("status"))
                .as("the tester stays INVITED after a failed acceptance")
                .isEqualTo("INVITED");

        Recording retry = exchange(
                "/api/v1/auth/register",
                null,
                "POST",
                Map.of(
                        "invitationToken",
                        token,
                        "email",
                        "late@example.com",
                        "password",
                        PASSWORD,
                        "fullName",
                        "Late Tester"));
        assertThat(retry.status)
                .as("the marked invitation refuses identically on retry")
                .isEqualTo(400);
    }

    private String loginOfOrgAdmin(String email, String orgName) {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        UUID orgId = UUID.fromString(exchange("/api/v1/organizations", devToken, "POST", Map.of("name", orgName), 201)
                .body
                .get("id")
                .asText());
        JsonNode invitation = post(
                devToken,
                "/api/v1/auth/invitations",
                Map.of("email", email, "organizationId", orgId.toString(), "role", "ORG_ADMIN"),
                201);
        exchange(
                "/api/v1/auth/register",
                null,
                "POST",
                Map.of(
                        "invitationToken",
                        invitation.get("invitationToken").asText(),
                        "email",
                        email,
                        "password",
                        PASSWORD,
                        "fullName",
                        "Org Admin"),
                201);
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
        Recording response = exchange(uri, bearer, "POST", body, expectedStatus);
        return response.body;
    }

    private Recording exchange(String uri, String bearer, String method, Map<String, Object> body, int expectedStatus) {
        Recording response = exchange(uri, bearer, method, body);
        assertThat(response.status).as("%s %s", method, uri).isEqualTo(expectedStatus);
        return response;
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

    private record Recording(int status, String contentType, JsonNode body) {}
}
