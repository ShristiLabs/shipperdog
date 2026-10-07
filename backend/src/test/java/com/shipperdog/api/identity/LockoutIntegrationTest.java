package com.shipperdog.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.audit.AuditEventType;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

/**
 * Failed-login lockout acceptance (issue #3): after the configured number
 * of consecutive failures the account locks, the correct password is
 * rejected while locked, a USER_LOCKED_OUT audit event fires, and the
 * lock is recorded in the database.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
@Import(AuditCapture.class)
class LockoutIntegrationTest {

    private static final String EMAIL = "dev@shipperdog.local";
    private static final String PASSWORD = "shipperdog-dev-admin";

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

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        audit.reset();
        jdbc.update("UPDATE users SET failed_login_attempts = 0, locked_until = NULL WHERE email = ?", EMAIL);
    }

    /**
     * Full-suite ordering hardening (issue #39, of the flake seen on
     * #35's CI run): the locking test legitimately ends with the
     * seeded dev admin LOCKED for the configured duration, and
     * classes that share this Spring context (identical properties —
     * one container) run against that same row afterwards; their
     * dev-admin bootstrap logins then answered 401 and the suite
     * looked order-flaky. Reset the lock when this class is done so
     * the fixture never leaks past it.
     */
    @org.junit.jupiter.api.AfterEach
    void unlockDevAdminAfterwards() {
        jdbc.update("UPDATE users SET failed_login_attempts = 0, locked_until = NULL WHERE email = ?", EMAIL);
    }

    @Test
    void accountLocksAfterConfiguredFailuresAndEmitsAuditEvent() {
        for (int i = 0; i < 4; i++) {
            assertThat(loginStatus("wrong-password-" + i)).isEqualTo(401);
        }
        assertThat(jdbc.queryForObject(
                        "SELECT locked_until FROM users WHERE email = ?", java.sql.Timestamp.class, EMAIL))
                .as("lock triggers only at the threshold")
                .isNull();

        assertThat(loginStatus("wrong-password-4")).isEqualTo(401);
        assertThat(audit.eventsOfType(AuditEventType.USER_LOCKED_OUT)).hasSize(1);

        java.sql.Timestamp lockedUntil =
                jdbc.queryForObject("SELECT locked_until FROM users WHERE email = ?", java.sql.Timestamp.class, EMAIL);
        assertThat(lockedUntil).isNotNull();

        assertThat(loginStatus(PASSWORD))
                .as("correct password is rejected while the account is locked")
                .isEqualTo(401);
        assertThat(audit.eventsOfType(AuditEventType.LOGIN_SUCCESS)).isEmpty();
    }

    @Test
    void successfulLoginResetsTheFailureCounter() {
        assertThat(loginStatus("wrong-password-x")).isEqualTo(401);
        assertThat(loginStatus(PASSWORD)).isEqualTo(200);

        Integer attempts =
                jdbc.queryForObject("SELECT failed_login_attempts FROM users WHERE email = ?", Integer.class, EMAIL);
        assertThat(attempts).isZero();
    }

    private int loginStatus(String password) {
        return client.post()
                .uri("/api/v1/auth/login")
                .body(Map.of("email", EMAIL, "password", password))
                .exchange((request, response) -> response.getStatusCode().value());
    }
}
