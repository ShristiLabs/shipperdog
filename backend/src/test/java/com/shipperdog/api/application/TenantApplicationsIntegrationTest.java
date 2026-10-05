package com.shipperdog.api.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.AuditCapture;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
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
 * End-to-end acceptance flow for the tenant-owned applications listing
 * (issue #11, S2-03, FR-002 "assign applications" read side): the
 * listing returns exactly the tenant's own applications (other
 * tenants' rows and organization-owned rows are invisible by
 * construction of the seam), it is readable by both tenant roles, and
 * denials conceal as the tenant-not-found envelope.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
@Import(AuditCapture.class)
class TenantApplicationsIntegrationTest {

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

    @Test
    void listingReturnsExactlyTheTenantsOwnApplications() throws Exception {
        String adminToken = loginOfOrgAdmin("a-list-admin@example.com", "App List Corp");
        UUID orgId = organizationIdByName("App List Corp");
        UUID ownTenant = createTenant(adminToken, orgId, "App Own Bank", "crm-a-own-1");
        UUID otherTenant = createTenant(adminToken, orgId, "App Other Bank", "crm-a-other-1");

        UUID banking = insertApplication(orgId, ownTenant, "Banking");
        UUID payments = insertApplication(orgId, ownTenant, "Payments");
        UUID foreign = insertApplication(orgId, otherTenant, "Foreign Bank");
        UUID orgOwned = insertApplication(orgId, null, "Org Level App");

        JsonNode page = get(adminToken, "/api/v1/tenants/" + ownTenant + "/applications", 200);
        assertThat(page.get("items").size())
                .as("only the tenant's own applications appear (AC3)")
                .isEqualTo(2);
        Set<UUID> listed = new HashSet<>();
        page.get("items").forEach(item -> {
            listed.add(UUID.fromString(item.get("id").asText()));
            assertThat(item.get("status").asText()).isEqualTo("ACTIVE");
            assertThat(item.has("bundleId")).isTrue();
        });
        assertThat(listed).containsExactlyInAnyOrder(banking, payments);
        assertThat(listed)
                .as("other tenants' and organization-owned rows are invisible by construction")
                .doesNotContain(foreign, orgOwned);
    }

    @Test
    void bothTenantRolesAndTheOwningOrgReadTheListing() throws Exception {
        String adminToken = loginOfOrgAdmin("a-roles-admin@example.com", "App Roles Corp");
        UUID orgId = organizationIdByName("App Roles Corp");
        UUID tenantId = createTenant(adminToken, orgId, "App Roles Bank", "crm-a-roles-1");
        insertApplication(orgId, tenantId, "Roles Banking");

        UUID tenantAdminId = userIdByEmail(insertOrglessUser("a-roles-tadmin@example.com"));
        assignMember(adminToken, tenantId, tenantAdminId, "TENANT_ADMIN");
        String tenantAdminToken = accessToken(login("a-roles-tadmin@example.com", PASSWORD));
        UUID testerId = userIdByEmail(insertOrglessUser("a-roles-tester@example.com"));
        assignMember(adminToken, tenantId, testerId, "TENANT_TESTER");
        String testerToken = accessToken(login("a-roles-tester@example.com", PASSWORD));

        assertThat(get("/api/v1/tenants/" + tenantId + "/applications", tenantAdminToken).status)
                .as("tenant administrators read the tenant's applications (AC4)")
                .isEqualTo(200);
        Recording testerRaw = get("/api/v1/tenants/" + tenantId + "/applications", testerToken);
        assertThat(testerRaw.status).isEqualTo(200);
        JsonNode testerView = get(testerToken, "/api/v1/tenants/" + tenantId + "/applications", 200);
        assertThat(testerView.get("items").size())
                .as("tenant testers are read-only, not blind (AC4)")
                .isEqualTo(1);
        assertThat(get("/api/v1/tenants/" + tenantId + "/applications", adminToken).status)
                .as("the owning organization's administrators read it too")
                .isEqualTo(200);
    }

