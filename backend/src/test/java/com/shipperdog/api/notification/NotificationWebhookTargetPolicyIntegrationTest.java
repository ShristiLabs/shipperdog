package com.shipperdog.api.notification;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The webhook target policy through the real API (issue #51,
 * S12-01 review): with the checks ON (this class pins the flag back
 * to the production default; the suite-wide property relaxes it for
 * the local receivers), a webhook subscription whose URL names an
 * internal target is refused with the field-addressed 422 -- at
 * create AND at edit, the two writes that can set the URL. Literal
 * loopback/link-local/private addresses and the reserved
 * {@code localhost}/{@code *.internal} names are deterministic
 * offline; {@code *.invalid} is the RFC 6761 never-resolving name
 * for the DNS-failure leg. The address math itself is pinned per
 * range in {@code WebhookTargetPolicyTest}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef",
            "shipperdog.notifications.relay-enabled=false"
        })
@TestPropertySource(properties = "shipperdog.notifications.webhook-allow-private-addresses=false")
@Testcontainers
class NotificationWebhookTargetPolicyIntegrationTest {

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
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    private RestClient client;

    private static UUID org;

    private static String adminToken;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        if (org == null) {
            org = createOrg("Webhook Target Policy Corp");
            adminToken = loginOfOrgRoleIn(org, "wtp-admin");
        }
    }

    @Test
    void internalLiteralTargetsAreRefusedAtCreate() {
        assertUrlRefuses("http://127.0.0.1:8080/hook");
        assertUrlRefuses("http://169.254.169.254/computeMetadata/v1");
        // The private ranges and the IPv6 loopback.
        assertUrlRefuses("http://10.0.0.5/hook");
        assertUrlRefuses("http://172.16.1.1/hook");
        assertUrlRefuses("http://192.168.1.10/hook");
        assertUrlRefuses("http://[::1]:8080/hook");
    }

    @Test
    void reservedAndUnresolvableNamesAreRefused() {
        // localhost resolves (to loopback) -- the DNS path's refusal.
        assertUrlRefuses("http://localhost:9090/hook");
        // The metadata name -- refused by the .internal rule, no DNS.
        assertUrlRefuses("http://metadata.google.internal/computeMetadata/v1");
        // A name nothing answers -- the resolution failure's refusal.
        assertUrlRefuses("https://no-such-host-shipperdog.invalid/hook");
    }

    @Test
    void theSyntaxAndLengthRulesRefuse() {
        assertUrlRefuses("ftp://hooks.example.test/hook");
        assertUrlRefuses("https://user:secret@hooks.example.test/hook");
        assertUrlRefuses("http://hooks.example.test/" + "a".repeat(2049));
    }

    @Test
    void anEditRevalidatesTheTarget() {
        // A standing subscription seeded with a syntactically valid
        // URL (inserted behind the API): pointing it at the metadata
        // endpoint through PATCH must refuse exactly like create.
        UUID subscription = jdbc.queryForObject(
                "INSERT INTO notification_subscriptions "
                        + "(organization_id, event_types, channel, recipient_roles, recipient_user_ids, "
                        + "webhook_url, webhook_secret, created_by) "
                        + "VALUES (?, '[\"RELEASE_PROMOTED\"]'::jsonb, 'WEBHOOK', '[]'::jsonb, '[]'::jsonb, "
                        + "'https://hooks.example.test/standing', 'a-signing-secret-of-32-chars', ?) "
                        + "RETURNING id",
                UUID.class,
                org,
                UUID.randomUUID());
        try {
            client.patch()
                    .uri(subscriptionUri(subscription))
                    .headers(headers -> headers.setBearerAuth(adminToken))
                    .body(Map.of("webhookUrl", "http://169.254.169.254/computeMetadata/v1"))
                    .retrieve()
                    .toBodilessEntity();
            throw new AssertionError("expected the internal edit target to refuse");
        } catch (RestClientResponseException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(422);
            JsonNode problem = mapper.readTree(e.getResponseBodyAsByteArray());
            assertThat(problem.get("code").asString()).isEqualTo("NOTIFICATION_SUBSCRIPTION_INVALID");
            assertThat(problem.get("errors").hasNonNull("webhookUrl")).isTrue();
        }
    }

    // The helpers ---------------------------------------------------------------

    private void assertUrlRefuses(String url) {
        try {
            client.post()
                    .uri(subscriptionsPath())
                    .headers(headers -> headers.setBearerAuth(adminToken))
                    .body(Map.of(
                            "eventTypes",
                            List.of("RELEASE_PROMOTED"),
                            "channel",
                            "WEBHOOK",
                            "webhookUrl",
                            url,
                            "webhookSecret",
                            "a-signing-secret-of-32-chars"))
                    .retrieve()
                    .toBodilessEntity();
            throw new AssertionError("expected 422 for " + url);
        } catch (RestClientResponseException e) {
            assertThat(e.getStatusCode().value()).as("url %s refuses", url).isEqualTo(422);
            JsonNode problem = mapper.readTree(e.getResponseBodyAsByteArray());
            assertThat(problem.get("code").asString()).isEqualTo("NOTIFICATION_SUBSCRIPTION_INVALID");
            assertThat(problem.get("errors").hasNonNull("webhookUrl"))
                    .as("url %s names the field", url)
                    .isTrue();
        }
    }

    private String subscriptionsPath() {
        return "/api/v1/organizations/" + org + "/notification-subscriptions";
    }

    private String subscriptionUri(UUID id) {
        return subscriptionsPath() + "/" + id;
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
                        "Webhook Target Admin"))
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
        return mapper.readTree(tokensJson).get("accessToken").asString();
    }
}
