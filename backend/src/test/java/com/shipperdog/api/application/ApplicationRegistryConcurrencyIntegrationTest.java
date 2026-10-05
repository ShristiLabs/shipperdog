package com.shipperdog.api.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
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
import org.springframework.dao.DataIntegrityViolationException;
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
 * Race and validation depth for the application registry (issue #13):
 * the organization-scope uniqueness guards must stay precise under
 * genuinely concurrent registration — one winner, one named 409, never a
 * 500 from a constraint violation leaking past the re-derivation — and
 * the platform set is canonicalized at the service edge while the V9
 * database checks (non-empty, fixed vocabulary) hold as the last line of
 * defense for any writer that bypasses the service.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
class ApplicationRegistryConcurrencyIntegrationTest {

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
    private ObjectMapper mapper;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    // -----------------------------------------------------------------------
    // Concurrent duplicate registration (AC2 under race)
    // -----------------------------------------------------------------------

    @Test
    void concurrentDuplicateBundleIdRegistrationYieldsOneCreateAndOneNamedConflict() throws Exception {
        UUID orgId = createOrg("Race Bundle Corp");
        String token = loginOfOrgRoleIn(orgId, "race-bundle@example.com", "ORG_ADMIN");
        UUID tenantA = createTenant(token, orgId, "Race Bundle A", "crm-race-b-a");
        UUID tenantB = createTenant(token, orgId, "Race Bundle B", "crm-race-b-b");

        // Distinct names so the losing request can only lose the
        // organization-wide bundle-id guard, whichever side of the race
        // (pre-check or index) it loses on.
        List<Recording> results = postConcurrently(
                token,
                app(tenantA, "Bundle Racer A", List.of("IOS"), "com.racebundle.app", null),
                app(tenantB, "Bundle Racer B", List.of("IOS"), "com.racebundle.app", null));

        assertThat(results.stream().map(r -> r.status).sorted().toList())
                .as("exactly one registration wins; the loser gets the precise conflict, never a 500")
                .containsExactly(201, 409);
        Recording loser =
                results.stream().filter(r -> r.status == 409).findFirst().orElseThrow();
        assertThat(loser.body.get("code").asText()).isEqualTo("APPLICATION_BUNDLE_TAKEN");
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM applications WHERE bundle_id = ?", Integer.class, "com.racebundle.app");
        assertThat(rows)
                .as("the index admits exactly one row per organization identifier")
                .isEqualTo(1);
    }

    @Test
    void concurrentDuplicateNameRegistrationYieldsOneCreateAndOneNamedConflict() throws Exception {
        UUID orgId = createOrg("Race Name Corp");
        String token = loginOfOrgRoleIn(orgId, "race-name@example.com", "ORG_ADMIN");
        UUID tenantId = createTenant(token, orgId, "Race Name Bank", "crm-race-n-1");

        List<Recording> results = postConcurrently(
                token,
                app(tenantId, "Raced Name", List.of("IOS"), "com.racename.a", null),
                app(tenantId, "Raced Name", List.of("IOS"), "com.racename.b", null));

        assertThat(results.stream().map(r -> r.status).sorted().toList()).containsExactly(201, 409);
        Recording loser =
                results.stream().filter(r -> r.status == 409).findFirst().orElseThrow();
        assertThat(loser.body.get("code").asText()).isEqualTo("APPLICATION_NAME_TAKEN");
    }

    @Test
    void concurrentCrossTenantDuplicateNameYieldsOneCreateAndOneNamedConflict() throws Exception {
        UUID orgId = createOrg("Race Cross Tenant Corp");
        String token = loginOfOrgRoleIn(orgId, "race-x-tenant@example.com", "ORG_ADMIN");
        UUID tenantA = createTenant(token, orgId, "Race XT A", "crm-race-xt-a");
        UUID tenantB = createTenant(token, orgId, "Race XT B", "crm-race-xt-b");

        // The same name from TWO DIFFERENT tenants of one organization:
        // V1's per-scope name index admitted both racers silently; the
        // V10 organization-wide index is the final authority for the
        // documented org-wide guard (issue #14).
        List<Recording> results = postConcurrently(
                token,
                app(tenantA, "Cross Tenant Racer", List.of("IOS"), "com.racext.a", null),
                app(tenantB, "Cross Tenant Racer", List.of("IOS"), "com.racext.b", null));

        assertThat(results.stream().map(r -> r.status).sorted().toList())
                .as("exactly one registration wins across tenants; the loser gets the precise conflict")
                .containsExactly(201, 409);
        assertThat(results.stream()
                        .filter(r -> r.status == 409)
                        .findFirst()
                        .orElseThrow()
                        .body
                        .get("code")
                        .asText())
                .isEqualTo("APPLICATION_NAME_TAKEN");
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM applications WHERE name = 'Cross Tenant Racer' AND organization_id = ?",
                Integer.class,
                orgId);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void concurrentCrossScopeDuplicateNameYieldsOneCreateAndOneNamedConflict() throws Exception {
        UUID orgId = createOrg("Race Cross Scope Corp");
        String token = loginOfOrgRoleIn(orgId, "race-x-scope@example.com", "ORG_ADMIN");
        UUID tenantA = createTenant(token, orgId, "Race XS A", "crm-race-xs-a");

        // A tenant-owned registration racing an organization-shared one
        // on the same name: one ownership mode per name, organization-wide.
        Map<String, Object> shared = new HashMap<>();
        shared.put("ownershipScope", "ORGANIZATION");
        shared.put("organizationId", orgId.toString());
        shared.put("name", "Cross Scope Racer");
        shared.put("bundleId", "com.racexs.shared");
        shared.put("platforms", new ArrayList<>(List.of("IOS")));
        List<Recording> results = postConcurrently(
                token, app(tenantA, "Cross Scope Racer", List.of("IOS"), "com.racexs.tenant", null), shared);

        assertThat(results.stream().map(r -> r.status).sorted().toList()).containsExactly(201, 409);
        assertThat(results.stream()
                        .filter(r -> r.status == 409)
                        .findFirst()
                        .orElseThrow()
                        .body
                        .get("code")
                        .asText())
                .isEqualTo("APPLICATION_NAME_TAKEN");
    }

    // -----------------------------------------------------------------------
    // Platform set canonicalization (duplicates, case, whitespace, nulls)
    // -----------------------------------------------------------------------

    @Test
    void platformInputsAreCanonicalizedToUppercaseUniques() throws Exception {
        UUID orgId = createOrg("Canon Platform Corp");
        String token = loginOfOrgRoleIn(orgId, "race-canon@example.com", "ORG_ADMIN");
        UUID tenantId = createTenant(token, orgId, "Canon Platform Bank", "crm-race-c-1");

        JsonNode ios = post(
                token,
                "/api/v1/applications",
                app(tenantId, "Canonical Ios", List.of("IOS", "ios", " IOS "), "com.racecanon.ios", null),
                201);
        assertThat(ios.get("platforms").size())
                .as("duplicates and case fold to one canonical value")
                .isEqualTo(1);
        assertThat(ios.get("platforms").get(0).asText()).isEqualTo("IOS");

        JsonNode android = post(
                token,
                "/api/v1/applications",
                app(tenantId, "Canonical Android", List.of("android", "ANDROID"), null, "com.racecanon.android"),
                201);
        assertThat(android.get("platforms").size()).isEqualTo(1);
        assertThat(android.get("platforms").get(0).asText()).isEqualTo("ANDROID");

        // A null entry is junk input, not a foldable duplicate.
        post(
                token,
                "/api/v1/applications",
                app(tenantId, "Null Platform", Arrays.asList("IOS", null), "com.racecanon.null", null),
                400);
    }

    // -----------------------------------------------------------------------
    // V9 database checks: defense in depth for non-service writers
    // -----------------------------------------------------------------------

    @Test
    void databasePlatformChecksRejectEmptySetsAndUnknownVocabulary() throws Exception {
        UUID orgId = createOrg("Db Check Corp");
        String token = loginOfOrgRoleIn(orgId, "db-check@example.com", "ORG_ADMIN");
        UUID tenantId = createTenant(token, orgId, "Db Check Bank", "crm-race-db-1");

        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO applications (id, organization_id, tenant_id, name, platforms) "
                                + "VALUES (?, ?, ?, ?, ?::varchar[])",
                        UUID.randomUUID(),
                        orgId,
                        tenantId,
                        "Db Check Empty",
                        "{}"))
                .as("ck_applications_platforms_nonempty holds at the database")
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO applications (id, organization_id, tenant_id, name, platforms) "
                                + "VALUES (?, ?, ?, ?, ?::varchar[])",
                        UUID.randomUUID(),
                        orgId,
                        tenantId,
                        "Db Check Vocabulary",
                        "{WEBOS}"))
                .as("ck_applications_platforms_vocabulary holds at the database")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Registration body builder; null fields are omitted entirely. */
    private static Map<String, Object> app(
            UUID tenantId, String name, List<String> platforms, String bundleId, String packageName) {
        Map<String, Object> body = new HashMap<>();
        body.put("tenantId", tenantId.toString());
        if (name != null) {
            body.put("name", name);
        }
        if (bundleId != null) {
            body.put("bundleId", bundleId);
        }
        if (packageName != null) {
            body.put("packageName", packageName);
        }
        body.put("platforms", new ArrayList<>(platforms));
        return body;
    }

    /**
     * Posts both bodies from two threads released by a shared barrier, so
     * the two requests are in flight together: each may pass the service
     * pre-checks before the other commits, which is exactly the race the
     * database guards and the REQUIRES_NEW re-derivation exist for. Either
     * interleaving must produce the same external outcome.
     */
    private List<Recording> postConcurrently(String token, Map<String, Object> bodyA, Map<String, Object> bodyB)
            throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Recording> first = pool.submit(() -> gatedPost(barrier, token, bodyA));
            Future<Recording> second = pool.submit(() -> gatedPost(barrier, token, bodyB));
            return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private Recording gatedPost(CyclicBarrier barrier, String token, Map<String, Object> body) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        return exchange("/api/v1/applications", token, "POST", body);
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
                        "a-perfectly-valid-password",
                        "fullName",
                        "Registry Test User"))
                .retrieve()
                .body(String.class);
        return accessToken(login(email, "a-perfectly-valid-password"));
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