    @Test
    void denialsConcealAndUnknownTenantsAreAGenuineMiss() throws Exception {
        String ownerToken = loginOfOrgAdmin("a-conceal-admin@example.com", "App Conceal Corp");
        UUID orgId = organizationIdByName("App Conceal Corp");
        UUID tenantId = createTenant(ownerToken, orgId, "Conceal Bank", "crm-a-conceal-1");
        insertApplication(orgId, tenantId, "Conceal Banking");

        String outsiderToken = accessToken(registerOrglessUser("a-outsider@example.com"));
        Recording concealed = get("/api/v1/tenants/" + tenantId + "/applications", outsiderToken);
        assertThat(concealed.status)
                .as("callers without a role in the tenant get the concealed 404")
                .isEqualTo(404);

        // An admin of a DIFFERENT organization's tenant gets the same
        // shape — the listing cannot even confirm the tenant exists.
        String otherOrgAdminToken = loginOfOrgAdmin("a-other-admin@example.com", "App Other Corp");
        UUID otherOrgId = organizationIdByName("App Other Corp");
        UUID otherOrgTenant = createTenant(otherOrgAdminToken, otherOrgId, "Cross Bank", "crm-a-cross-1");
        UUID otherOrgAdminMember = userIdByEmail(insertOrglessUser("a-cross-tadmin@example.com"));
        assignMember(otherOrgAdminToken, otherOrgTenant, otherOrgAdminMember, "TENANT_ADMIN");
        String crossTenantAdminToken = accessToken(login("a-cross-tadmin@example.com", PASSWORD));
        Recording crossDenied = get("/api/v1/tenants/" + tenantId + "/applications", crossTenantAdminToken);
        assertThat(crossDenied.status).isEqualTo(404);

        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        Recording genuineMiss = get("/api/v1/tenants/" + UUID.randomUUID() + "/applications", devToken);
        assertThat(genuineMiss.status)
                .as("platform admins reach the service and see the genuine miss")
                .isEqualTo(404);
        assertThat(crossDenied.body.get("code").asText())
                .as("denial and genuine miss share the TENANT_NOT_FOUND envelope (PRD 12)")
                .isEqualTo(genuineMiss.body.get("code").asText())
                .isEqualTo("TENANT_NOT_FOUND");
    }

    @Test
    void listingPagesWithTheCursorContract() throws Exception {
        String adminToken = loginOfOrgAdmin("a-page-admin@example.com", "App Page Corp");
        UUID orgId = organizationIdByName("App Page Corp");
        UUID tenantId = createTenant(adminToken, orgId, "Page Bank", "crm-a-page-1");
        Set<UUID> created = new LinkedHashSet<>();
        for (int i = 0; i < 3; i++) {
            created.add(insertApplication(orgId, tenantId, "Paged App " + i));
        }

        Set<UUID> seen = new HashSet<>();
        String cursor = "";
        int pages = 0;
        while (true) {
            JsonNode page = get(
                    adminToken,
                    "/api/v1/tenants/" + tenantId + "/applications?limit=2"
                            + (cursor.isEmpty() ? "" : "&cursor=" + cursor),
                    200);
            assertThat(page.get("items").size()).as("limit is honored").isLessThanOrEqualTo(2);
            page.get("items")
                    .forEach(item -> seen.add(UUID.fromString(item.get("id").asText())));
            pages++;
            if (!page.hasNonNull("next_cursor")) {
                break;
            }
            cursor = page.get("next_cursor").asText();
        }
        assertThat(seen).as("the walk covers every application, no gaps").containsExactlyInAnyOrderElementsOf(created);
        assertThat(pages).as("three applications at limit=2 span two pages").isEqualTo(2);

        assertThat(get("/api/v1/tenants/" + tenantId + "/applications?limit=0", adminToken).status)
                .isEqualTo(400);
    }

    @Test
    void deactivatedTenantsKeepAReadableListing() throws Exception {
        String adminToken = loginOfOrgAdmin("a-deact-admin@example.com", "App Deact Corp");
        UUID orgId = organizationIdByName("App Deact Corp");
        UUID tenantId = createTenant(adminToken, orgId, "Deact Bank", "crm-a-deact-1");
        insertApplication(orgId, tenantId, "Deact Banking");
        put(adminToken, "/api/v1/tenants/" + tenantId, Map.of("status", "DEACTIVATED"), 200);

        JsonNode page = get(adminToken, "/api/v1/tenants/" + tenantId + "/applications", 200);
        assertThat(page.get("items").size())
                .as("a deactivated tenant stays administrable and its data readable (issue #9/#10)")
                .isEqualTo(1);
    }

