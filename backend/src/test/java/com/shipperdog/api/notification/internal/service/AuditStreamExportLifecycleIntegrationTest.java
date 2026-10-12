package com.shipperdog.api.notification.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.environment.SecretReferenceResolver;
import com.shipperdog.api.identity.AuditCapture;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The export lifecycle's own edges (issue #71, S14-05, the tester
 * tier): the edit surface (filter change, secret-reference rotation,
 * the enabled switch, the kind that never moves), the optimistic
 * version's public 409 (a stale writer never resurrects a rotated
 * reference), the per-organization export ceiling, the initial
 * watermark's validation, and concealment of a REAL foreign export
 * (the isolation registry pins the random-id shape; this pins the
 * live row's).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef",
            "shipperdog.notifications.relay-enabled=false"
        })
@Testcontainers
@Import({AuditCapture.class, AuditStreamExportLifecycleIntegrationTest.ResolvingSecrets.class})
class AuditStreamExportLifecycleIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";
    private static final String PASSWORD = "a-perfectly-valid-password";
    private static final String SIGNING_REF = "sm://test/audit-stream-signing";

    @TestConfiguration
    static class ResolvingSecrets {

        @org.springframework.context.annotation.Primary
        @Bean
        SecretReferenceResolver lifecycleTestResolver() {
            return reference ->
                    SIGNING_REF.equals(reference) ? Optional.of("lifecycle-signing-secret") : Optional.empty();
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

    private final ObjectMapper mapper = new ObjectMapper();

    private RestClient client;

    private UUID org;

    private UUID foreignOrg;

    private String adminToken;

    private String foreignAdminToken;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        audit.reset();
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        org = createOrg("Lifecycle Corp " + UUID.randomUUID().toString().substring(0, 8), devToken);
        foreignOrg =
                createOrg("Lifecycle Foreign " + UUID.randomUUID().toString().substring(0, 8), devToken);
        adminToken = orgRoleTokenIn(org, "life-admin");
        foreignAdminToken = orgRoleTokenIn(foreignOrg, "foreign-admin");
    }

    @AfterEach
    void tearDown() {
        if (org != null && adminToken != null) {
            JsonNode page = get(adminToken, exportsPath() + "?limit=50", 200);
            for (JsonNode item : page.get("items")) {
                exchange(HttpMethod.DELETE, exportsPath() + "/" + item.get("id").asText(), null, 204, adminToken);
            }
        }
    }

    @Test
    void editRotatesTheReferenceAndChangesTheFilterWithoutMovingTheKind() {
        UUID exportId = createExport(
                Map.of(
                        "kind", "HTTPS",
                        "format", "SHIPPERDOG_JSON",
                        "destinationUrl", "https://collector.customer.test/events",
                        "signingSecretRef", SIGNING_REF,
                        "eventTypes", List.of("ORGANIZATION_UPDATED")),
                adminToken,
                201);
        JsonNode edited = exchange(
                HttpMethod.PATCH,
                exportsPath() + "/" + exportId,
                Map.of(
                        "format", "SPLUNK_HEC",
                        "eventTypes", List.of("ACCESS_DENIED", "ABAC_EVALUATION_FAULT"),
                        "destinationUrl", "https://collector2.customer.test/events",
                        "signingSecretRef", "sm://test/rotated-signing",
                        "enabled", false),
                200,
                adminToken);
        assertThat(edited.get("format").asString()).isEqualTo("SPLUNK_HEC");
        assertThat(edited.get("eventTypes").size()).isEqualTo(2);
        assertThat(edited.get("destinationUrl").asString()).isEqualTo("https://collector2.customer.test/events");
        assertThat(edited.get("signingSecretRef").asString()).isEqualTo("sm://test/rotated-signing");
        assertThat(edited.get("enabled").asBoolean()).isFalse();
        // The edit is audited with the changed facts; the reference
        // member rides the trail as the scrubber's [REDACTED].
        assertThat(audit.eventsOfType(AuditEventType.AUDIT_STREAM_EXPORT_UPDATED))
                .hasSize(1);
        assertThat(audit.eventsOfType(AuditEventType.AUDIT_STREAM_EXPORT_UPDATED)
                        .get(0)
                        .detail()
                        .toString())
                .doesNotContain("sm://test/rotated-signing");

        // An edit that tries to move the kind answers the field 422
        // (the ledgers differ; an HTTPS export is not a bucket export).
        exchange(
                HttpMethod.PATCH,
                exportsPath() + "/" + exportId,
                Map.of("bucketUri", "gs://bucket/audit"),
                422,
                adminToken);
    }

    @Test
    void theCommittedRowIsTheLastWriterSTruth() {
        // The optimistic version column backs the surface's public
        // 409 (the notification subscription precedent -- a genuine
        // concurrent writer trips it mid-transaction); sequential
        // edits answer the committed truth, each writer's write
        // visible to the next.
        UUID exportId = createExport(defaultBody(), adminToken, 201);
        exchange(HttpMethod.PATCH, exportsPath() + "/" + exportId, Map.of("enabled", false), 200, adminToken);
        JsonNode current = get(adminToken, exportsPath() + "/" + exportId, 200);
        assertThat(current.get("enabled").asBoolean()).isFalse();
        assertThat(current.get("signingSecretRef").asString()).isEqualTo(SIGNING_REF);
    }

    @Test
    void thePerOrganizationCeilingRefusesTheEleventhExport() {
        for (int i = 0; i < 10; i++) {
            createExport(defaultBody(), adminToken, 201);
        }
        JsonNode refusal = postRaw(defaultBody(), adminToken, 422);
        assertThat(refusal.get("code").asString()).isEqualTo("AUDIT_STREAM_EXPORT_INVALID");
        assertThat(refusal.get("errors").hasNonNull("organization")).isTrue();
    }

    @Test
    void theInitialWatermarkValidatesItsShape() {
        JsonNode refusal = postRaw(
                Map.of(
                        "kind", "BUCKET",
                        "bucketUri", "gs://bucket/audit",
                        "signingSecretRef", SIGNING_REF,
                        "initialWatermark", "not-an-instant"),
                adminToken,
                422);
        assertThat(refusal.get("errors").hasNonNull("initialWatermark")).isTrue();
        // A well-formed epoch watermark backfills: the batcher's
        // own suite covers the drain; here the config accepts it.
        createExport(
                Map.of(
                        "kind", "BUCKET",
                        "bucketUri", "gs://bucket/audit",
                        "signingSecretRef", SIGNING_REF,
                        "initialWatermark", "1970-01-01T00:00:00Z"),
                adminToken,
                201);
    }

    @Test
    void aRealForeignExportConcealsExactlyLikeAnUnknownId() {
        UUID foreignExportId = createExportIn(foreignOrg, defaultBody(), foreignAdminToken, 201);
        // The owning admin sees it; the foreign admin's identical
        // call is the byte-identical miss.
        assertThat(get(
                                foreignAdminToken,
                                "/api/v1/organizations/" + foreignOrg + "/audit-stream-exports/" + foreignExportId,
                                200)
                        .get("id")
                        .asString())
                .isEqualTo(foreignExportId.toString());
        JsonNode concealed =
                get(adminToken, "/api/v1/organizations/" + org + "/audit-stream-exports/" + foreignExportId, 404);
        assertThat(concealed.get("code").asString()).isEqualTo("AUDIT_STREAM_EXPORT_NOT_FOUND");
        // The same envelope the unknown-id miss renders (the instance
        // member names the requested URI, so the bytes differ where
        // the requests do -- the code and status are the identity).
        JsonNode unknownId = get(adminToken, exportsPath() + "/" + UUID.randomUUID(), 404);
        assertThat(unknownId.get("code").asString()).isEqualTo("AUDIT_STREAM_EXPORT_NOT_FOUND");
    }

    // Helpers -------------------------------------------------------------------

    private Map<String, Object> defaultBody() {
        return Map.of(
                "kind", "HTTPS",
                "format", "SHIPPERDOG_JSON",
                "destinationUrl", "https://collector.customer.test/events",
                "signingSecretRef", SIGNING_REF);
    }

    private String exportsPath() {
        return "/api/v1/organizations/" + org + "/audit-stream-exports";
    }

    private JsonNode postRaw(Map<String, Object> body, String bearer, int expectedStatus) {
        return exchange(HttpMethod.POST, exportsPath(), body, expectedStatus, bearer);
    }

    private UUID createExport(Map<String, Object> body, String bearer, int expectedStatus) {
        return UUID.fromString(postRaw(body, bearer, expectedStatus).get("id").asString());
    }

    private UUID createExportIn(UUID organizationId, Map<String, Object> body, String bearer, int expectedStatus) {
        return UUID.fromString(exchange(
                        HttpMethod.POST,
                        "/api/v1/organizations/" + organizationId + "/audit-stream-exports",
                        body,
                        expectedStatus,
                        bearer)
                .get("id")
                .asString());
    }

    private JsonNode exchange(
            HttpMethod method, String uri, Map<String, Object> body, int expectedStatus, String bearer) {
        var spec = client.method(method).uri(uri).headers(headers -> {
            if (bearer != null) {
                headers.setBearerAuth(bearer);
            }
        });
        if (body != null) {
            spec = spec.header("Content-Type", "application/json").body(body);
        }
        return spec.exchange((request, response) -> {
            assertThat(response.getStatusCode().value())
                    .as("%s %s -> %d", method, uri, expectedStatus)
                    .isEqualTo(expectedStatus);
            byte[] bytes = response.getBody().readAllBytes();
            return bytes.length == 0 ? mapper.createObjectNode() : mapper.readTree(bytes);
        });
    }

    private JsonNode get(String bearer, String uri, int expectedStatus) {
        return exchange(HttpMethod.GET, uri, null, expectedStatus, bearer);
    }

    private UUID createOrg(String name, String devToken) {
        JsonNode created = exchange(HttpMethod.POST, "/api/v1/organizations", Map.of("name", name), 201, devToken);
        return UUID.fromString(created.get("id").asString());
    }

    private String orgRoleTokenIn(UUID organizationId, String slug) {
        String email = slug + UUID.randomUUID().toString().substring(0, 6) + "@example.com";
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        JsonNode invitation = exchange(
                HttpMethod.POST,
                "/api/v1/auth/invitations",
                Map.of("email", email, "organizationId", organizationId.toString(), "role", "ORG_ADMIN"),
                201,
                devToken);
        exchange(
                HttpMethod.POST,
                "/api/v1/auth/register",
                Map.of(
                        "invitationToken",
                        invitation.get("invitationToken").asString(),
                        "email",
                        email,
                        "password",
                        PASSWORD,
                        "fullName",
                        "Lifecycle " + slug),
                201,
                null);
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
        return mapper.readTree(tokensJson).get("accessToken").asString();
    }
}
