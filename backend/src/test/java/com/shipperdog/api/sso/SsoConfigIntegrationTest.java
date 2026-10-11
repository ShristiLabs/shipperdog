package com.shipperdog.api.sso;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.AuditCapture;
import java.util.HashMap;
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

/**
 * The OIDC configuration surface for issue #67 (S14-01): the typed
 * write's validation, the read's shape (the secret reference echoes,
 * the material never does), secret rotation through the platform's
 * store (the replaced reference's material is discarded), and the
 * permission gates -- anonymous 401, and the concealed 404 for a
 * foreign organization's configuration.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
@Import(AuditCapture.class)
class SsoConfigIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";

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
    private tools.jackson.databind.ObjectMapper mapper;

    @Autowired
    private com.shipperdog.api.distribution.ChannelSecrets secrets;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        audit.reset();
    }

    @Test
    void writeIsTypedValidatedAndNeverEchoesMaterial() throws Exception {
        String devToken = devToken();
        UUID orgId = createOrganization(devToken, "Config SSO");

        JsonNode stored = putConfig(devToken, orgId, body("https://login.example.com/tenant/v2.0", null), 200);
        assertThat(stored.get("providerType").asText()).isEqualTo("OIDC");
        assertThat(stored.get("issuerUrl").asText()).isEqualTo("https://login.example.com/tenant/v2.0");
        assertThat(stored.get("clientId").asText()).isEqualTo("shipperdog-console");
        assertThat(stored.get("clientSecretRef").asText()).isNotBlank();
        assertThat(stored.has("clientSecret")).as("the material never echoes").isFalse();
        assertThat(stored.get("provisioningMode").asText()).isEqualTo("INVITED_ONLY");
        assertThat(stored.get("jitDefaultRole").asText()).isEqualTo("VIEWER");
        assertThat(stored.get("requireVerifiedEmail").asBoolean()).isTrue();
        assertThat(stored.get("enabled").asBoolean()).isFalse();
        assertThat(stored.get("scopes").asText()).isEqualTo("openid email");

        JsonNode fetched = client.get()
                .uri("/api/v1/organizations/" + orgId + "/idp-config")
                .header("Authorization", "Bearer " + devToken)
                .retrieve()
                .body(JsonNode.class);
        assertThat(fetched.get("clientSecretRef").asText())
                .isEqualTo(stored.get("clientSecretRef").asText());
        assertThat(audit.eventsOfType(AuditEventType.ORG_IDP_CONFIGURED)).hasSize(1);
    }

    @Test
    void invalidWritesAreRefused() throws Exception {
        String devToken = devToken();
        UUID orgId = createOrganization(devToken, "Invalid SSO");

        assertThat(statusOfPut(devToken, orgId, body("http://login.example.com", null)))
                .isEqualTo(400);
        assertThat(statusOfPut(devToken, orgId, body("https://login.example.com/?q=1", null)))
                .isEqualTo(400);
        assertThat(statusOfPut(devToken, orgId, with(body("https://login.example.com", null), "providerType", "SAML")))
                .isEqualTo(400);
        assertThat(statusOfPut(devToken, orgId, with(body("https://login.example.com", null), "scopes", "email")))
                .isEqualTo(400);
        assertThat(statusOfPut(
                        devToken, orgId, with(body("https://login.example.com", null), "jitDefaultRole", "SUPERUSER")))
                .isEqualTo(400);
        assertThat(statusOfPut(
                        devToken, orgId, with(body("https://login.example.com", null), "provisioningMode", "OPEN")))
                .isEqualTo(400);
        assertThat(statusOfPut(
                        devToken,
                        orgId,
                        with(
                                with(body("https://login.example.com", null), "clientSecret", "one"),
                                "clientSecretRef",
                                "local-envelope://two")))
                .isEqualTo(400);
        assertThat(statusOfPut(devToken, orgId, with(body("https://login.example.com", null), "clientSecret", null)))
                .as("a first write needs a secret")
                .isEqualTo(400);
        assertThat(statusOfPut(devToken, UUID.randomUUID(), body("https://login.example.com", null)))
                .as("unknown organization answers the uniform miss")
                .isEqualTo(404);
    }

    @Test
    void secretRotationDiscardsTheReplacedReference() throws Exception {
        String devToken = devToken();
        UUID orgId = createOrganization(devToken, "Rotate SSO");

        JsonNode first = putConfig(devToken, orgId, body("https://login.example.com", "secret-one"), 200);
        String firstRef = first.get("clientSecretRef").asText();
        assertThat(secrets.resolve(firstRef)).isPresent();

        JsonNode second = putConfig(devToken, orgId, body("https://login.example.com", "secret-two"), 200);
        String secondRef = second.get("clientSecretRef").asText();
        assertThat(secondRef).isNotEqualTo(firstRef);
        assertThat(secrets.resolve(firstRef))
                .as("the replaced reference's material is discarded (the slot doctrine)")
                .isEmpty();
        assertThat(secrets.resolve(secondRef)).isPresent();

        // A write without any secret keeps the standing reference.
        Map<String, Object> noSecret = body("https://login.example.com", "secret-two");
        noSecret.remove("clientSecret");
        JsonNode third = putConfig(devToken, orgId, noSecret, 200);
        assertThat(third.get("clientSecretRef").asText()).isEqualTo(secondRef);
    }

    @Test
    void readMissesAndForeignOrganizationsConcealIdentically() throws Exception {
        String devToken = devToken();
        UUID ownOrg = createOrganization(devToken, "Own SSO");
        UUID foreignOrg = createOrganization(devToken, "Foreign SSO");
        putConfig(devToken, ownOrg, body("https://login.example.com", null), 200);
        putConfig(devToken, foreignOrg, body("https://login.example.com", null), 200);

        assertThat(statusOf(HttpMethod.GET, "/api/v1/organizations/" + UUID.randomUUID() + "/idp-config", devToken))
                .as("unknown organization")
                .isEqualTo(404);
        assertThat(statusOf(
                        HttpMethod.GET,
                        "/api/v1/organizations/" + createOrganization(devToken, "Empty SSO") + "/idp-config",
                        devToken))
                .as("existing organization without configuration: the same miss")
                .isEqualTo(404);

        // An organization administrator of one organization holds no
        // rights in another: the foreign config conceals at the 404 --
        // byte-identical to the genuine miss above.
        String foreignAdminToken = orgAdminToken(devToken, foreignOrg, "Foreign SSO Admin");
        assertThat(statusOf(HttpMethod.GET, "/api/v1/organizations/" + ownOrg + "/idp-config", foreignAdminToken))
                .isEqualTo(404);
        assertThat(statusOfPut(foreignAdminToken, ownOrg, body("https://evil.example.com", null)))
                .isEqualTo(404);

        // And the owning organization's own admin reads it.
        String ownAdminToken = orgAdminToken(devToken, ownOrg, "Own SSO Admin");
        assertThat(statusOf(HttpMethod.GET, "/api/v1/organizations/" + ownOrg + "/idp-config", ownAdminToken))
                .isEqualTo(200);
    }

    @Test
    void anonymousAccessIsRefused() throws Exception {
        String devToken = devToken();
        UUID orgId = createOrganization(devToken, "Anon SSO");
        putConfig(devToken, orgId, body("https://login.example.com", null), 200);
        assertThat(statusOf(HttpMethod.GET, "/api/v1/organizations/" + orgId + "/idp-config", null))
                .isEqualTo(401);
        assertThat(statusOfPut(null, orgId, body("https://login.example.com", null)))
                .isEqualTo(401);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** A valid base body; the secret defaults to material (rotation tests override). */
    private Map<String, Object> body(String issuerUrl, String clientSecret) {
        Map<String, Object> body = new HashMap<>();
        body.put("providerType", "OIDC");
        body.put("issuerUrl", issuerUrl);
        body.put("clientId", "shipperdog-console");
        body.put("clientSecret", clientSecret == null ? "secret-one" : clientSecret);
        return body;
    }

    private static Map<String, Object> with(Map<String, Object> body, String field, Object value) {
        body.put(field, value);
        return body;
    }

    private JsonNode putConfig(String token, UUID organizationId, Map<String, Object> body, int expectedStatus) {
        RestClient.RequestBodySpec spec = client.put().uri("/api/v1/organizations/" + organizationId + "/idp-config");
        if (token != null) {
            spec = spec.header("Authorization", "Bearer " + token);
        }
        return spec.contentType(MediaType.APPLICATION_JSON).body(body).exchange((request, response) -> {
            assertThat(response.getStatusCode().value()).isEqualTo(expectedStatus);
            String text = new String(response.getBody().readAllBytes());
            return text.isBlank() ? null : mapper.readTree(text);
        });
    }

    private int statusOfPut(String token, UUID organizationId, Map<String, Object> body) {
        RestClient.RequestBodySpec spec = client.put().uri("/api/v1/organizations/" + organizationId + "/idp-config");
        if (token != null) {
            spec = spec.header("Authorization", "Bearer " + token);
        }
        return spec.contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) -> response.getStatusCode().value());
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
        String orgId = organizationId.toString();
        String address = email.toLowerCase().replace(' ', '.') + "@admin.example";
        JsonNode invitation = client.post()
                .uri("/api/v1/auth/invitations")
                .header("Authorization", "Bearer " + devToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", address, "organizationId", orgId, "role", "ORG_ADMIN"))
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

    private int statusOf(HttpMethod method, String uri, String token) {
        RestClient.RequestBodySpec spec = client.method(method).uri(uri);
        if (token != null) {
            spec = spec.header("Authorization", "Bearer " + token);
        }
        return spec.exchange((request, response) -> response.getStatusCode().value());
    }
}
