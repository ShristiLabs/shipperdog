package com.shipperdog.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.internal.service.PasswordResetService;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Password-reset acceptance (issue #3): requests are indistinguishable for
 * known and unknown addresses, the token is single-use and short-lived,
 * confirming rehashes the password and revokes refresh tokens, and the
 * reset link is observable until the notification module exists (S12-02).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
@Import(AuditCapture.class)
class PasswordResetIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";
    private static final String OLD_PASSWORD = "initial-password-value";
    private static final String NEW_PASSWORD = "fresh-password-after-reset";

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
    }

    @Test
    void resetFlowIsSingleUseAndRevokesSessions() throws Exception {
        String email = uniqueEmail();
        registerUser(email, OLD_PASSWORD);

        ListAppender<ILoggingEvent> logs = attachLogWatcher();
        int known = requestReset(email);
        int unknown = requestReset("nobody@nowhere.example");
        detachLogWatcher(logs);

        assertThat(known).isEqualTo(202);
        assertThat(unknown).as("no account enumeration").isEqualTo(202);
        assertThat(audit.eventsOfType(AuditEventType.PASSWORD_RESET_REQUESTED)).hasSize(2);

        String refreshBeforeReset = loginAndGrabRefresh(email, OLD_PASSWORD);
        String token = extractTokenFromLogs(logs);
        assertThat(token).isNotBlank();

        assertThat(confirmReset(token, NEW_PASSWORD)).isEqualTo(204);

        assertThat(loginStatus(email, OLD_PASSWORD)).isEqualTo(401);
        assertThat(loginStatus(email, NEW_PASSWORD)).isEqualTo(200);
        assertThat(audit.eventsOfType(AuditEventType.PASSWORD_RESET_COMPLETED)).hasSize(1);

        assertThat(statusOf(client.post().uri("/api/v1/auth/refresh").body(Map.of("refreshToken", refreshBeforeReset))))
                .as("pre-reset refresh tokens die with the old password")
                .isEqualTo(401);

        assertThat(confirmReset(token, "another-valid-password"))
                .as("token is single-use")
                .isEqualTo(400);
    }

    @Test
    void expiredTokensAreRejected() throws Exception {
        String email = uniqueEmail();
        registerUser(email, OLD_PASSWORD);

        ListAppender<ILoggingEvent> logs = attachLogWatcher();
        requestReset(email);
        detachLogWatcher(logs);
        String token = extractTokenFromLogs(logs);
        assertThat(token).isNotBlank();

        jdbc.update("UPDATE password_reset_tokens SET expires_at = now() - interval '1 minute'");

        assertThat(confirmReset(token, NEW_PASSWORD))
                .as("expired reset tokens must be rejected")
                .isEqualTo(400);
    }

    private String uniqueEmail() {
        return "reset-" + UUID.randomUUID() + "@example.com";
    }

    private void registerUser(String email, String password) throws Exception {
        String devAccessToken = loginAndGrabAccessToken(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD);
        JsonNode invitation = client.post()
                .uri("/api/v1/auth/invitations")
                .header("Authorization", "Bearer " + devAccessToken)
                .body(Map.of("email", email))
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
                        password,
                        "fullName",
                        "Reset Test User"))
                .retrieve()
                .toBodilessEntity();
    }

    private ListAppender<ILoggingEvent> attachLogWatcher() {
        Logger logger = (Logger) LoggerFactory.getLogger(PasswordResetService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);
        return appender;
    }

    private void detachLogWatcher(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(PasswordResetService.class);
        logger.detachAppender(appender);
    }

    private String extractTokenFromLogs(ListAppender<ILoggingEvent> logs) {
        Pattern pattern = Pattern.compile("token=([^ ]+) \\[userId=");
        for (ILoggingEvent event : logs.list) {
            Matcher matcher = pattern.matcher(event.getFormattedMessage());
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return "";
    }

    private int requestReset(String email) {
        return client.post()
                .uri("/api/v1/auth/password-reset/request")
                .body(Map.of("email", email))
                .exchange((request, response) -> response.getStatusCode().value());
    }

    private int confirmReset(String token, String newPassword) {
        return client.post()
                .uri("/api/v1/auth/password-reset/confirm")
                .body(Map.of("token", token, "newPassword", newPassword))
                .exchange((request, response) -> response.getStatusCode().value());
    }

    private String loginAndGrabAccessToken(String email, String password) throws Exception {
        return mapper.readTree(loginBody(email, password)).get("accessToken").asText();
    }

    private String loginAndGrabRefresh(String email, String password) throws Exception {
        return mapper.readTree(loginBody(email, password)).get("refreshToken").asText();
    }

    private String loginBody(String email, String password) {
        return client.post()
                .uri("/api/v1/auth/login")
                .body(Map.of("email", email, "password", password))
                .retrieve()
                .body(String.class);
    }

    private int statusOf(org.springframework.web.client.RestClient.RequestHeadersSpec<?> request) {
        return request.exchange((req, res) -> res.getStatusCode().value());
    }

    private int loginStatus(String email, String password) {
        return client.post()
                .uri("/api/v1/auth/login")
                .body(Map.of("email", email, "password", password))
                .exchange((request, response) -> response.getStatusCode().value());
    }
}
