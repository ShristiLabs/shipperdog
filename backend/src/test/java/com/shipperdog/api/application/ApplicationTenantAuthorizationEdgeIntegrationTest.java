package com.shipperdog.api.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.AuditCapture;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
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
 * Adversarial coverage for the tenant-application authorization mapping
 * (issue #15, S3-03), complementing the acceptance suite: concurrent
 * state flips resolve as a state machine (exactly one winner per
 * transition, one audit event per real change), the V11 CHECK pins the
 * revocation pairing at the database, the effective-visibility union
 * never crosses tenants (INV-001), pagination walks both arms in the
 * platform order, and the surface conceals from unauthorized callers.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
@Import(AuditCapture.class)
class ApplicationTenantAuthorizationEdgeIntegrationTest {

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
    private AuditCapture audit;

    @Autowired
    private ObjectMapper mapper;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        audit.reset();
    }

    // -----------------------------------------------------------------------
    // Concurrent state flips resolve as a state machine
    // -----------------------------------------------------------------------

    @Test
    void concurrentRevokesResolveToExactlyOneRevocation() throws Exception {
        UUID orgId = createOrg("Edge Race Revoke Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "e15-race-admin@example.com", "ORG_ADMIN");
        UUID appId = createOrgShared(adminToken, orgId, "Race Revoke Banking", "com.e15.race.revoke");
        UUID tenantId = createTenant(adminToken, orgId, "Race Revoke Bank", "e15-race-1");
        authorize(adminToken, appId, tenantId, null);

        List<Integer> statuses = runConcurrently(
                3,
                () -> exchange("/api/v1/applications/" + appId + "/tenants/" + tenantId, adminToken, "DELETE", null)
                        .status());
        assertThat(statuses.stream().filter(status -> status == 204))
                .as("exactly one concurrent revoke wins, got " + statuses)
                .hasSize(1);
        assertThat(statuses.stream().filter(status -> status == 404))
                .as("the losers see the already-revoked miss, got " + statuses)
                .hasSize(2);
        assertThat(audit.eventsOfType(AuditEventType.APPLICATION_TENANT_DEAUTHORIZED))
                .as("one audit event per real change")
                .hasSize(1);
    }

    @Test
    void concurrentRegrantsResolveToExactlyOneAuditedRegrant() throws Exception {
        UUID orgId = createOrg("Edge Race Regrant Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "e15-regrant-admin@example.com", "ORG_ADMIN");
        UUID appId = createOrgShared(adminToken, orgId, "Race Regrant Banking", "com.e15.race.regrant");
        UUID tenantId = createTenant(adminToken, orgId, "Race Regrant Bank", "e15-regrant-1");
        authorize(adminToken, appId, tenantId, null);
        delete(adminToken, appId, tenantId, 204);

        // From REVOKED, concurrent re-grants: one flip wins (audited),
        // the rest are idempotent successes (no second re-grant event).
        List<Integer> statuses = runConcurrently(
                3,
                () -> exchange("/api/v1/applications/" + appId + "/tenants/" + tenantId, adminToken, "POST", null)
                        .status());
        assertThat(statuses.stream().allMatch(status -> status == 200 || status == 201))
                .as("every concurrent re-grant ends in success, got " + statuses)
                .isTrue();
        assertThat(audit.eventsOfType(AuditEventType.APPLICATION_TENANT_AUTHORIZED))
                .as("the initial grant plus exactly one re-grant event for the flip")
                .hasSize(2);
        assertThat(authorizationRow(appId, tenantId).get("status")).isEqualTo("ACTIVE");
    }

    // -----------------------------------------------------------------------
    // The V11 CHECK pins the revocation pairing
    // -----------------------------------------------------------------------

    @Test
    void v11CheckPinsRevocationPairing() {
        UUID orgId = organizationIdByName("ShipperDog Demo Org");
        UUID tenantId = jdbc.queryForObject("SELECT id FROM tenants WHERE external_id = 'demo-acme'", UUID.class);
        UUID appId = jdbc.queryForObject("SELECT id FROM applications WHERE name = 'Banking'", UUID.class);
        UUID actor = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, DEV_ADMIN_EMAIL);

        // REVOKED without its stamps: rejected.
        assertThat(catchIntegrityViolation(
                        "INSERT INTO tenant_application_authorizations"
                                + " (application_id, tenant_id, status, granted_by, revoked_by)"
                                + " VALUES (?, ?, 'REVOKED', ?, ?)",
                        appId,
                        tenantId,
                        actor,
                        actor))
                .isNotNull();
        // ACTIVE carrying revocation stamps: rejected.
        assertThat(catchIntegrityViolation(
                        "INSERT INTO tenant_application_authorizations"
                                + " (application_id, tenant_id, status, granted_by, revoked_by, revoked_at)"
                                + " VALUES (?, ?, 'ACTIVE', ?, ?, now())",
                        appId,
                        tenantId,
                        actor,
                        actor))
                .isNotNull();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM tenant_application_authorizations WHERE application_id = ?",
                        Integer.class,
                        appId))
                .isZero();
    }

    // -----------------------------------------------------------------------
    // INV-001: the union never crosses tenants
    // -----------------------------------------------------------------------

    @Test
    void effectiveVisibilityNeverCrossesTenants() {
        UUID orgId = createOrg("Edge Isolation Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "e15-iso-admin@example.com", "ORG_ADMIN");
        UUID firstTenantId = createTenant(adminToken, orgId, "Iso Bank", "e15-iso-1");
        UUID secondTenantId = createTenant(adminToken, orgId, "Iso Bank 2", "e15-iso-2");
        UUID firstOwnedId = createTenantOwned(adminToken, firstTenantId, "Iso First Owned", "com.e15.iso.first");
        UUID secondOwnedId = createTenantOwned(adminToken, secondTenantId, "Iso Second Owned", "com.e15.iso.second");
        UUID forFirstId = createOrgShared(adminToken, orgId, "Iso For First", "com.e15.iso.forfirst");
        UUID forSecondId = createOrgShared(adminToken, orgId, "Iso For Second", "com.e15.iso.forsecond");

        authorize(adminToken, forFirstId, firstTenantId, null);
        authorize(adminToken, forSecondId, secondTenantId, null);

        // Each tenant sees exactly its own rows: its owned application
        // and its authorized shared one — never the other tenant's
        // owned rows and never the other tenant's authorization.
        assertThat(listingIds(adminToken, firstTenantId)).containsExactly(firstOwnedId, forFirstId);
        assertThat(listingIds(adminToken, secondTenantId)).containsExactly(secondOwnedId, forSecondId);

        // De-authorization is per tenant: the other keeps its view.
        delete(adminToken, forFirstId, firstTenantId, 204);
        assertThat(listingIds(adminToken, firstTenantId)).containsExactly(firstOwnedId);
        assertThat(listingIds(adminToken, secondTenantId)).containsExactly(secondOwnedId, forSecondId);
    }

    // -----------------------------------------------------------------------
    // Pagination walks both arms in the platform order
    // -----------------------------------------------------------------------

    @Test
    void unionPaginationWalksArmsInCreationOrder() {
        UUID orgId = createOrg("Edge Page Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "e15-page-admin@example.com", "ORG_ADMIN");
        UUID tenantId = createTenant(adminToken, orgId, "Page Bank", "e15-page-1");
        UUID ownedId = createTenantOwned(adminToken, tenantId, "Page Owned", "com.e15.page.owned");
        UUID sharedId = createOrgShared(adminToken, orgId, "Page Shared", "com.e15.page.shared");
        UUID ownedLaterId = createTenantOwned(adminToken, tenantId, "Page Owned Later", "com.e15.page.later");
        authorize(adminToken, sharedId, tenantId, null);

        // Creation order across both arms, one item per page.
        List<UUID> walk = new ArrayList<>();
        String cursor = null;
        do {
            String uri = "/api/v1/tenants/" + tenantId + "/applications?limit=1"
                    + (cursor == null ? "" : "&cursor=" + cursor);
            JsonNode page = get(adminToken, uri, 200);
            page.get("items")
                    .forEach(item -> walk.add(UUID.fromString(item.get("id").asText())));
            cursor = page.has("next_cursor") ? page.get("next_cursor").asText() : null;
        } while (cursor != null);
        assertThat(walk).containsExactly(ownedId, sharedId, ownedLaterId);
    }

    // -----------------------------------------------------------------------
    // Platform administration and tenant-side concealment
    // -----------------------------------------------------------------------

    @Test
    void platformAdministratorGrantsAreAuditedAsCrossTenantAccess() {
        UUID orgId = createOrg("Edge Platform Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "e15-plat-admin@example.com", "ORG_ADMIN");
        UUID appId = createOrgShared(adminToken, orgId, "Platform Banking", "com.e15.plat.banking");
        UUID tenantId = createTenant(adminToken, orgId, "Platform Bank", "e15-plat-1");
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));

        JsonNode granted = post(devToken, "/api/v1/applications/" + appId + "/tenants/" + tenantId, null, 201);
        assertThat(granted.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(audit.eventsOfType(AuditEventType.APPLICATION_TENANT_AUTHORIZED))
                .hasSize(1);
        assertThat(audit.eventsOfType(AuditEventType.CROSS_TENANT_ACCESS))
                .as("the platform-administered org-scope binding is audited")
                .isNotEmpty();
    }

    @Test
    void tenantRolesCannotReadTheAuthorizedTenantList() {
        UUID orgId = createOrg("Edge Tenant List Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "e15-tlist-admin@example.com", "ORG_ADMIN");
        UUID appId = createOrgShared(adminToken, orgId, "Tenant List Banking", "com.e15.tlist.banking");
        UUID tenantId = createTenant(adminToken, orgId, "Tenant List Bank", "e15-tlist-1");
        authorize(adminToken, appId, tenantId, null);
        String tenantAdminToken = tenantAdminIn(tenantId, "e15-tlist-tenant-admin@example.com");

        // The mapping is an organization-level surface: a tenant
        // administrator — even of an authorized tenant — conceal-denies.
        get(tenantAdminToken, "/api/v1/applications/" + appId + "/tenants", 404);
        // While their own listing keeps resolving the authorized arm.
        assertThat(listingIds(tenantAdminToken, tenantId)).contains(appId);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private interface HttpCall {

        int execute();
    }

    private List<Integer> runConcurrently(int threads, HttpCall call) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                calls.add(pool.submit(() -> {
                    start.await();
                    return call.execute();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> each : calls) {
                statuses.add(each.get(30, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    private Map<String, Object> authorizationRow(UUID applicationId, UUID tenantId) {
        return jdbc.queryForMap(
                "SELECT status FROM tenant_application_authorizations WHERE application_id = ? AND tenant_id = ?",
                applicationId,
                tenantId);
    }

    /** Wraps an insert so a constraint violation is returned, not thrown. */
    private org.springframework.dao.DataIntegrityViolationException catchIntegrityViolation(
            String sql, Object... args) {
        try {
            jdbc.update(sql, args);
            return null;
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            return e;
        }
    }

    private void authorize(String token, UUID applicationId, UUID tenantId, String reason) {
        post(
                token,
                "/api/v1/applications/" + applicationId + "/tenants/" + tenantId,
                reason == null ? null : Map.of("reason", reason),
                201);
    }

    private void delete(String token, UUID applicationId, UUID tenantId, int expected) {
        exchange("/api/v1/applications/" + applicationId + "/tenants/" + tenantId, token, "DELETE", null)
                .expect(expected);
    }

    private UUID createTenantOwned(String token, UUID tenantId, String name, String bundleId) {
        Map<String, Object> body = new HashMap<>();
        body.put("tenantId", tenantId.toString());
        body.put("name", name);
        body.put("bundleId", bundleId);
        body.put("platforms", new ArrayList<>(List.of("IOS")));
        return UUID.fromString(
                post(token, "/api/v1/applications", body, 201).get("id").asText());
    }

    private UUID createOrgShared(String token, UUID orgId, String name, String bundleId) {
        Map<String, Object> body = new HashMap<>();
        body.put("ownershipScope", "ORGANIZATION");
        body.put("organizationId", orgId.toString());
        body.put("name", name);
        body.put("bundleId", bundleId);
        body.put("platforms", new ArrayList<>(List.of("IOS")));
        return UUID.fromString(
                post(token, "/api/v1/applications", body, 201).get("id").asText());
    }

    private List<UUID> listingIds(String token, UUID tenantId) {
        JsonNode page = get(token, "/api/v1/tenants/" + tenantId + "/applications?limit=100", 200);
        List<UUID> ids = new ArrayList<>();
        page.get("items").forEach(item -> ids.add(UUID.fromString(item.get("id").asText())));
        return ids;
    }

    private String tenantAdminIn(UUID tenantId, String email) {
        insertOrglessUser(email);
        UUID userId = userIdByEmail(email);
        post(
                adminTokenOf(tenantsOrganizationOf(tenantId)),
                "/api/v1/tenants/" + tenantId + "/members",
                Map.of("userId", userId.toString(), "role", "TENANT_ADMIN"),
                201);
        return accessToken(login(email, PASSWORD));
    }

    private UUID tenantsOrganizationOf(UUID tenantId) {
        return jdbc.queryForObject("SELECT organization_id FROM tenants WHERE id = ?", UUID.class, tenantId);
    }

    private int adminCounter = 0;

    private String adminTokenOf(UUID orgId) {
        return loginOfOrgRoleIn(orgId, "e15-admin-" + orgId + "-" + adminCounter++ + "@example.com", "ORG_ADMIN");
    }

    private UUID createTenant(String token, UUID organizationId, String name, String externalId) {
        JsonNode created = post(
                token,
                "/api/v1/tenants",
                Map.of("organizationId", organizationId.toString(), "name", name, "externalId", externalId),
                201);
        return UUID.fromString(created.get("id").asText());
    }

    private UUID organizationIdByName(String name) {
        return jdbc.queryForObject("SELECT id FROM organizations WHERE name = ?", UUID.class, name);
    }

    private UUID userIdByEmail(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
    }

    private void insertOrglessUser(String email) {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        JsonNode invitation = post(devToken, "/api/v1/auth/invitations", Map.of("email", email), 201);
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
                        "Authorization Edge Test User"))
                .retrieve()
                .body(String.class);
    }

    private UUID createOrg(String orgName) {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        return UUID.fromString(post(devToken, "/api/v1/organizations", Map.of("name", orgName), 201)
                .get("id")
                .asText());
    }

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
                        PASSWORD,
                        "fullName",
                        "Authorization Edge " + role))
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
