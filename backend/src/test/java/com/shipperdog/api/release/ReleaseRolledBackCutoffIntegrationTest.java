package com.shipperdog.api.release;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.artifact.AndroidFixtures;
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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The rolled-back download cutoff end to end (issue #56 AC4): a
 * granted tester's DOWNLOAD decision through the REAL authorization
 * engine — the S7-03 decision API route the portal issues against —
 * flips from ALLOW to the concealed miss the moment the release
 * reaches ROLLED_BACK, while the READ-grained surfaces stay exactly
 * as they were (INV-006/007: rollback never deletes, reads remain
 * fully authorized). The policy-level probe (which contributor
 * answers, on which version) is pinned by the S9-02 transition suite;
 * this suite pins the ENGINE wiring: the release module's contributor
 * is picked up by the distribution layer's ObjectProvider and denies
 * with the layer's reason on the audit trail, byte-identical to any
 * other distribution wall.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
class ReleaseRolledBackCutoffIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";
    private static final String PASSWORD = "a-perfectly-valid-password";

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
    private ObjectMapper mapper;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void aGrantedTestersDownloadDecisionFlipsToTheConcealedMissOnRollback() throws Exception {
        String platformAdmin = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        UUID orgId =
                UUID.fromString(post(platformAdmin, "/api/v1/organizations", Map.of("name", "S902 Cutoff Corp"), 201)
                        .get("id")
                        .asText());
        UUID tenantId = UUID.fromString(post(
                        platformAdmin,
                        "/api/v1/tenants",
                        Map.of(
                                "organizationId",
                                orgId.toString(),
                                "name",
                                "Cutoff Bank",
                                "externalId",
                                "ext-s902-cutoff"),
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
                                "Cutoff Banking",
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

        // The tester persona: invited, registered, linked to the tenant.
        JsonNode invited = post(
                platformAdmin,
                "/api/v1/testers",
                Map.of("organizationId", orgId.toString(), "email", "s902-cutoff@example.com", "fullName", "Cut T"),
                201);
        client.post()
                .uri("/api/v1/auth/register")
                .body(Map.of(
                        "invitationToken",
                        invited.get("invitation").get("invitationToken").asString(),
                        "email",
                        "s902-cutoff@example.com",
                        "password",
                        PASSWORD,
                        "fullName",
                        "Cut T"))
                .retrieve()
                .body(String.class);
        post(
                platformAdmin,
                "/api/v1/tenants/" + tenantId + "/testers",
                Map.of("testerId", invited.get("id").asText()),
                201);
        String testerToken = accessToken(login("s902-cutoff@example.com", PASSWORD));

        // A real SIGNED APK so the decision resolves a distributable
        // installable (the S8-04 format rule).
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("version_id", versionId.toString());
        parts.add("platform", "ANDROID");
        parts.add(
                "file",
                new ByteArrayResource(
                        AndroidFixtures.signedApk(AndroidFixtures.VERSION_NAME, AndroidFixtures.VERSION_CODE)) {
                    @Override
                    public String getFilename() {
                        return "cutoff.apk";
                    }
                });
        Recording uploaded = exchange("/api/v1/artifacts", platformAdmin, HttpMethod.POST, parts);
        assertThat(uploaded.status).as("artifact upload: %s", uploaded.body).isEqualTo(201);
        UUID artifactId = UUID.fromString(uploaded.body.get("id").asText());
        awaitReady(platformAdmin, artifactId, versionId);

        // The EXPLICIT_VERSION grant — the FR-011 entitlement leg.
        Map<String, Object> grant = new LinkedHashMap<>();
        grant.put("testerId", invited.get("id").asText());
        grant.put("applicationId", applicationId.toString());
        grant.put("accessType", "EXPLICIT_VERSION");
        grant.put("versionId", versionId.toString());
        grant.put("reason", "cutoff fixture");
        post(platformAdmin, "/api/v1/tenants/" + tenantId + "/authorization-grants", grant, 201);

        // The release: created by a RELEASE_MANAGER, walked to ACTIVE.
        String managerToken = loginOfOrgRoleIn(orgId, "s902-cutoff-manager", "RELEASE_MANAGER");
        UUID releaseId = UUID.fromString(post(
                        managerToken,
                        "/api/v1/releases",
                        Map.of(
                                "applicationId", applicationId.toString(),
                                "versionId", versionId.toString(),
                                "ring", "qa",
                                "artifacts",
                                        List.of(Map.of("platform", "ANDROID", "artifactId", artifactId.toString())),
                                "targets",
                                        List.of(Map.of("tenantId", tenantId.toString(), "testerGroupIds", List.of()))),
                        201)
                .get("id")
                .asText());
        for (String state : List.of("VALIDATING", "READY", "APPROVAL_PENDING", "APPROVED", "DEPLOYING", "ACTIVE")) {
            transition(managerToken, releaseId, applicationId, state, null, 200);
        }

        String download = "/api/v1/authorization/decisions/download?tenantId=" + tenantId + "&versionId=" + versionId
                + "&platform=ANDROID";

        // Before the rollback the full chain passes — entitlement AND
        // the distribution layer with its new release policy — and the
        // decision carries the issuance inputs.
        JsonNode allowed = get(testerToken, download, 200);
        assertThat(allowed.get("artifactId").asString()).isEqualTo(artifactId.toString());

        // The rollback: the direct channel's cutoff.
        transition(
                managerToken,
                releaseId,
                applicationId,
                "ROLLED_BACK",
                "crash spike; direct channel pulled pending store fix",
                200);

        // The very next decision denies — the concealed miss shape,
        // byte-identical to the resting-state denial (the engine's
        // distribution layer consulted the release module's policy
        // live; the decision-input cache holds input SETS only, never
        // this fact, so there is no stale-allow window).
        JsonNode denied = get(testerToken, download, 404);
        assertThat(denied.get("code").asString()).isEqualTo("VERSION_NOT_FOUND");

        // The READ-grained surfaces are untouched (INV-006/007): the
        // eligibility listing still carries the granted version, and
        // the release's own history remains readable to the manager.
        assertThat(get(
                                testerToken,
                                "/api/v1/authorization/decisions/eligibility?tenantId="
                                        + tenantId
                                        + "&applicationId="
                                        + applicationId,
                                200)
                        .get("entries")
                        .size())
                .isEqualTo(1);
        assertThat(get(managerToken, "/api/v1/releases/" + releaseId + "/history?applicationId=" + applicationId, 200)
                        .get("status")
                        .asString())
                .isEqualTo("ROLLED_BACK");
    }

    // -----------------------------------------------------------------------
    // Helpers (the decision suites' shared shape)
    // -----------------------------------------------------------------------

    private void awaitReady(String token, UUID artifactId, UUID versionId) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        String status = "";
        while (System.currentTimeMillis() < deadline) {
            status = get(token, "/api/v1/artifacts/" + artifactId + "?version_id=" + versionId, 200)
                    .get("status")
                    .asString();
            if ("READY".equals(status)) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(status).isEqualTo("READY");
    }

    private JsonNode transition(
            String bearer, UUID releaseId, UUID applicationId, String to, String reason, int expectedStatus) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", applicationId.toString());
        body.put("to", to);
        if (reason != null) {
            body.put("reason", reason);
        }
        Recording response = exchange("/api/v1/releases/" + releaseId + "/transitions", bearer, HttpMethod.POST, body);
        assertThat(response.status)
                .as("transition to %s: %s", to, response.body)
                .isEqualTo(expectedStatus);
        return response.body;
    }

    /** Registers a user with the given role into an EXISTING organization; returns their token. */
    private String loginOfOrgRoleIn(UUID orgId, String slug, String role) {
        String email = slug + "@example.com";
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
                        invitation.get("invitationToken").asString(),
                        "email",
                        email,
                        "password",
                        PASSWORD,
                        "fullName",
                        "Release " + role))
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

    private JsonNode post(String token, String uri, Map<String, Object> body, int expectedStatus) {
        Recording response = exchange(uri, token, HttpMethod.POST, body);
        assertThat(response.status).as("POST %s: %s", uri, response.body).isEqualTo(expectedStatus);
        return response.body;
    }

    private JsonNode get(String token, String uri, int expectedStatus) {
        Recording response = exchange(uri, token, HttpMethod.GET, null);
        assertThat(response.status).as("GET %s: %s", uri, response.body).isEqualTo(expectedStatus);
        return response.body;
    }

    private Recording exchange(String uri, String token, HttpMethod method, Object body) {
        RestClient.RequestBodySpec spec = client.method(method).uri(uri);
        if (token != null) {
            spec.headers(headers -> headers.setBearerAuth(token));
        }
        if (body != null) {
            spec.contentType(
                    body instanceof MultiValueMap<?, ?> ? MediaType.MULTIPART_FORM_DATA : MediaType.APPLICATION_JSON);
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
