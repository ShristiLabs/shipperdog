package com.shipperdog.api.otabundle;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.otabundle.internal.service.OtaAppTokenService;
import java.security.MessageDigest;
import java.util.HashMap;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The S16-04 acceptance flow (issue #166, ADR-0010 D3): the {@code
 * sdc1.} app token's full lifecycle — issue (the value returned
 * EXACTLY once, only its SHA-256 persisted), the listing (the registry
 * without the token), the authentication filter (a valid token
 * authenticates; the permission layer's concealed denial answers 404,
 * never the 401 of an unauthenticated request), and revocation's
 * immediate effect (the same token answers 401 the moment the registry
 * row is stamped). Resolution is proven at the service seam: the
 * principal carries the org/tenant/app ids the device check (S16-07)
 * resolves with, because bundleId is NOT tenant-unique.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
class OtaAppTokenIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";
    private static final String PASSWORD = "a-perfectly-valid-password";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @DynamicPropertySource
    static void otaKey(DynamicPropertyRegistry registry) {
        registry.add("shipperdog.ota.app-token-key", () -> "test-ota-app-token-key");
    }

    @LocalServerPort
    int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private OtaAppTokenService appTokens;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    // -----------------------------------------------------------------------
    // AC1: the value exactly once; only the hash persisted
    // -----------------------------------------------------------------------

    @Test
    void issueReturnsTheSdc1ExactlyOnceAndPersistsOnlyTheHash() throws Exception {
        OtaWorld world = otaWorld("at-issue");

        JsonNode issued = post(
                world.token,
                "/api/v1/ota/app-tokens",
                Map.of("applicationId", world.appId.toString(), "name", "fleet token"),
                201);
        String value = issued.get("value").asText();
        UUID tokenId = UUID.fromString(issued.get("token").get("id").asText());
        assertThat(value).startsWith("sdc1.");
        assertThat(issued.get("token").get("name").asText()).isEqualTo("fleet token");
        assertThat(issued.get("token").get("revoked").asBoolean()).isFalse();

        // Write-only registry: the row holds the SHA-256 hex of the exact
        // bytes the device presents — and nothing else of the token.
        String hash = jdbc.queryForObject("SELECT token_hash FROM ota_app_tokens WHERE id = ?", String.class, tokenId);
        assertThat(hash).isEqualTo(sha256Of(value));
        assertThat(hash).hasSize(64).doesNotContain(value);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM ota_app_tokens WHERE application_id = ?", Integer.class, world.appId))
                .isEqualTo(1);

        // The listing renders the registry row, never a token value.
        JsonNode listed = get(world.token, "/api/v1/ota/app-tokens?applicationId=" + world.appId, 200);
        assertThat(listed.get("items").size()).isEqualTo(1);
        assertThat(listed.get("items").get(0).get("id").asText()).isEqualTo(tokenId.toString());
        assertThat(listed.get("items").get(0).has("value")).isFalse();
    }

    // -----------------------------------------------------------------------
    // AC2: the filter authenticates a live token; revocation is immediate
    // -----------------------------------------------------------------------

    @Test
    void liveTokensAuthenticateThroughTheFilterAndRevocationIsImmediate() {
        OtaWorld world = otaWorld("at-auth");
        String value = issueToken(world.token, world.appId, "shipped build");

        // The probe route's permission layer denies the app token's
        // single authority with the concealed 404 — the security context
        // EXISTS, which is the filter's entire contract (no surface
        // honors ota:app-token:check until the device check, S16-07).
        // An unauthenticated bearer could never reach this shape: it
        // answers 401 before the permission layer runs.
        Recording probe = exchange("/api/v1/ota/app-tokens?applicationId=" + world.appId, value, "GET", null);
        assertThat(probe.status).isEqualTo(404);

        // A tampered token never authenticates: the uniform 401.
        assertThat(exchange(
                                "/api/v1/ota/app-tokens?applicationId=" + world.appId,
                                value.substring(0, value.length() - 3) + "aaa",
                                "GET",
                                null)
                        .status)
                .isEqualTo(401);
        assertThat(exchange("/api/v1/ota/app-tokens?applicationId=" + world.appId, "sdc1.not-a-token", "GET", null)
                        .status)
                .isEqualTo(401);

        // Revoke, then the SAME token answers 401: the registry lookup
        // refuses it from the moment revoked_at lands.
        UUID tokenId =
                jdbc.queryForObject("SELECT id FROM ota_app_tokens WHERE application_id = ?", UUID.class, world.appId);
        JsonNode revoked = post(
                world.token,
                "/api/v1/ota/app-tokens/" + tokenId + "/revoke",
                Map.of("applicationId", world.appId.toString()),
                200);
        assertThat(revoked.get("revoked").asBoolean()).isTrue();
        assertThat(revoked.get("revokedAt")).isNotNull();

        assertThat(exchange("/api/v1/ota/app-tokens?applicationId=" + world.appId, value, "GET", null).status)
                .isEqualTo(401);

        // The revoke replay answers the same row (a row-level no-op).
        JsonNode replay = post(
                world.token,
                "/api/v1/ota/app-tokens/" + tokenId + "/revoke",
                Map.of("applicationId", world.appId.toString()),
                200);
        assertThat(replay.get("revokedAt").asText())
                .isEqualTo(revoked.get("revokedAt").asText());

        // The revoked token still lists (registry history, not a secret).
        JsonNode listed = get(world.token, "/api/v1/ota/app-tokens?applicationId=" + world.appId, 200);
        assertThat(listed.get("items").get(0).get("revoked").asBoolean()).isTrue();
    }

    // -----------------------------------------------------------------------
    // AC3: resolution yields the org/tenant/app ids (the rate-limitable
    // identifier semantics of D3)
    // -----------------------------------------------------------------------

    @Test
    void resolutionYieldsTheOrganizationTenantAndApplicationIds() {
        OtaWorld world = otaWorld("at-resolve");
        String value = issueToken(world.token, world.appId, "resolver probe");
        UUID tokenId = issuedRowId(world.appId);

        OtaAppTokenPrincipal principal = appTokens.resolve(value).orElseThrow();
        assertThat(principal.organizationId()).isEqualTo(world.orgId);
        assertThat(principal.tenantId()).isEqualTo(world.tenantId);
        assertThat(principal.applicationId()).isEqualTo(world.appId);
        assertThat(principal.tokenId()).isEqualTo(tokenId);

        // Refusals are uniform: a tampered token and a revoked one both
        // resolve to nothing.
        assertThat(appTokens.resolve(value.substring(0, value.length() - 2) + "zz"))
                .isEmpty();
        post(
                world.token,
                "/api/v1/ota/app-tokens/" + tokenId + "/revoke",
                Map.of("applicationId", world.appId.toString()),
                200);
        assertThat(appTokens.resolve(value)).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Retry safety: the issue route's Idempotency-Key contract
    // -----------------------------------------------------------------------

    @Test
    void issueReplayWithTheSameKeyReturnsTheStoredTokenWithoutASecondMint() {
        OtaWorld world = otaWorld("at-idempotent");
        String key = UUID.randomUUID().toString();

        // A retried issue (a network blip after the 201 was written)
        // must answer the STORED outcome — the same token value and row
        // — never mint a second identifier the operator never sees.
        Recording first = postKeyed(
                world.token,
                "/api/v1/ota/app-tokens",
                Map.of("applicationId", world.appId.toString(), "name", "retry-safe token"),
                key);
        assertThat(first.status).isEqualTo(201);
        Recording replay = postKeyed(
                world.token,
                "/api/v1/ota/app-tokens",
                Map.of("applicationId", world.appId.toString(), "name", "retry-safe token"),
                key);
        assertThat(replay.status).isEqualTo(201);
        assertThat(replay.body.get("value").asText())
                .isEqualTo(first.body.get("value").asText());
        assertThat(replay.body.get("token").get("id").asText())
                .isEqualTo(first.body.get("token").get("id").asText());
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM ota_app_tokens WHERE application_id = ?", Integer.class, world.appId))
                .isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // Guards: the opt-in, the shape, and the concealed misses
    // -----------------------------------------------------------------------

    @Test
    void issueGuardsRefuseHonestly() {
        OtaWorld world = otaWorld("at-guard");
        OtaWorld plain = plainWorld("at-plain");

        // The OTA opt-in gates ISSUE (an authoring act).
        Recording disabled = postRaw(
                plain.token, "/api/v1/ota/app-tokens", Map.of("applicationId", plain.appId.toString(), "name", "x"));
        assertThat(disabled.status).isEqualTo(409);
        assertThat(disabled.body.get("code").asText()).isEqualTo("APPLICATION_NOT_OTA_ENABLED");

        // Shape rejections name their fields.
        Recording blank = postRaw(
                world.token, "/api/v1/ota/app-tokens", Map.of("applicationId", world.appId.toString(), "name", "  "));
        assertThat(blank.status).isEqualTo(400);
        assertThat(blank.body.get("errors").get("name").asText()).contains("1 to 200");
        Recording longName = postRaw(
                world.token,
                "/api/v1/ota/app-tokens",
                Map.of("applicationId", world.appId.toString(), "name", "y".repeat(201)));
        assertThat(longName.status).isEqualTo(400);

        // Unknown application: the concealed miss.
        assertThat(postRaw(
                                world.token,
                                "/api/v1/ota/app-tokens",
                                Map.of("applicationId", UUID.randomUUID().toString(), "name", "x"))
                        .status)
                .isEqualTo(404);

        // Revoke's misses conceal identically: an unknown token id and a
        // foreign application's token are the same 404.
        assertThat(postRaw(
                                world.token,
                                "/api/v1/ota/app-tokens/" + UUID.randomUUID() + "/revoke",
                                Map.of("applicationId", world.appId.toString()))
                        .status)
                .isEqualTo(404);
        String foreignValue = issueToken(world.token, world.appId, "cross-org target");
        UUID foreignTokenId = issuedRowId(world.appId);
        assertThat(postRaw(
                                plain.token,
                                "/api/v1/ota/app-tokens/" + foreignTokenId + "/revoke",
                                Map.of("applicationId", plain.appId.toString()))
                        .status)
                .isEqualTo(404);
        // The foreign attempt revoked nothing: the token still resolves.
        assertThat(appTokens.resolve(foreignValue)).isPresent();

        // Listing deliberately skips the OTA opt-in (a fleet's live
        // identifiers stay visible after the owner disables OTA): the
        // plain world's own app answers its honest empty page.
        JsonNode plainList = get(plain.token, "/api/v1/ota/app-tokens?applicationId=" + plain.appId, 200);
        assertThat(plainList.get("items").size()).isZero();
    }

    // -----------------------------------------------------------------------
    // The world and its helpers
    // -----------------------------------------------------------------------

    private record OtaWorld(UUID orgId, UUID tenantId, UUID appId, String token) {}

    /** The OTA world: an org, a DEVELOPER (the artifact family's persona), a tenant app with OTA enabled. */
    private OtaWorld otaWorld(String suffix) {
        return world(suffix, true);
    }

    /** The same world without the OTA opt-in (V55's default). */
    private OtaWorld plainWorld(String suffix) {
        return world(suffix, false);
    }

    private OtaWorld world(String suffix, boolean otaEnabled) {
        UUID orgId = createOrg("At " + suffix + " Corp");
        String ownerToken = loginOfOrgRoleIn(orgId, "at-" + suffix + "-owner@example.com", "APP_OWNER");
        String token = loginOfOrgRoleIn(orgId, "at-" + suffix + "-dev@example.com", "DEVELOPER");
        String adminToken = loginOfOrgRoleIn(orgId, "at-" + suffix + "-admin@example.com", "ORG_ADMIN");
        UUID tenantId = createTenant(adminToken, orgId, "At " + suffix + " Bank", "crm-at-" + suffix);
        UUID appId = createTenantApp(
                ownerToken,
                tenantId,
                "At " + suffix + " App",
                "com.at." + suffix.replace("-", ""),
                List.of("IOS", "ANDROID"));
        if (otaEnabled) {
            jdbc.update("UPDATE applications SET ota_enabled = true WHERE id = ?", appId);
        }
        return new OtaWorld(orgId, tenantId, appId, token);
    }

    /** Issues one token and answers its value (the only time it is readable). */
    private String issueToken(String bearer, UUID appId, String name) {
        return post(bearer, "/api/v1/ota/app-tokens", Map.of("applicationId", appId.toString(), "name", name), 201)
                .get("value")
                .asText();
    }

    /** The id of the world's single issued row (each test issues at most one per app). */
    private UUID issuedRowId(UUID appId) {
        return jdbc.queryForObject("SELECT id FROM ota_app_tokens WHERE application_id = ?", UUID.class, appId);
    }

    private UUID createTenantApp(String token, UUID tenantId, String name, String bundleId, List<String> platforms) {
        Map<String, Object> body = new HashMap<>();
        body.put("tenantId", tenantId.toString());
        body.put("name", name);
        body.put("platforms", platforms);
        body.put("bundleId", platforms.contains("IOS") ? bundleId : null);
        body.put("packageName", platforms.contains("ANDROID") ? bundleId : null);
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
                        PASSWORD,
                        "fullName",
                        "At " + role))
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

    private JsonNode post(String bearer, String uri, Map<String, Object> body, Integer expectedStatus) {
        Recording recording = exchange(uri, bearer, "POST", body);
        if (expectedStatus != null) {
            assertThat(recording.status)
                    .as("POST %s -> %s", uri, recording.body)
                    .isEqualTo(expectedStatus);
        }
        return recording.body;
    }

    private Recording postRaw(String bearer, String uri, Map<String, Object> body) {
        return exchange(uri, bearer, "POST", body);
    }

    /** POSTs with an Idempotency-Key (the retry-safety contract, issue #8). */
    private Recording postKeyed(String bearer, String uri, Map<String, Object> body, String idempotencyKey) {
        RestClient.RequestBodySpec spec = client.post().uri(uri);
        if (bearer != null) {
            spec.headers(headers -> headers.setBearerAuth(bearer));
        }
        return spec.header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) -> new Recording(
                        response.getStatusCode().value(),
                        response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                        mapper.readTree(response.getBody().readAllBytes())));
    }

    private JsonNode get(String bearer, String uri, int expectedStatus) {
        return exchange(uri, bearer, "GET", null).expect(expectedStatus);
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

    private static String sha256Of(String value) throws Exception {
        return java.util.HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private record Recording(int status, String contentType, JsonNode body) {

        JsonNode expect(int expectedStatus) {
            assertThat(status).as("HTTP status for %s", body.toString()).isEqualTo(expectedStatus);
            return body;
        }
    }
}
