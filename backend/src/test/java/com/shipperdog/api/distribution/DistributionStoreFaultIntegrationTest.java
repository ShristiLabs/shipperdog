package com.shipperdog.api.distribution;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.identity.AuditCapture;
import java.util.LinkedHashMap;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The store-fault half of the crossing's containment (issue #61,
 * S13-01, the review fix): a secret store whose RESOLUTION throws a
 * raw fault (a broken ADC, a crashed client) must never take the
 * ACTIVE crossing down with it -- the dispatch records
 * CREDENTIAL_UNRESOLVED, the crossing commits, S13-06 owns rollback.
 * The throwing store is a @Primary test bean so the whole context
 * resolves credentials through it; its STORE half works (binding must
 * succeed) and only resolution fails, exactly the runtime-fault shape.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@TestPropertySource(properties = "shipperdog.distribution.adapters.stub-fixture.retry-backoff=PT0.05S")
@Testcontainers
@Import({
    AuditCapture.class,
    DistributionStoreFaultIntegrationTest.ThrowingStoreConfiguration.class,
    DistributionStoreFaultIntegrationTest.StubAdapterConfiguration.class
})
class DistributionStoreFaultIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";
    private static final String PASSWORD = "a-perfectly-valid-password";

    /** The runtime-fault shape: store works, resolution throws a raw bug. */
    static class ThrowingChannelSecretStore
            implements com.shipperdog.api.distribution.internal.secret.ChannelSecretStore {

        @Override
        public String store(
                UUID organizationId, String provider, com.shipperdog.api.distribution.SecretMaterial material) {
            return "throwing-store://" + organizationId + "/" + provider + "/" + UUID.randomUUID();
        }

        @Override
        public Optional<com.shipperdog.api.distribution.SecretMaterial> resolve(String secretReference) {
            throw new IllegalStateException("fixture store fault: the token source is broken");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ThrowingStoreConfiguration {

        @Bean
        @Primary
        ThrowingChannelSecretStore throwingChannelSecretStore() {
            return new ThrowingChannelSecretStore();
        }
    }

    /** The stub channel (a provider-declaring adapter), this suite's own bean. */
    static class StubChannelAdapter implements DistributionChannelAdapter {

        @Override
        public String channelId() {
            return "stub-fixture";
        }

        @Override
        public String displayName() {
            return "Stub Fixture Channel";
        }

        @Override
        public String description() {
            return "The store-fault suite's adapter.";
        }

        @Override
        public java.util.Set<AdapterCapability> capabilities() {
            return java.util.Set.of(AdapterCapability.BUILD_UPLOAD);
        }

        @Override
        public java.util.Set<String> credentialProviders() {
            return java.util.Set.of("stub");
        }

        @Override
        public List<AdapterOutcome> distribute(DistributionCommand command) {
            throw new IllegalStateException("the adapter must never be reached: the credential cannot resolve");
        }

        @Override
        public AdapterOutcome status(
                UUID artifactId,
                String channel,
                String externalId,
                com.shipperdog.api.distribution.DistributionCommand.ResolvedCredential credential) {
            return new AdapterOutcome(artifactId, AdapterOutcome.FAILED, null, "never", null);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubAdapterConfiguration {

        @Bean
        StubChannelAdapter storeFaultStubChannelAdapter() {
            return new StubChannelAdapter();
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
    private ObjectMapper mapper;

    private RestClient client;
    private UUID org;
    private String adminToken;
    private String ownerToken;
    private String managerToken;
    private UUID tenantA;
    private UUID app;
    private int versionCounter;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        String slug = "store-fault-" + UUID.randomUUID().toString().substring(0, 8);
        org = createOrg("Store Fault Corp " + slug);
        adminToken = loginOfOrgRoleIn(org, slug + "-admin", "ORG_ADMIN");
        ownerToken = loginOfOrgRoleIn(org, slug + "-owner", "APP_OWNER");
        managerToken = loginOfOrgRoleIn(org, slug + "-manager", "RELEASE_MANAGER");
        tenantA = createTenant(adminToken, org, "Store Fault Bank", "crm-store-fault-" + slug);
        app = createTenantApp(adminToken, tenantA, "Store Fault Banking", "com." + slug.replace("-", "") + ".banking");
    }

    @Test
    void aThrowingStoreResolutionNeverFailsTheCrossing() {
        // Binding works (the store's write half is healthy).
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("organizationId", org.toString());
        binding.put("provider", "stub");
        binding.put("name", "Org Stub Account");
        binding.put("secretMaterial", "stub-secret-material");
        put(adminToken, "/api/v1/distribution/credentials", binding, 200);

        UUID production = environmentByCode("production");
        patch(adminToken, "/api/v1/environments/" + production, Map.of("distributionChannel", "stub-fixture"), 200);
        put(
                adminToken,
                "/api/v1/environments/" + production + "/tenants",
                Map.of("tenantIds", List.of(tenantA.toString())),
                200);

        UUID release = releaseWalkedToActive(production);

        // The crossing committed: the release went ACTIVE despite the store.
        assertThat(jdbc.queryForObject("SELECT status FROM releases WHERE id = ?", String.class, release))
                .isEqualTo("ACTIVE");
        JsonNode records =
                get(managerToken, "/api/v1/distribution/records?organizationId=" + org + "&releaseId=" + release, 200);
        assertThat(records.get("items").size()).isEqualTo(1);
        JsonNode row = records.get("items").get(0);
        assertThat(row.get("status").asText()).isEqualTo("FAILED");
        assertThat(row.get("failureCode").asText()).isEqualTo("CREDENTIAL_UNRESOLVED");
    }

    // -----------------------------------------------------------------------
    // Fixtures (the framework suite's shapes, trimmed to this suite's needs)
    // -----------------------------------------------------------------------

    private UUID environmentByCode(String code) {
        return jdbc.queryForObject(
                "SELECT id FROM environments WHERE organization_id = ?::uuid AND code = ?",
                UUID.class,
                org.toString(),
                code);
    }

    private UUID releaseWalkedToActive(UUID environmentId) {
        String name = String.format("%d.%d.%d", 1 + versionCounter / 10, versionCounter % 10, 7);
        versionCounter++;
        UUID version = createVersion(ownerToken, app, name);
        UUID artifact = insertArtifact(version, "IOS", versionCounter);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", app.toString());
        body.put("versionId", version.toString());
        body.put("ring", "qa");
        body.put("environmentId", environmentId.toString());
        body.put("artifacts", List.of(Map.of("platform", "IOS", "artifactId", artifact.toString())));
        body.put("targets", List.of(Map.of("tenantId", tenantA.toString(), "testerGroupIds", List.of())));
        UUID release = UUID.fromString(
                post(managerToken, "/api/v1/releases", body, 201).get("id").asText());
        for (String step : List.of("VALIDATING", "READY", "APPROVAL_PENDING", "APPROVED", "DEPLOYING", "ACTIVE")) {
            post(
                    managerToken,
                    "/api/v1/releases/" + release + "/transitions",
                    Map.of("applicationId", app.toString(), "to", step),
                    200);
        }
        return release;
    }

    private UUID insertArtifact(UUID versionId, String platform, long build) {
        UUID artifactId = UUID.randomUUID();
        Map<String, Object> owner =
                jdbc.queryForMap("SELECT organization_id, tenant_id FROM versions WHERE id = ?", versionId);
        jdbc.update(
                "INSERT INTO artifacts (id, version_id, platform, file_name, storage_key, sha256, size, "
                        + "build_number, status, organization_id, tenant_id) "
                        + "VALUES (?, ?, ?::platform, ?, ?, ?, ?, ?, ?::artifact_status, ?, ?)",
                artifactId,
                versionId,
                platform,
                platform.toLowerCase(java.util.Locale.ROOT) + "-" + build + ".ipa",
                "fixture/" + artifactId + "/artifact",
                "a1".repeat(32),
                1024L,
                build,
                "READY",
                owner.get("organization_id"),
                owner.get("tenant_id"));
        return artifactId;
    }

    private UUID createVersion(String token, UUID applicationId, String versionName) {
        return UUID.fromString(post(
                        token,
                        "/api/v1/applications/" + applicationId + "/versions",
                        Map.of("versionName", versionName),
                        201)
                .get("id")
                .asText());
    }

    private UUID createTenantApp(String token, UUID tenantId, String name, String bundleId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", tenantId.toString());
        body.put("name", name);
        body.put("platforms", List.of("IOS", "ANDROID"));
        body.put("bundleId", bundleId);
        body.put("packageName", bundleId);
        return UUID.fromString(
                post(token, "/api/v1/applications", body, 201).get("id").asText());
    }

    private UUID createTenant(String token, UUID organizationId, String name, String externalId) {
        return UUID.fromString(post(
                        token,
                        "/api/v1/tenants",
                        Map.of("organizationId", organizationId.toString(), "name", name, "externalId", externalId),
                        201)
                .get("id")
                .asText());
    }

    private UUID createOrg(String orgName) {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        return UUID.fromString(post(devToken, "/api/v1/organizations", Map.of("name", orgName), 201)
                .get("id")
                .asText());
    }

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
                        invitation.get("invitationToken").asText(),
                        "email",
                        email,
                        "password",
                        PASSWORD,
                        "fullName",
                        "Store Fault " + role))
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
        return exchange(uri, bearer, "POST", body).expect(expectedStatus);
    }

    private JsonNode put(String bearer, String uri, Map<String, Object> body, int expectedStatus) {
        return exchange(uri, bearer, "PUT", body).expect(expectedStatus);
    }

    private JsonNode patch(String bearer, String uri, Map<String, Object> body, int expectedStatus) {
        return exchange(uri, bearer, "PATCH", body).expect(expectedStatus);
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

    private record Recording(int status, String contentType, JsonNode body) {

        JsonNode expect(int expectedStatus) {
            assertThat(status).as("HTTP status for %s", body.toString()).isEqualTo(expectedStatus);
            return body;
        }
    }
}
