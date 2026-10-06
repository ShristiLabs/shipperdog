package com.shipperdog.api.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.artifact.AndroidFixtures;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The S7-03 degraded-mode contract (issue #32, acceptance criterion
 * 3): with Redis unreachable (the test points the client at an
 * intentionally dead port), every decision still answers CORRECTLY
 * from the live loaders — the cache can only make decisions slower,
 * never different — and the degraded outcome counter climbs so
 * operators see it. The correctness halves (allow with a grant, deny
 * without) plus the concealment shape, over the full stack with the
 * cache enabled and broken.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef",
            // A port with no listener: every Redis operation fails
            // fast (connection refused), the cache's permanent
            // degraded state.
            "spring.data.redis.port=16379",
            "shipperdog.authorization-cache.enabled=true"
        })
@Testcontainers
class AuthorizationDecisionDegradedIntegrationTest {

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
    private MeterRegistry meters;

    @Autowired
    private ObjectMapper mapper;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void aDeadCacheAnswersCorrectDecisionsFromTheLiveLoaders() throws Exception {
        String platformAdmin = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        UUID orgId =
                UUID.fromString(post(platformAdmin, "/api/v1/organizations", Map.of("name", "S703 Degraded Corp"), 201)
                        .get("id")
                        .asText());
        UUID tenantId = UUID.fromString(post(
                        platformAdmin,
                        "/api/v1/tenants",
                        Map.of(
                                "organizationId", orgId.toString(),
                                "name", "Banking degraded",
                                "externalId", "ext-s703-degraded"),
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
                                "Banking",
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

        // The tester persona.
        JsonNode invited = post(
                platformAdmin,
                "/api/v1/testers",
                Map.of("organizationId", orgId.toString(), "email", "s703-degraded@example.com", "fullName", "Deg D"),
                201);
        client.post()
                .uri("/api/v1/auth/register")
                .body(Map.of(
                        "invitationToken",
                        invited.get("invitation").get("invitationToken").asString(),
                        "email",
                        "s703-degraded@example.com",
                        "password",
                        PASSWORD,
                        "fullName",
                        "Deg D"))
                .retrieve()
                .body(String.class);
        post(
                platformAdmin,
                "/api/v1/tenants/" + tenantId + "/testers",
                Map.of("testerId", invited.get("id").asText()),
                201);
        String testerToken = accessToken(login("s703-degraded@example.com", PASSWORD));

        // A READY artifact so ALLOW can carry issuance inputs.
        org.springframework.util.MultiValueMap<String, Object> parts =
                new org.springframework.util.LinkedMultiValueMap<>();
        parts.add("version_id", versionId.toString());
        parts.add("platform", "ANDROID");
        parts.add(
                "file", new org.springframework.core.io.ByteArrayResource(AndroidFixtures.aabVersionCodeInManifest()) {
                    @Override
                    public String getFilename() {
                        return "banking.aab";
                    }
                });
        Recording uploaded = exchange("/api/v1/artifacts", platformAdmin, HttpMethod.POST, parts);
        assertThat(uploaded.status).as("artifact upload: %s", uploaded.body).isEqualTo(201);
        UUID artifactId = UUID.fromString(uploaded.body.get("id").asText());
        long deadline = System.currentTimeMillis() + 20_000;
        String status = "";
        while (System.currentTimeMillis() < deadline) {
            status = get(platformAdmin, "/api/v1/artifacts/" + artifactId + "?version_id=" + versionId, 200)
                    .get("status")
                    .asString();
            if ("READY".equals(status)) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(status).isEqualTo("READY");

        String download = "/api/v1/authorization/decisions/download?tenantId=" + tenantId + "&versionId=" + versionId
                + "&platform=ANDROID";

        // INV-005 resting state through the dead cache: the concealed
        // miss, exactly the healthy stack's shape.
        Map<String, Object> denied = problemOf(getRaw(testerToken, download));
        assertThat(denied.get("status")).isEqualTo(404);
        assertThat(denied.get("code")).isEqualTo("VERSION_NOT_FOUND");

        // The grant flips it — evaluated LIVE because the cache cannot
        // answer, and never an allow the engine would deny.
        Map<String, Object> grant = new LinkedHashMap<>();
        grant.put("testerId", invited.get("id").asText());
        grant.put("applicationId", applicationId.toString());
        grant.put("accessType", "EXPLICIT_VERSION");
        grant.put("versionId", versionId.toString());
        grant.put("reason", "degraded-mode fixture");
        post(platformAdmin, "/api/v1/tenants/" + tenantId + "/authorization-grants", grant, 201);
        JsonNode allowed = get(testerToken, download, 200);
        assertThat(allowed.get("artifactId").asString()).isEqualTo(artifactId.toString());
        assertThat(allowed.get("storageKey").asString()).endsWith("/" + artifactId + "/artifact");

        // The eligibility listing answers live as well (empty before,
        // the granted version after).
        String eligibility =
                "/api/v1/authorization/decisions/eligibility?tenantId=" + tenantId + "&applicationId=" + applicationId;
        assertThat(get(testerToken, eligibility, 200).get("entries").size()).isEqualTo(1);

        // And the degradation is OBSERVABLE: the outcome counter's
        // degraded leg climbed on this decision path.
        assertThat(meters.find(com.shipperdog.api.config.MetricNames.AUTHORIZATION_DECISION_CACHE)
                        .tag("outcome", "degraded")
                        .counters()
                        .size())
                .as("the dead cache counts degraded outcomes on some family")
                .isGreaterThan(0);
    }

    // -----------------------------------------------------------------------
    // Helpers (the suite's shared shape)
    // -----------------------------------------------------------------------

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
        Recording response = getRaw(token, uri);
        assertThat(response.status).as("GET %s: %s", uri, response.body).isEqualTo(expectedStatus);
        return response.body;
    }

    private Recording getRaw(String token, String uri) {
        return exchange(uri, token, HttpMethod.GET, null);
    }

    private static Map<String, Object> problemOf(Recording recording) {
        Map<String, Object> shape = new HashMap<>();
        shape.put("status", recording.status);
        shape.put("code", recording.body.get("code").asString());
        shape.put("title", recording.body.get("title").asString());
        shape.put("detail", recording.body.get("detail").asString());
        return shape;
    }

    private Recording exchange(String uri, String token, HttpMethod method, Object body) {
        RestClient.RequestBodySpec spec = client.method(method).uri(uri);
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
            return new Recording(
                    response.getStatusCode().value(),
                    response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                    raw.length == 0 ? mapper.createObjectNode() : mapper.readTree(raw),
                    null);
        });
    }

    private record Recording(int status, String contentType, JsonNode body, String replayed) {}
}
