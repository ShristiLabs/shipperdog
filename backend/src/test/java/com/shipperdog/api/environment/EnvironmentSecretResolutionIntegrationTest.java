package com.shipperdog.api.environment;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.AuditCapture;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The secret-resolution half of issue #58's AC5 (S9-04, FR-025, PRD
 * 25): Production is not QA — two environments' credential bindings
 * carry different references, and the references resolve INDEPENDENTLY
 * through the {@code SecretReferenceResolver} port (here: a test stub
 * standing in for the GCP Secret Manager wiring the secrets story
 * owns). The proof has three legs: both environments' probes resolve,
 * an unknown reference answers {@code resolved: false}, and no HTTP
 * response or audit event EVER carries the material behind a
 * reference — the database cannot hold it (the companion suite pins
 * the schema) and these surfaces never move it.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
@Import({AuditCapture.class, EnvironmentSecretResolutionIntegrationTest.StubResolverConfiguration.class})
class EnvironmentSecretResolutionIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";
    private static final String PASSWORD = "a-perfectly-valid-password";

    /**
     * The stub registry: the reference is the KEY, the material the
     * value — and the material exists ONLY here, in the test JVM, for
     * the length of the probe. Exactly the registered references
     * resolve; every other one answers empty (unknown, deleted, access
     * denied — the caller cannot distinguish, by design).
     */
    private static final Map<String, String> REGISTERED_SECRETS = new HashMap<>();

    /**
     * The resolver this suite registers in place of the honest unbacked
     * default ({@code @Primary} wins the by-type injection, exactly the
     * {@code AuditCapture} import precedent): the GCP Secret Manager
     * wiring the secrets story owns, stubbed for the probe.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class StubResolverConfiguration {

        @Bean
        @Primary
        SecretReferenceResolver stubSecretReferenceResolver() {
            return reference -> Optional.ofNullable(REGISTERED_SECRETS.get(reference));
        }
    }

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
        REGISTERED_SECRETS.clear();
    }

    @Test
    void productionAndQaReferencesResolveIndependentlyAndMaterialNeverSurfaces() throws Exception {
        REGISTERED_SECRETS.put("projects/demo/secrets/prod-appstore", "material-prod-appstore");
        REGISTERED_SECRETS.put("projects/demo/secrets/qa-appstore", "material-qa-appstore");

        UUID org = createOrg("Env Secrets Corp");
        String adminToken = loginOfOrgRoleIn(org, "env-secrets-admin", "ORG_ADMIN");
        UUID productionId = environmentByCode(org, "production");
        UUID qaId = environmentByCode(org, "qa");

        // Different environments, different references (AC5's setup).
        bind(adminToken, productionId, "apple-store", "projects/demo/secrets/prod-appstore");
        bind(adminToken, qaId, "apple-store", "projects/demo/secrets/qa-appstore");

        // Both resolve — independently, through the same port.
        JsonNode productionProbe = verify(adminToken, productionId, "apple-store");
        assertThat(productionProbe.get("resolved").asBoolean()).isTrue();
        assertThat(productionProbe.get("secretReference").asText()).isEqualTo("projects/demo/secrets/prod-appstore");
        JsonNode qaProbe = verify(adminToken, qaId, "apple-store");
        assertThat(qaProbe.get("resolved").asBoolean()).isTrue();
        assertThat(qaProbe.get("secretReference").asText()).isEqualTo("projects/demo/secrets/qa-appstore");

        // An unknown reference answers resolved:false — never an error,
        // never a hint about WHY it does not resolve.
        bind(adminToken, qaId, "api-signing", "projects/demo/secrets/does-not-exist");
        JsonNode unknownProbe = verify(adminToken, qaId, "api-signing");
        assertThat(unknownProbe.get("resolved").asBoolean()).isFalse();

        // The material never surfaces: not in any response...
        for (JsonNode probe : List.of(productionProbe, qaProbe, unknownProbe)) {
            assertThat(probe.toString()).doesNotContain("material");
        }
        JsonNode productionBindings = get(adminToken, "/api/v1/environments/" + productionId + "/credentials", 200);
        JsonNode qaBindings = get(adminToken, "/api/v1/environments/" + qaId + "/credentials", 200);
        assertThat(productionBindings.toString()).doesNotContain("material");
        assertThat(qaBindings.toString()).doesNotContain("material");

        // ...and not in the audit trail: the events carry the reference
        // and the outcome, and nothing else (PRD 25).
        for (AuditEvent event : audit.eventsOfType(AuditEventType.ENVIRONMENT_CREDENTIAL_VERIFIED)) {
            assertThat(event.detail().toString()).doesNotContain("material");
            assertThat(event.detail()).containsKey("resolved");
        }
        assertThat(audit.eventsOfType(AuditEventType.ENVIRONMENT_CREDENTIAL_VERIFIED))
                .hasSize(3);
    }

    // -----------------------------------------------------------------------
    // Fixture helpers (the sibling suites' shapes)
    // -----------------------------------------------------------------------

    private void bind(String token, UUID environmentId, String name, String reference) {
        client.put()
                .uri("/api/v1/environments/" + environmentId + "/credentials")
                .headers(headers -> headers.setBearerAuth(token))
                .body(Map.of("credentials", List.of(Map.of("name", name, "secretReference", reference))))
                .retrieve()
                .body(JsonNode.class);
    }

    private JsonNode verify(String token, UUID environmentId, String name) {
        UUID credentialId = jdbc.queryForObject(
                "SELECT id FROM environment_credentials WHERE environment_id = ? AND name = ?",
                UUID.class,
                environmentId,
                name);
        return client.post()
                .uri("/api/v1/environments/" + environmentId + "/credentials/" + credentialId + "/verify")
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .body(JsonNode.class);
    }

    private JsonNode get(String bearer, String uri, int expectedStatus) {
        Recording recorded = client.get()
                .uri(uri)
                .headers(headers -> headers.setBearerAuth(bearer))
                .exchange((request, response) -> new Recording(
                        response.getStatusCode().value(),
                        response.getBody().readAllBytes().length == 0
                                ? mapper.createObjectNode()
                                : mapper.readTree(response.getBody().readAllBytes())));
        return recorded.expect(expectedStatus);
    }

    private UUID environmentByCode(UUID organizationId, String code) {
        return jdbc.queryForObject(
                "SELECT id FROM environments WHERE organization_id = ? AND code = ?", UUID.class, organizationId, code);
    }

    /** Creates one organization per test (its five default environments arrive with it). */
    private UUID createOrg(String orgName) {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        return UUID.fromString(client.post()
                .uri("/api/v1/organizations")
                .headers(headers -> headers.setBearerAuth(devToken))
                .body(Map.of("name", orgName))
                .retrieve()
                .body(JsonNode.class)
                .get("id")
                .asText());
    }

    /** Registers a user with the given role into an EXISTING organization; returns their token. */
    private String loginOfOrgRoleIn(UUID orgId, String slug, String role) {
        String email = slug + "@example.com";
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        JsonNode invitation = client.post()
                .uri("/api/v1/auth/invitations")
                .headers(headers -> headers.setBearerAuth(devToken))
                .body(Map.of("email", email, "organizationId", orgId.toString(), "role", role))
                .retrieve()
                .body(JsonNode.class);
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
                        "Environment Secrets " + role))
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

    private static final class Recording {
        final int status;
        final JsonNode body;

        Recording(int status, JsonNode body) {
            this.status = status;
            this.body = body;
        }

        JsonNode expect(int expectedStatus) {
            assertThat(status).as("status of %s", body).isEqualTo(expectedStatus);
            return body;
        }
    }
}
