package com.shipperdog.api.scim;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.AuditCapture;
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
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The SCIM configuration surface for issue #69 (S14-03): the typed
 * write's validation (roles, group mappings, tenant ownership), the
 * read's shape (the token appears as metadata only — never the hash,
 * never the plaintext), the token lifecycle (generation answers the
 * plaintext exactly once; rotation replaces the live credential;
 * revocation freezes provisioning), and the permission gates —
 * anonymous 401, the concealed 404 for a foreign organization, the
 * uniform miss for an unknown one.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
@Import(AuditCapture.class)
class ScimConfigIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";

    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @LocalServerPort
    int port;

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
    void writeIsTypedValidatedAndReadsBack() throws Exception {
        String devToken = devToken();
        UUID orgId = createOrganization(devToken, "SCIM Config");
        UUID tenantId = createTenant(devToken, orgId, "scim-config-tenant");

        JsonNode stored = putConfig(devToken, orgId, configBody(tenantId), 200);
        assertThat(stored.get("enabled").asBoolean()).isTrue();
        assertThat(stored.get("defaultRole").asText()).isEqualTo("VIEWER");
        assertThat(stored.get("groupRoleMappings")).hasSize(1);
        assertThat(stored.get("groupRoleMappings").get(0).get("group").asText()).isEqualTo("ShipperDog Developers");
        assertThat(stored.get("groupRoleMappings").get(0).get("orgRole").asText())
                .isEqualTo("DEVELOPER");
        assertThat(stored.get("groupTenantMappings")).hasSize(1);
        assertThat(stored.get("groupTenantMappings").get(0).get("tenantId").asText())
                .isEqualTo(tenantId.toString());
        assertThat(stored.get("token").isNull())
                .as("no token exists before generation")
                .isTrue();
        assertThat(audit.eventsOfType(AuditEventType.SCIM_CONFIGURED)).hasSize(1);

        JsonNode fetched = get(devToken, orgId, 200);
        assertThat(fetched.get("groupRoleMappings")).hasSize(1);
        assertThat(fetched.get("token").isNull()).isTrue();
    }

    @Test
    void invalidWritesAreRefused() throws Exception {
        String devToken = devToken();
        UUID orgId = createOrganization(devToken, "SCIM Invalid");
        UUID foreignTenant = createTenant(devToken, createOrganization(devToken, "Other Org"), "foreign-tenant");

        assertThat(statusOfPut(devToken, orgId, with(configBody(null), "defaultRole", "SUPERUSER")))
                .isEqualTo(400);
        assertThat(statusOfPut(devToken, orgId, with(configBody(null), "groupRoleMappings", List.of())))
                .as("an empty mapping set is a valid no-rules state")
                .isEqualTo(200);
        assertThat(statusOfPut(
                        devToken,
                        orgId,
                        with(
                                configBody(foreignTenant),
                                "groupRoleMappings",
                                List.of(
                                        Map.of("group", "Twice", "orgRole", "VIEWER"),
                                        Map.of("group", "Twice", "orgRole", "DEVELOPER")))))
                .as("the same group listed twice in role mappings is refused")
                .isEqualTo(400);
        assertThat(statusOfPut(devToken, orgId, configBody(foreignTenant)))
                .as("a mapping naming another organization's tenant is refused")
                .isEqualTo(400);
        assertThat(statusOfPut(devToken, UUID.randomUUID(), configBody(null)))
                .as("unknown organization answers the uniform miss")
                .isEqualTo(404);
    }

    @Test
    void tokenLifecycleAnswersPlaintextOnceAndRotationKillsTheOldToken() throws Exception {
        String devToken = devToken();
        UUID orgId = createOrganization(devToken, "SCIM Token");
        putConfig(devToken, orgId, configBody(null), 200);

        JsonNode first = postToken(devToken, orgId, Map.of("label", "Entra tenant"), 200);
        String firstToken = first.get("token").asText();
        assertThat(firstToken).startsWith("sds_");
        assertThat(first.get("label").asText()).isEqualTo("Entra tenant");

        // The read answers metadata only: never the token, never the hash.
        JsonNode config = get(devToken, orgId, 200);
        assertThat(config.get("token")).isNotNull();
        assertThat(config.get("token").get("label").asText()).isEqualTo("Entra tenant");
        assertThat(config.get("token").has("token")).isFalse();
        assertThat(config.get("token").has("tokenHash")).isFalse();
        assertThat(audit.eventsOfType(AuditEventType.SCIM_TOKEN_ROTATED)).hasSize(1);

        // Enable the connector: the FIRST token now authenticates.
        putConfig(devToken, orgId, configBody(null), 200);
        assertThat(scimStatus(HttpMethod.GET, "/scim/v2/Users", firstToken)).isEqualTo(200);

        // Rotation: the new token answers, the old one dies immediately.
        JsonNode second = postToken(devToken, orgId, Map.of("label", "Okta tenant"), 200);
        String secondToken = second.get("token").asText();
        assertThat(secondToken).isNotEqualTo(firstToken);
        assertThat(scimStatus(HttpMethod.GET, "/scim/v2/Users", secondToken)).isEqualTo(200);
        assertThat(scimStatus(HttpMethod.GET, "/scim/v2/Users", firstToken))
                .as("the rotated-away token is dead")
                .isEqualTo(401);

        // Revocation freezes provisioning until a new token exists.
        deleteToken(devToken, orgId, 204);
        assertThat(scimStatus(HttpMethod.GET, "/scim/v2/Users", secondToken)).isEqualTo(401);
    }

    @Test
    void disabledConnectorRefusesTokensItWouldOtherwiseAccept() throws Exception {
        String devToken = devToken();
        UUID orgId = createOrganization(devToken, "SCIM Disabled");
        postToken(devToken, orgId, Map.of(), 200);

        // Config exists but enabled=false: every request is refused.
        assertThat(scimStatus(HttpMethod.GET, "/scim/v2/Users", tokenOf(devToken, orgId)))
                .isEqualTo(401);
        assertThat(statusOf(HttpMethod.GET, "/api/v1/organizations/" + orgId + "/scim-config", null))
                .isEqualTo(401);
    }

    @Test
    void permissionGatesFollowTheIdpConfigDoctrine() throws Exception {
        String devToken = devToken();
        UUID ownOrg = createOrganization(devToken, "SCIM Own");
        UUID foreignOrg = createOrganization(devToken, "SCIM Foreign");
        putConfig(devToken, foreignOrg, configBody(null), 200);

        // A foreign organization's administrator conceals at the 404 —
        // byte-identical to the genuine miss.
        String foreignAdminToken = orgAdminToken(devToken, foreignOrg, "Foreign SCIM Admin");
        assertThat(statusOf(HttpMethod.GET, "/api/v1/organizations/" + ownOrg + "/scim-config", foreignAdminToken))
                .isEqualTo(404);
        assertThat(statusOfPut(foreignAdminToken, ownOrg, configBody(null))).isEqualTo(404);
        assertThat(statusOf(
                        HttpMethod.POST, "/api/v1/organizations/" + ownOrg + "/scim-config/token", foreignAdminToken))
                .isEqualTo(404);

        // An organization without configuration answers the same miss.
        assertThat(statusOf(HttpMethod.GET, "/api/v1/organizations/" + ownOrg + "/scim-config", devToken))
                .isEqualTo(404);

        // The owning organization's own administrator reads it.
        String ownAdminToken = orgAdminToken(devToken, ownOrg, "Own SCIM Admin");
        putConfig(ownAdminToken, ownOrg, configBody(null), 200);
        assertThat(statusOf(HttpMethod.GET, "/api/v1/organizations/" + ownOrg + "/scim-config", ownAdminToken))
                .isEqualTo(200);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** A valid base body: enabled, one role mapping, one tenant mapping when a tenant is given. */
    private static Map<String, Object> configBody(UUID tenantId) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("enabled", true);
        body.put("defaultRole", "VIEWER");
        body.put("groupRoleMappings", List.of(Map.of("group", "ShipperDog Developers", "orgRole", "DEVELOPER")));
        if (tenantId != null) {
            body.put(
                    "groupTenantMappings",
                    List.of(Map.of(
                            "group",
                            "ShipperDog Developers",
                            "tenantId",
                            tenantId.toString(),
                            "tenantRole",
                            "TENANT_TESTER")));
        } else {
            body.put("groupTenantMappings", List.of());
        }
        return body;
    }

    private static Map<String, Object> with(Map<String, Object> body, String field, Object value) {
        body.put(field, value);
        return body;
    }

    private JsonNode putConfig(String token, UUID organizationId, Map<String, Object> body, int expectedStatus) {
        return exchange(
                HttpMethod.PUT,
                "/api/v1/organizations/" + organizationId + "/scim-config",
                token,
                body,
                expectedStatus);
    }

    private int statusOfPut(String token, UUID organizationId, Map<String, Object> body) {
        exchange(HttpMethod.PUT, "/api/v1/organizations/" + organizationId + "/scim-config", token, body, null);
        return lastStatus;
    }

    private JsonNode postToken(String token, UUID organizationId, Map<String, Object> body, int expectedStatus) {
        return exchange(
                HttpMethod.POST,
                "/api/v1/organizations/" + organizationId + "/scim-config/token",
                token,
                body,
                expectedStatus);
    }

    private void deleteToken(String token, UUID organizationId, int expectedStatus) {
        exchange(
                HttpMethod.DELETE,
                "/api/v1/organizations/" + organizationId + "/scim-config/token",
                token,
                null,
                expectedStatus);
    }

    private JsonNode get(String token, UUID organizationId, int expectedStatus) {
        return exchange(
                HttpMethod.GET,
                "/api/v1/organizations/" + organizationId + "/scim-config",
                token,
                null,
                expectedStatus);
    }

    /** The live connector token, generated fresh (the read never answers it). */
    private String tokenOf(String adminToken, UUID organizationId) {
        return postToken(adminToken, organizationId, Map.of(), 200).get("token").asText();
    }

    private int lastStatus;

    private JsonNode exchange(HttpMethod method, String uri, String token, Object body, Integer expectedStatus) {
        RestClient.RequestBodySpec spec = client.method(method).uri(uri);
        if (token != null) {
            spec = spec.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((request, response) -> {
            lastStatus = response.getStatusCode().value();
            if (expectedStatus != null) {
                assertThat(lastStatus).isEqualTo(expectedStatus);
            }
            String text = new String(response.getBody().readAllBytes());
            return text.isBlank() ? null : mapper.readTree(text);
        });
    }

    private int statusOf(HttpMethod method, String uri, String token) {
        return client.method(method)
                .uri(uri)
                .headers(headers -> {
                    if (token != null) {
                        headers.setBearerAuth(token);
                    }
                })
                .exchange((request, response) -> response.getStatusCode().value());
    }

    private int scimStatus(HttpMethod method, String uri, String scimToken) {
        return statusOf(method, uri, scimToken);
    }

    private String devToken() {
        JsonNode tokens = client.post()
                .uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", DEV_ADMIN_EMAIL, "password", DEV_ADMIN_PASSWORD))
                .retrieve()
                .body(JsonNode.class);
        return tokens.get("accessToken").asText();
    }

    /** Invites an ORG_ADMIN of the organization and answers their token. */
    private String orgAdminToken(String devToken, UUID organizationId, String email) {
        String address = email.toLowerCase().replace(' ', '.') + "@admin.example";
        JsonNode invitation = client.post()
                .uri("/api/v1/auth/invitations")
                .header("Authorization", "Bearer " + devToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", address, "organizationId", organizationId.toString(), "role", "ORG_ADMIN"))
                .retrieve()
                .body(JsonNode.class);
        client.post()
                .uri("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "invitationToken",
                        invitation.get("invitationToken").asText(),
                        "email",
                        address,
                        "password",
                        "correct-horse-battery-1",
                        "fullName",
                        email))
                .retrieve()
                .body(JsonNode.class);
        JsonNode tokens = client.post()
                .uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", address, "password", "correct-horse-battery-1"))
                .retrieve()
                .body(JsonNode.class);
        return tokens.get("accessToken").asText();
    }

    private UUID createOrganization(String token, String name) {
        JsonNode organization = client.post()
                .uri("/api/v1/organizations")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", name))
                .retrieve()
                .body(JsonNode.class);
        return UUID.fromString(organization.get("id").asText());
    }

    private UUID createTenant(String token, UUID organizationId, String name) {
        JsonNode tenant = client.post()
                .uri("/api/v1/tenants")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "organizationId",
                        organizationId.toString(),
                        "name",
                        name,
                        "externalId",
                        "scim-ext-" + UUID.randomUUID()))
                .retrieve()
                .body(JsonNode.class);
        return UUID.fromString(tenant.get("id").asText());
    }
}
