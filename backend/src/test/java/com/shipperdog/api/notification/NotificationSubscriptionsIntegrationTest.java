package com.shipperdog.api.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.AuditCapture;
import java.util.Base64;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The subscription surface's acceptance flow (issue #51, S12-01,
 * AC4): the org-scoped CRUD (create, list, detail, edit, delete --
 * the console's routing table), the validation rules (the frozen
 * event vocabulary, known roles and users, the channel-appropriate
 * recipient shape), the concealment posture (a foreign organization's
 * subscription is the miss), the audit trail (created/updated/deleted
 * beside every write, never carrying the webhook secret), and the
 * self-scoped inbox's honest empty page.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef",
            "shipperdog.notifications.relay-enabled=false",
            "shipperdog.notifications.relay-fixed-delay=PT1H"
        })
@Testcontainers
@Import(AuditCapture.class)
class NotificationSubscriptionsIntegrationTest {

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
    private AuditCapture audit;

    @Autowired
    private ObjectMapper mapper;

    private RestClient client;

    private static UUID org;

    private static UUID otherOrg;

    private static String adminToken;

    private static UUID adminUserId;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        audit.reset();
        if (org == null) {
            org = createOrg("Subscriptions Corp");
            otherOrg = createOrg("Subscriptions Foreign Corp");
            adminToken = loginOfOrgRoleIn(org, "sub-admin");
            adminUserId = userIdOf(adminToken);
        }
    }

    // -----------------------------------------------------------------------
    // AC4: the CRUD
    // -----------------------------------------------------------------------

    @Test
    void subscriptionLifecycleIsManageableThroughTheApi() {
        JsonNode created = create(
                Map.of(
                        "eventTypes", List.of("RELEASE_PROMOTED", "FEEDBACK_RECEIVED"),
                        "channel", "IN_APP",
                        "recipientRoles", List.of("ORG_ADMIN")),
                201);
        UUID id = UUID.fromString(created.get("id").asText());
        assertThat(created.get("eventTypes").toString()).contains("RELEASE_PROMOTED");
        assertThat(created.get("enabled").asBoolean()).isTrue();
        // The view never carries the webhook secret (write-only field).
        assertThat(created.has("webhookSecret")).isFalse();

        JsonNode page = get(adminToken, subscriptionsPath() + "?limit=10", 200);
        assertThat(page.get("items").size()).isGreaterThanOrEqualTo(1);
        assertThat(page.get("items").get(0).get("id").asText()).isEqualTo(id.toString());

        JsonNode edited = patch(
                adminToken,
                subscriptionsPath() + "/" + id,
                Map.of("eventTypes", List.of("RELEASE_PROMOTED"), "enabled", false),
                200);
        assertThat(edited.get("eventTypes").size()).isEqualTo(1);
        assertThat(edited.get("enabled").asBoolean()).isFalse();

        assertThat(audit.eventsOfType(AuditEventType.NOTIFICATION_SUBSCRIPTION_CREATED))
                .hasSize(1);
        assertThat(audit.eventsOfType(AuditEventType.NOTIFICATION_SUBSCRIPTION_UPDATED))
                .hasSize(1);
        assertThat(audit.eventsOfType(AuditEventType.NOTIFICATION_SUBSCRIPTION_CREATED)
                        .get(0)
                        .detail()
                        .toString())
                .doesNotContain("secret");

        get(adminToken, subscriptionsPath() + "/" + id, 200);
        delete(adminToken, subscriptionsPath() + "/" + id, 204);
        get(adminToken, subscriptionsPath() + "/" + id, 404);
        assertThat(audit.eventsOfType(AuditEventType.NOTIFICATION_SUBSCRIPTION_DELETED))
                .hasSize(1);
    }

    @Test
    void webhookSubscriptionCarriesUrlAndSecretWriteOnly() {
        JsonNode created = create(
                Map.of(
                        "eventTypes", List.of("RELEASE_PROMOTED"),
                        "channel", "WEBHOOK",
                        "webhookUrl", "https://hooks.example.test/shipperdog",
                        "webhookSecret", "a-signing-secret-of-32-chars"),
                201);
        assertThat(created.get("channel").asString()).isEqualTo("WEBHOOK");
        assertThat(created.get("webhookUrl").asString()).isEqualTo("https://hooks.example.test/shipperdog");
        assertThat(created.has("webhookSecret")).isFalse();

        // Rotation through the edit body; still never rendered back.
        JsonNode rotated = patch(
                adminToken,
                subscriptionsPath() + "/" + created.get("id").asText(),
                Map.of("webhookSecret", "rotated-signing-secret-32-chars"),
                200);
        assertThat(rotated.has("webhookSecret")).isFalse();
    }

    // -----------------------------------------------------------------------
    // The validation rules
    // -----------------------------------------------------------------------

    @Test
    void invalidRoutingFactsRefuseWithTheFieldAddressed422() {
        assertBodyRefuses(
                Map.of(
                        "eventTypes",
                        List.of("NOT_A_REAL_EVENT"),
                        "channel",
                        "IN_APP",
                        "recipientRoles",
                        List.of("ORG_ADMIN")),
                "eventTypes");
        assertBodyRefuses(Map.of("eventTypes", List.of("RELEASE_PROMOTED"), "channel", "IN_APP"), "recipientRoles");
        assertBodyRefuses(
                Map.of(
                        "eventTypes",
                        List.of("RELEASE_PROMOTED"),
                        "channel",
                        "IN_APP",
                        "recipientRoles",
                        List.of("NOT_A_ROLE")),
                "recipientRoles");
        assertBodyRefuses(
                Map.of(
                        "eventTypes",
                        List.of("RELEASE_PROMOTED"),
                        "channel",
                        "IN_APP",
                        "recipientUserIds",
                        List.of(UUID.randomUUID().toString())),
                "recipientUserIds");
        assertBodyRefuses(Map.of("eventTypes", List.of("RELEASE_PROMOTED"), "channel", "WEBHOOK"), "webhookUrl");
        assertBodyRefuses(
                Map.of(
                        "eventTypes",
                        List.of("RELEASE_PROMOTED"),
                        "channel",
                        "WEBHOOK",
                        "webhookUrl",
                        "https://hooks.example.test/x",
                        "webhookSecret",
                        "short"),
                "webhookSecret");
        assertBodyRefuses(
                Map.of(
                        "eventTypes",
                        List.of("RELEASE_PROMOTED"),
                        "channel",
                        "CARRIER_PIGEON",
                        "recipientRoles",
                        List.of("ORG_ADMIN")),
                "channel");
    }

    // -----------------------------------------------------------------------
    // Concealment and self-scope
    // -----------------------------------------------------------------------

    @Test
    void foreignSubscriptionAndForeignOrgConcealAsTheMiss() {
        JsonNode created = create(
                Map.of(
                        "eventTypes", List.of("RELEASE_PROMOTED"),
                        "channel", "IN_APP",
                        "recipientRoles", List.of("ORG_ADMIN")),
                201);
        String foreignPath = "/api/v1/organizations/" + otherOrg + "/notification-subscriptions";
        // The foreign organization's permission target conceals first.
        get(adminToken, foreignPath, 404);
        get(adminToken, foreignPath + "/" + created.get("id").asText(), 404);
        // An unknown subscription in one's own organization is the same miss.
        get(adminToken, subscriptionsPath() + "/" + UUID.randomUUID(), 404);
        // The delivery log conceals the same way.
        get(adminToken, "/api/v1/organizations/" + otherOrg + "/notification-deliveries", 404);
    }

    @Test
    void inboxIsSelfScopedAndHonestlyEmpty() {
        JsonNode inbox = get(adminToken, "/api/v1/notification-inbox?limit=10", 200);
        assertThat(inbox.get("items").size()).isZero();
    }

    @Test
    void deliveryLogValidatesItsFilters() {
        get(adminToken, deliveriesPath() + "?status=MADE_UP", 400);
        JsonNode page = get(adminToken, deliveriesPath() + "?status=FAILED", 200);
        assertThat(page.get("items").size()).isZero();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private String subscriptionsPath() {
        return "/api/v1/organizations/" + org + "/notification-subscriptions";
    }

    private String deliveriesPath() {
        return "/api/v1/organizations/" + org + "/notification-deliveries";
    }

    private void assertBodyRefuses(Map<String, Object> body, String field) {
        try {
            client.post()
                    .uri(subscriptionsPath())
                    .headers(headers -> headers.setBearerAuth(adminToken))
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            throw new AssertionError("expected 422 for " + field);
        } catch (RestClientResponseException e) {
            assertThat(e.getStatusCode().value()).as("field %s refuses", field).isEqualTo(422);
            JsonNode problem = mapper.readTree(e.getResponseBodyAsByteArray());
            assertThat(problem.get("code").asString()).isEqualTo("NOTIFICATION_SUBSCRIPTION_INVALID");
            assertThat(problem.get("errors").hasNonNull(field)).isTrue();
        }
    }

    private JsonNode create(Map<String, Object> body, int expectedStatus) {
        return exchange("POST", subscriptionsPath(), body, expectedStatus, adminToken);
    }

    private JsonNode patch(String bearer, String uri, Map<String, Object> body, int expectedStatus) {
        return exchange("PATCH", uri, body, expectedStatus, bearer);
    }

    private JsonNode exchange(String method, String uri, Map<String, Object> body, int expectedStatus, String bearer) {
        return client.method(HttpMethod.valueOf(method))
                .uri(uri)
                .headers(headers -> {
                    headers.setBearerAuth(bearer);
                    headers.set(HttpHeaders.CONTENT_TYPE, "application/json");
                })
                .body(body)
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode().value())
                            .as("%s %s -> %d", method, uri, expectedStatus)
                            .isEqualTo(expectedStatus);
                    byte[] bytes = response.getBody().readAllBytes();
                    return bytes.length == 0 ? mapper.createObjectNode() : mapper.readTree(bytes);
                });
    }

    private void delete(String bearer, String uri, int expectedStatus) {
        client.delete()
                .uri(uri)
                .headers(headers -> headers.setBearerAuth(bearer))
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(expectedStatus);
                    return null;
                });
    }

    private JsonNode get(String bearer, String uri, int expectedStatus) {
        return client.get()
                .uri(uri)
                .headers(headers -> headers.setBearerAuth(bearer))
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode().value())
                            .as("GET %s -> %d", uri, expectedStatus)
                            .isEqualTo(expectedStatus);
                    byte[] bytes = response.getBody().readAllBytes();
                    return bytes.length == 0 ? mapper.createObjectNode() : mapper.readTree(bytes);
                });
    }

    private UUID createOrg(String name) {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        JsonNode created = client.post()
                .uri("/api/v1/organizations")
                .headers(headers -> headers.setBearerAuth(devToken))
                .body(Map.of("name", name))
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(201);
                    return mapper.readTree(response.getBody().readAllBytes());
                });
        return UUID.fromString(created.get("id").asText());
    }

    private String loginOfOrgRoleIn(UUID orgId, String slug) {
        String email = slug + "@example.com";
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        JsonNode invitation = client.post()
                .uri("/api/v1/auth/invitations")
                .headers(headers -> headers.setBearerAuth(devToken))
                .body(Map.of("email", email, "organizationId", orgId.toString(), "role", "ORG_ADMIN"))
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(201);
                    return mapper.readTree(response.getBody().readAllBytes());
                });
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
                        "Subscriptions Admin"))
                .retrieve()
                .body(String.class);
        return accessToken(login(email, PASSWORD));
    }

    private UUID userIdOf(String bearer) {
        String claims = bearer.split("\\.")[1];
        JsonNode decoded = mapper.readTree(Base64.getUrlDecoder().decode(claims));
        return UUID.fromString(decoded.get("sub").asText());
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
