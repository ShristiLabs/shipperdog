package com.shipperdog.api.tester;

import static org.assertj.core.api.Assertions.assertThat;

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
 * The configurable half of issue #27's seeding acceptance criterion:
 * with {@code shipperdog.tester.default-groups} overridden, tenant
 * creation seeds exactly the configured list (not the PRD 8.9
 * defaults) — a deployment can shrink, grow, or empty the seed. Runs
 * in its own Spring context so the property override applies
 * everywhere (the default list's presence is covered by
 * {@code TesterGroupsIntegrationTest}).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef",
            "shipperdog.tester.default-groups=Smoke Ring A,Smoke Ring B"
        })
@Testcontainers
@Import(com.shipperdog.api.identity.AuditCapture.class)
class TesterGroupSeedingIntegrationTest {

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
    void tenantCreationSeedsTheConfiguredList() {
        String admin = loginOfOrgAdmin("tgs-admin@example.com", "Seeding Config Corp");
        UUID orgId = jdbc.queryForObject("SELECT id FROM organizations WHERE name = 'Seeding Config Corp'", UUID.class);
        JsonNode tenant = exchange(
                "/api/v1/tenants",
                admin,
                Map.of("organizationId", orgId.toString(), "name", "Configured", "externalId", "configured-1"));
        UUID tenantId = UUID.fromString(tenant.get("id").asText());

        List<String> names = jdbc.queryForList(
                "SELECT name FROM tester_groups WHERE tenant_id = ? ORDER BY name", String.class, tenantId);
        assertThat(names)
                .as("the configured list replaces the PRD defaults")
                .containsExactly("Smoke Ring A", "Smoke Ring B");
    }

    private String loginOfOrgAdmin(String email, String orgName) {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        JsonNode created = exchange("/api/v1/organizations", devToken, Map.of("name", orgName));
        UUID orgId = UUID.fromString(created.get("id").asText());
        JsonNode invitation = exchange(
                "/api/v1/auth/invitations",
                devToken,
                Map.of("email", email, "organizationId", orgId.toString(), "role", "ORG_ADMIN"));
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

    private JsonNode exchange(String uri, String bearer, Map<String, Object> body) {
        return client.method(HttpMethod.POST)
                .uri(uri)
                .headers(headers -> headers.setBearerAuth(bearer))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) -> {
                    byte[] bytes = response.getBody().readAllBytes();
                    if (response.getStatusCode().value() != 201) {
                        throw new IllegalStateException("expected 201 on " + uri + " but got "
                                + response.getStatusCode().value() + ": " + new String(bytes));
                    }
                    return mapper.readTree(bytes);
                });
    }
}
