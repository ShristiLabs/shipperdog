package com.shipperdog.api.tester;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import tools.jackson.databind.ObjectMapper;

/**
 * Tester creation idempotency under genuine concurrency (issue #26):
 * several invitations of the same address submitted together must
 * collapse onto ONE tester record, ONE live invitation, and ONE
 * delivered email — a single 201 for the winner and idempotent 200s
 * for every loser, never a 500. The loser may fail either the service
 * pre-check or the uq_testers_organization_email constraint; either
 * way the fresh-transaction re-derivation must answer with the
 * winner's record (the issue #13 race discipline, applied to the
 * idempotent-create contract).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
@Import(CapturingEmailSender.class)
class TesterCreationRaceIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";

    private static final int RACERS = 4;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @LocalServerPort
    int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CapturingEmailSender email;

    @Autowired
    private ObjectMapper mapper;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        email.reset();
    }

    @Test
    void concurrentInvitesOfOneAddressCollapseOntoOneTesterAndOneInvitation() throws Exception {
        UUID orgId = createOrg("Tester Race Corp");
        String token = loginOfOrgAdminIn(orgId, "tester-race-admin@example.com");
        Map<String, Object> body = Map.of(
                "organizationId", orgId.toString(),
                "email", "raced@example.com",
                "fullName", "Raced Tester");

        List<Recording> results = postTestersConcurrently(token, body);

        assertThat(results).hasSize(RACERS);
        assertThat(results.stream().filter(recording -> recording.status == 201).count())
                .as("exactly one racer creates the tester")
                .isEqualTo(1);
        assertThat(results.stream().filter(recording -> recording.status == 200).count())
                .as("every other racer receives the existing tester, never a 500")
                .isEqualTo(RACERS - 1);
        List<String> ids = results.stream()
                .map(recording -> recording.body.get("id").asText())
                .distinct()
                .toList();
        assertThat(ids).as("all racers answer the same tester record").hasSize(1);
        assertThat(results.stream()
                        .filter(recording -> recording.status == 200)
                        .allMatch(recording -> !recording.body.get("invitation").has("invitationToken")))
                .as("no loser re-shows or re-mints the token")
                .isTrue();

        Integer testerRows = jdbc.queryForObject(
                "SELECT count(*) FROM testers WHERE organization_id = ? AND email = ?",
                Integer.class,
                orgId,
                "raced@example.com");
        assertThat(testerRows).as("one tester row survives the race").isEqualTo(1);
        Integer pendingInvitations = jdbc.queryForObject(
                "SELECT count(*) FROM user_invitations WHERE tester_id = ("
                        + "SELECT id FROM testers WHERE organization_id = ? AND email = ?) "
                        + "AND status = 'PENDING'",
                Integer.class,
                orgId,
                "raced@example.com");
        assertThat(pendingInvitations).as("exactly one live invitation").isEqualTo(1);
        assertThat(email.sent()).as("exactly one invitation email").hasSize(1);
    }

    private List<Recording> postTestersConcurrently(String token, Map<String, Object> body) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(RACERS);
        ExecutorService pool = Executors.newFixedThreadPool(RACERS);
        try {
            List<Future<Recording>> futures = new java.util.ArrayList<>(RACERS);
            for (int i = 0; i < RACERS; i++) {
                futures.add(pool.submit(() -> gatedPost(barrier, token, body)));
            }
            return futures.stream()
                    .map(future -> {
                        try {
                            return future.get(30, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .toList();
        } finally {
            pool.shutdownNow();
        }
    }

    private Recording gatedPost(CyclicBarrier barrier, String token, Map<String, Object> body) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        return exchange("/api/v1/testers", token, "POST", body);
    }

    private UUID createOrg(String orgName) {
        Recording created = exchange("/api/v1/organizations", devAdminToken(), "POST", Map.of("name", orgName), 201);
        return UUID.fromString(created.body.get("id").asText());
    }

    private String loginOfOrgAdminIn(UUID orgId, String adminEmail) {
        Recording invitation = exchange(
                "/api/v1/auth/invitations",
                devAdminToken(),
                "POST",
                Map.of("email", adminEmail, "organizationId", orgId.toString(), "role", "ORG_ADMIN"),
                201);
        exchange(
                "/api/v1/auth/register",
                null,
                "POST",
                Map.of(
                        "invitationToken",
                        invitation.body.get("invitationToken").asText(),
                        "email",
                        adminEmail,
                        "password",
                        "a-perfectly-valid-password",
                        "fullName",
                        "Race Admin"),
                201);
        return accessToken(login(adminEmail, "a-perfectly-valid-password"));
    }

    private String devAdminToken() {
        return accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
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

    private Recording exchange(String uri, String bearer, String method, Map<String, Object> body, int expected) {
        Recording recording = exchange(uri, bearer, method, body);
        assertThat(recording.status()).as("%s %s -> %d", method, uri, expected).isEqualTo(expected);
        return recording;
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
            JsonNode parsed = bytes.length == 0 ? mapper.createObjectNode() : mapper.readTree(bytes);
            return new Recording(response.getStatusCode().value(), parsed);
        });
    }

    private record Recording(int status, JsonNode body) {}
}