    @Test
    void platformAdminOverrideIsAuditedOnTheApplicationsRoute() throws Exception {
        String adminToken = loginOfOrgAdmin("a-plat-admin@example.com", "App Plat Corp");
        UUID orgId = organizationIdByName("App Plat Corp");
        UUID tenantId = createTenant(adminToken, orgId, "Plat Bank", "crm-a-plat-1");
        insertApplication(orgId, tenantId, "Plat Banking");

        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        JsonNode page = get(devToken, "/api/v1/tenants/" + tenantId + "/applications", 200);
        assertThat(page.get("items").size()).isEqualTo(1);
        assertThat(audit.eventsOfType(AuditEventType.CROSS_TENANT_ACCESS))
                .as("every platform-admin admission on a tenant-scoped route is audited (issue #10)")
                .isNotEmpty();
    }

    @Test
    void suspensionStripsTheTestersApplicationsRead() throws Exception {
        String adminToken = loginOfOrgAdmin("a-susp-admin@example.com", "App Susp Corp");
        UUID orgId = organizationIdByName("App Susp Corp");
        UUID tenantId = createTenant(adminToken, orgId, "Susp Bank", "crm-a-susp-1");
        insertApplication(orgId, tenantId, "Susp Banking");
        UUID testerId = userIdByEmail(insertOrglessUser("a-susp-tester@example.com"));
        assignMember(adminToken, tenantId, testerId, "TENANT_TESTER");
        String testerToken = accessToken(login("a-susp-tester@example.com", PASSWORD));
        assertThat(get("/api/v1/tenants/" + tenantId + "/applications", testerToken).status)
                .isEqualTo(200);

        put(
                adminToken,
                "/api/v1/tenants/" + tenantId + "/members/" + testerId + "/status",
                Map.of("status", "SUSPENDED"),
                200);
        assertThat(get("/api/v1/tenants/" + tenantId + "/applications", testerToken).status)
                .as("both switches must be on for every tenant-scoped read, applications included")
                .isEqualTo(404);

        put(
                adminToken,
                "/api/v1/tenants/" + tenantId + "/members/" + testerId + "/status",
                Map.of("status", "ACTIVE"),
                200);
        assertThat(get("/api/v1/tenants/" + tenantId + "/applications", testerToken).status)
                .as("reactivation restores the read")
                .isEqualTo(200);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private UUID insertApplication(UUID organizationId, UUID tenantId, String name) {
        jdbc.update(
                "INSERT INTO applications (organization_id, tenant_id, name) VALUES (?, ?, ?)",
                organizationId,
                tenantId,
                name);
        return jdbc.queryForObject(
                "SELECT id FROM applications WHERE organization_id = ? AND name = ?", UUID.class, organizationId, name);
    }

    private void assignMember(String token, UUID tenantId, UUID userId, String role) {
        post(token, "/api/v1/tenants/" + tenantId + "/members", Map.of("userId", userId.toString(), "role", role), 201);
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

    private String insertOrglessUser(String email) {
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
                        "Application Test User"))
                .retrieve()
                .body(String.class);
        return email;
    }

    private String registerOrglessUser(String email) {
        insertOrglessUser(email);
        return login(email, PASSWORD);
    }

    private String loginOfOrgAdmin(String email, String orgName) {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        UUID orgId = UUID.fromString(post(devToken, "/api/v1/organizations", Map.of("name", orgName), 201)
                .get("id")
                .asText());
        JsonNode invitation = post(
                devToken,
                "/api/v1/auth/invitations",
                Map.of("email", email, "organizationId", orgId.toString(), "role", "ORG_ADMIN"),
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
                        "Org Admin"))
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
        Recording response = exchange(uri, bearer, "POST", body);
        assertThat(response.status).as("POST %s", uri).isEqualTo(expectedStatus);
        return response.body;
    }

    private JsonNode put(String bearer, String uri, Map<String, Object> body, int expectedStatus) {
        Recording response = exchange(uri, bearer, "PUT", body);
        assertThat(response.status).as("PUT %s", uri).isEqualTo(expectedStatus);
        return response.body;
    }

    private Recording get(String uri, String bearer) {
        return exchange(uri, bearer, "GET", null);
    }

    private JsonNode get(String bearer, String uri, int expectedStatus) {
        Recording response = exchange(uri, bearer, "GET", null);
        assertThat(response.status).as("GET %s", uri).isEqualTo(expectedStatus);
        return response.body;
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

    private record Recording(int status, String contentType, JsonNode body) {}
}
