package com.shipperdog.api.device;

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
import org.springframework.http.HttpHeaders;
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
 * Device identifier uniqueness under genuine concurrency (issue #48,
 * AC1 — the race discipline of TenantUniquenessRaceIntegrationTest):
 * two registrations of the same identifier in one tenant, submitted
 * together, must resolve to one 201 and one precisely-coded 409 — the
 * loser may fail either the service probe or V1's
 * {@code uq_devices_tenant_identifier}, and the constraint's verdict
 * must surface as the named conflict, never a 500. The same identifier
 * racing in TWO DIFFERENT tenants is not a conflict at all: the
 * constraint's grain is the tenant, and both creations win.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
class DeviceRegistrationRaceIntegrationTest {

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
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void concurrentDuplicateIdentifierInOneTenantYieldsOneCreateAndOneNamedConflict() throws Exception {
        World world = world("dvrace-a");
        String identifier = "RACE-SAME-TENANT-1";

        List<Recording> results = registerConcurrently(
                world.adminToken,
                List.of(
                        registration(world.tenantA, world.testerId, "IOS", identifier),
                        registration(world.tenantA, world.testerId, "ANDROID", identifier)));

        assertThat(results.stream().map(r -> r.status).sorted().toList())
                .as("one registration wins; the loser receives the named conflict, never a 500")
                .containsExactly(201, 409);
        Recording loser =
                results.stream().filter(r -> r.status == 409).findFirst().orElseThrow();
        assertThat(loser.body.get("code").asText()).isEqualTo("DEVICE_IDENTIFIER_TAKEN");
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM devices WHERE tenant_id = ? AND identifier = ?",
                Integer.class,
                world.tenantA,
                identifier);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void theSameIdentifierRacingInTwoTenantsIsNotAConflict() throws Exception {
        World world = world("dvrace-b");
        String identifier = "RACE-CROSS-TENANT-1";

        // The tester linked to both tenants registers the same identifier
        // in each tenant at the same moment: the uniqueness grain is the
        // tenant, so both win.
        List<Recording> results = registerConcurrently(
                world.adminToken,
                List.of(
                        registration(world.tenantA, world.testerId, "IOS", identifier),
                        registration(world.tenantB, world.testerId, "ANDROID", identifier)));

        assertThat(results.stream().map(r -> r.status).sorted().toList())
                .as("the identifier uniqueness is tenant-scoped (V1's own constraint grain)")
                .containsExactly(201, 201);
        Integer rows =
                jdbc.queryForObject("SELECT count(*) FROM devices WHERE identifier = ?", Integer.class, identifier);
        assertThat(rows).isEqualTo(2);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private RacedRegistration registration(UUID tenantId, UUID testerId, String platform, String identifier) {
        return new RacedRegistration(
                tenantId,
                Map.of(
                        "testerId", testerId.toString(),
                        "platform", platform,
                        "identifier", identifier));
    }

    private List<Recording> registerConcurrently(String token, List<RacedRegistration> registrations) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(registrations.size());
        ExecutorService pool = Executors.newFixedThreadPool(registrations.size());
        try {
            List<Future<Recording>> futures = registrations.stream()
                    .map(registration -> pool.submit(() -> gatedRegister(barrier, token, registration)))
                    .toList();
            return futures.stream()
                    .map(future -> {
                        try {
                            return future.get(30, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new IllegalStateException("the raced registration did not answer", e);
                        }
                    })
                    .toList();
        } finally {
            pool.shutdownNow();
        }
    }

    private Recording gatedRegister(CyclicBarrier barrier, String token, RacedRegistration registration)
            throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        return exchange("/api/v1/tenants/" + registration.tenantId() + "/devices", token, "POST", registration.body());
    }

    /** One raced registration: the tenant route it addresses and the body it posts. */
    private record RacedRegistration(UUID tenantId, Map<String, Object> body) {}

    private static final class World {
        UUID org;
        String adminToken;
        UUID tenantA;
        UUID tenantB;
        UUID testerId;
    }

    /** An organization, its ORG_ADMIN (the overlay holds every device authority), two tenants, and a tester linked to both. */
    private World world(String slug) {
        World world = new World();
        world.org = createOrg("Dv Race " + slug + " Corp");
        world.adminToken = loginOfOrgRoleIn(world.org, slug + "-race-admin@example.com", "ORG_ADMIN");
        world.tenantA = createTenant(world.adminToken, world.org, "Dv Race " + slug + " A", "dv-race-" + slug + "-a");
        world.tenantB = createTenant(world.adminToken, world.org, "Dv Race " + slug + " B", "dv-race-" + slug + "-b");
        world.testerId = linkedTester(world, slug);
        return world;
    }

    /** Invites, registers, and links one tester to BOTH tenants; returns their tester id. */
    private UUID linkedTester(World world, String slug) {
        String email = slug + "-race-tester@example.com";
        JsonNode invited = exchange(
                        "/api/v1/testers",
                        world.adminToken,
                        "POST",
                        Map.of(
                                "organizationId",
                                world.org.toString(),
                                "email",
                                email,
                                "fullName",
                                "Dv Race Tester " + slug))
                .expect(201);
        exchange(
                        "/api/v1/auth/register",
                        null,
                        "POST",
                        Map.of(
                                "invitationToken",
                                invited.get("invitation").get("invitationToken").asText(),
                                "email",
                                email,
                                "password",
                                PASSWORD,
                                "fullName",
                                "Dv Race Tester " + slug))
                .expect(201);
        for (UUID tenantId : List.of(world.tenantA, world.tenantB)) {
            exchange(
                            "/api/v1/tenants/" + tenantId + "/testers",
                            world.adminToken,
                            "POST",
                            Map.of("testerId", invited.get("id").asText()))
                    .expect(201);
        }
        return UUID.fromString(invited.get("id").asText());
    }

    private UUID createTenant(String token, UUID organizationId, String name, String externalId) {
        return UUID.fromString(exchange(
                        "/api/v1/tenants",
                        token,
                        "POST",
                        Map.of(
                                "organizationId", organizationId.toString(),
                                "name", name,
                                "externalId", externalId))
                .expect(201)
                .get("id")
                .asText());
    }

    private UUID createOrg(String orgName) {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        return UUID.fromString(exchange("/api/v1/organizations", devToken, "POST", Map.of("name", orgName))
                .expect(201)
                .get("id")
                .asText());
    }

    private String loginOfOrgRoleIn(UUID orgId, String email, String role) {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        JsonNode invitation = exchange(
                        "/api/v1/auth/invitations",
                        devToken,
                        "POST",
                        Map.of("email", email, "organizationId", orgId.toString(), "role", role))
                .expect(201);
        exchange(
                        "/api/v1/auth/register",
                        null,
                        "POST",
                        Map.of(
                                "invitationToken",
                                invitation.get("invitationToken").asText(),
                                "email",
                                email,
                                "password",
                                PASSWORD,
                                "fullName",
                                "Dv Race " + role))
                .expect(201);
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
            throw new IllegalStateException("could not parse the token pair", e);
        }
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
