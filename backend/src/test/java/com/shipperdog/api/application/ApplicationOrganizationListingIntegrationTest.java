package com.shipperdog.api.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * End-to-end acceptance flow for the organization-wide registry listing
 * (issue #16, S3-04): the console's administration surface. AC1 — the
 * listing carries both ownership scopes, including a shared application
 * with zero tenant authorizations (the reachability gap that motivated
 * the surface: every effective-visibility listing hides such rows by
 * construction, PRD 15). AC2 — organization roles read it (write roles
 * and the read-only catalog alike), while tenant-only actors and
 * foreign organizations are conceal-denied (PRD 12). AC3 — the pinned
 * createdAt/id order and the platform's cursor convention.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
class ApplicationOrganizationListingIntegrationTest {

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

    // -----------------------------------------------------------------------
    // AC1: both scopes listed, unauthorized shared applications included
    // -----------------------------------------------------------------------

    @Test
    void listsBothScopesIncludingASharedApplicationWithoutAnyAuthorization() {
        UUID orgId = createOrg("Org Listing Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "o-list-admin@example.com", "ORG_ADMIN");
        UUID tenantId = createTenant(adminToken, orgId, "Org Listing Bank", "o-list-1");

        UUID tenantOwned = createTenantOwned(adminToken, tenantId, "Listing Banking", "com.orglisting.banking");
        // A shared application nobody is authorized for: invisible to
        // every effective-visibility listing — this surface is its only
        // reach (the reason the console needs it).
        UUID shared = createOrgShared(adminToken, orgId, "Listing Shared", "com.orglisting.shared");

        JsonNode page = get(adminToken, "/api/v1/applications?organizationId=" + orgId + "&limit=100", 200);
        List<UUID> listed = idsOf(page);
        assertThat(listed).containsExactlyInAnyOrder(tenantOwned, shared);
        assertThat(scopeOf(page, shared)).isEqualTo("ORGANIZATION");
        assertThat(scopeOf(page, tenantOwned)).isEqualTo("TENANT");
    }

    @Test
    void tenantOwnedRowsOfOtherOrganizationsNeverAppear() {
        UUID orgA = createOrg("Org Listing Isolation A");
        UUID orgB = createOrg("Org Listing Isolation B");
        String adminA = loginOfOrgRoleIn(orgA, "o-iso-a@example.com", "ORG_ADMIN");
        String adminB = loginOfOrgRoleIn(orgB, "o-iso-b@example.com", "ORG_ADMIN");
        UUID tenantB = createTenant(adminB, orgB, "Isolation Bank B", "o-iso-b1");
        UUID appB = createTenantOwned(adminB, tenantB, "Isolation Banking B", "com.orgiso.b");
        UUID sharedB = createOrgShared(adminB, orgB, "Isolation Shared B", "com.orgiso.shared");
        UUID tenantA = createTenant(adminA, orgA, "Isolation Bank A", "o-iso-a1");
        UUID appA = createTenantOwned(adminA, tenantA, "Isolation Banking A", "com.orgiso.a");

        JsonNode pageA = get(adminA, "/api/v1/applications?organizationId=" + orgA + "&limit=100", 200);
        assertThat(idsOf(pageA)).containsExactly(appA);
        assertThat(idsOf(pageA))
                .as("the other organization's rows never appear")
                .doesNotContain(appB, sharedB);
    }

    // -----------------------------------------------------------------------
    // AC2: role gating and concealment
    // -----------------------------------------------------------------------

    @Test
    void readOnlyOrganizationRolesListWhileTenantOnlyActorsAreConcealDenied() {
        UUID orgId = createOrg("Org Listing Roles Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "o-roles-admin@example.com", "ORG_ADMIN");
        String viewerToken = loginOfOrgRoleIn(orgId, "o-roles-viewer@example.com", "VIEWER");
        UUID tenantId = createTenant(adminToken, orgId, "Org Roles Bank", "o-roles-1");
        createTenantOwned(adminToken, tenantId, "Roles Banking", "com.orgroles.banking");

        assertThat(get(viewerToken, "/api/v1/applications?organizationId=" + orgId, 200)
                        .get("items")
                        .size())
                .as("the read-only catalog role lists the registry")
                .isEqualTo(1);

        String tenantAdminToken = tenantAdminIn(tenantId, "o-roles-tenant-admin@example.com");
        JsonNode denial = get(tenantAdminToken, "/api/v1/applications?organizationId=" + orgId, 404);
        assertThat(denial.get("code").asText())
                .as("a tenant-only actor is conceal-denied at the organization target")
                .isEqualTo("ORGANIZATION_NOT_FOUND");

        // A foreign organization's administrator learns nothing either.
        String foreignToken = loginOfOrgRoleIn(createOrg("Org Listing Foreign"), "o-foreign@example.com", "ORG_ADMIN");
        get(foreignToken, "/api/v1/applications?organizationId=" + orgId, 404);
    }

    // -----------------------------------------------------------------------
    // AC3: pinned order and the cursor convention
    // -----------------------------------------------------------------------

    @Test
    void walksPagesInCreationOrderThroughTheOpaqueCursor() {
        UUID orgId = createOrg("Org Listing Pages Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "o-pages-admin@example.com", "ORG_ADMIN");
        UUID tenantId = createTenant(adminToken, orgId, "Org Pages Bank", "o-pages-1");
        List<UUID> created = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            created.add(createTenantOwned(adminToken, tenantId, "Pages Banking " + i, "com.orgpages.banking" + i));
        }

        JsonNode first = get(adminToken, "/api/v1/applications?organizationId=" + orgId + "&limit=2", 200);
        assertThat(idsOf(first)).containsExactly(created.get(0), created.get(1));
        String cursor = first.get("next_cursor").asText();
        assertThat(cursor).isNotEmpty();

        JsonNode second =
                get(adminToken, "/api/v1/applications?organizationId=" + orgId + "&limit=2&cursor=" + cursor, 200);
        assertThat(idsOf(second)).containsExactly(created.get(2));
        assertThat(second.has("next_cursor")).as("the walk terminates").isFalse();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static List<UUID> idsOf(JsonNode page) {
        List<UUID> ids = new ArrayList<>();
        page.get("items").forEach(item -> ids.add(UUID.fromString(item.get("id").asText())));
        return ids;
    }

    private static String scopeOf(JsonNode page, UUID applicationId) {
        for (JsonNode item : page.get("items")) {
            if (UUID.fromString(item.get("id").asText()).equals(applicationId)) {
                return item.get("ownershipScope").asText();
            }
        }
        throw new AssertionError("application not listed: " + applicationId);
    }

    private UUID createTenantOwned(String token, UUID tenantId, String name, String bundleId) {
        Map<String, Object> body = new HashMap<>();
        body.put("tenantId", tenantId.toString());
        body.put("name", name);
        body.put("platforms", List.of("IOS"));
        body.put("bundleId", bundleId);
        return UUID.fromString(
                post(token, "/api/v1/applications", body, 201).get("id").asText());
    }

    private UUID createOrgShared(String token, UUID orgId, String name, String bundleId) {
        Map<String, Object> body = new HashMap<>();
        body.put("ownershipScope", "ORGANIZATION");
        body.put("organizationId", orgId.toString());
        body.put("name", name);
        body.put("platforms", List.of("IOS"));
        body.put("bundleId", bundleId);
        return UUID.fromString(
                post(token, "/api/v1/applications", body, 201).get("id").asText());
    }

    private UUID createTenant(String token, UUID organizationId, String name, String externalId) {
        JsonNode created = post(
                token,
                "/api/v1/tenants",
                Map.of("organizationId", organizationId.toString(), "name", name, "externalId", externalId),
                201);
        return UUID.fromString(created.get("id").asText());
    }

    private UUID createOrg(String orgName) {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        return UUID.fromString(post(devToken, "/api/v1/organizations", Map.of("name", orgName), 201)
                .get("id")
                .asText());
    }

    private String tenantAdminIn(UUID tenantId, String email) {
        // Register the user org-less, then have the org admin add them to
        // the tenant as TENANT_ADMIN (no organization role: exactly the
        // tenant-only actor AC2 needs).
        insertOrglessUser(email);
        UUID userId = userIdByEmail(email);
        post(
                adminTokenOf(tenantsOrganizationOf(tenantId)),
                "/api/v1/tenants/" + tenantId + "/members",
                Map.of("userId", userId.toString(), "role", "TENANT_ADMIN"),
                201);
        return accessToken(login(email, PASSWORD));
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
                        "Org Listing Test User"))
                .retrieve()
                .body(String.class);
    }

    private String adminTokenOf(UUID orgId) {
        return loginOfOrgRoleIn(orgId, "o-helper-admin-" + orgId + "@example.com", "ORG_ADMIN");
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
                        "Org Listing " + role))
                .retrieve()
                .body(String.class);
        return accessToken(login(email, PASSWORD));
    }

    private UUID tenantsOrganizationOf(UUID tenantId) {
        return jdbc.queryForObject("SELECT organization_id FROM tenants WHERE id = ?", UUID.class, tenantId);
    }

    private UUID userIdByEmail(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
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
