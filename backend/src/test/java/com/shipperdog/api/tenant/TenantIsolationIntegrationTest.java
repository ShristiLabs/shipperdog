package com.shipperdog.api.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.AuditCapture;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * The exhaustive cross-access matrix of tenant isolation (INV-001, issue
 * #10, acceptance criterion 5): two organizations, one tenant each,
 * seeded members, and every actor-by-target-by-endpoint combination.
 * Cross-tenant attempts render as concealed 404s byte-shaped like
 * genuine misses (PRD 12), nothing about the other tenant ever appears
 * in a response body, the platform-administrator override reaches both
 * tenants and is audited, and organization roles can never trigger it.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
@Import(AuditCapture.class)
class TenantIsolationIntegrationTest {

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
    void crossAccessMatrixReturnsZeroLeaks() throws Exception {
        String orgOneAdmin = loginOfOrgAdmin("iso-one-admin@example.com", "Isolation One Corp");
        String orgTwoAdmin = loginOfOrgAdmin("iso-two-admin@example.com", "Isolation Two Corp");
        UUID tenantA = createTenant(orgOneAdmin, organizationIdByName("Isolation One Corp"), "Matrix Alpha", "iso-m-a");
        UUID tenantB = createTenant(orgTwoAdmin, organizationIdByName("Isolation Two Corp"), "Matrix Beta", "iso-m-b");
        UUID adminA = userIdByEmail(insertOrglessUser("iso-a-admin@example.com"));
        UUID adminB = userIdByEmail(insertOrglessUser("iso-b-admin@example.com"));
        put(
                orgOneAdmin,
                "/api/v1/tenants/" + tenantA + "/members/" + adminA + "/role",
                Map.of("role", "TENANT_ADMIN"),
                200);
        put(
                orgTwoAdmin,
                "/api/v1/tenants/" + tenantB + "/members/" + adminB + "/role",
                Map.of("role", "TENANT_ADMIN"),
                200);
        String tenantAdminA = accessToken(login("iso-a-admin@example.com", PASSWORD));
        String tenantAdminB = accessToken(login("iso-b-admin@example.com", PASSWORD));
        UUID stranger = userIdByEmail(insertOrglessUser("iso-matrix-stranger@example.com"));

        Map<String, String> actors = new LinkedHashMap<>();
        actors.put("tenant-admin-A", tenantAdminA);
        actors.put("tenant-admin-B", tenantAdminB);
        Map<UUID, String> targets = new LinkedHashMap<>();
        targets.put(tenantA, "Matrix Alpha");
        targets.put(tenantB, "Matrix Beta");

        List<String> leaks = new ArrayList<>();
        for (Map.Entry<UUID, String> addressed : targets.entrySet()) {
            String otherName = targets.entrySet().stream()
                    .filter(entry -> !entry.getKey().equals(addressed.getKey()))
                    .findFirst()
                    .orElseThrow()
                    .getValue();
            for (Map.Entry<String, String> actor : actors.entrySet()) {
                boolean own = (actor.getKey().endsWith("A")
                                && addressed.getValue().endsWith("Alpha"))
                        || (actor.getKey().endsWith("B") && addressed.getValue().endsWith("Beta"));
                UUID targetId = addressed.getKey();
                // Read surface: own tenants answer, foreign tenants conceal.
                expect(actor.getValue(), "GET", "/api/v1/tenants/" + targetId, own ? 200 : 404, leaks, otherName);
                expect(
                        actor.getValue(),
                        "GET",
                        "/api/v1/tenants/" + targetId + "/members",
                        own ? 200 : 404,
                        leaks,
                        otherName);
                // Tenant admins hold no tenant:tenants:update: the denial is
                // concealed as 404 even on their own tenant (same shape as
                // a cross-tenant denial).
                expect(actor.getValue(), "PUT", "/api/v1/tenants/" + targetId, 404, leaks, otherName);
                // Empty role body reaches the handler only on the own
                // tenant (invalid role: 400); cross-tenant is concealed 404.
                expect(
                        actor.getValue(),
                        "PUT",
                        "/api/v1/tenants/" + targetId + "/members/" + adminA + "/role",
                        own ? 400 : 404,
                        leaks,
                        otherName);
                // The stranger is a member of neither tenant: a genuine 404
                // on the own tenant, a concealed 404 across, no mutation.
                expect(
                        actor.getValue(),
                        "DELETE",
                        "/api/v1/tenants/" + targetId + "/members/" + stranger,
                        404,
                        leaks,
                        otherName);
            }
        }

        // Every denied body must be the concealed shape: the same JSON as a
        // genuine unknown-target miss for the same actor and endpoint,
        // modulo the per-request trace_id (PRD 12; acceptance criterion 2).
        String denied = exchange("/api/v1/tenants/" + tenantB, tenantAdminA, "GET", null)
                .body();
        String genuineMiss = exchange("/api/v1/tenants/" + UUID.randomUUID(), tenantAdminA, "GET", null)
                .body();
        assertThat(withoutTraceId(denied))
                .as("a cross-tenant denial is indistinguishable from an unknown tenant")
                .isEqualTo(withoutTraceId(genuineMiss));
        String deniedMembers = exchange("/api/v1/tenants/" + tenantA + "/members", tenantAdminB, "GET", null)
                .body();
        String genuineMissMembers = exchange(
                        "/api/v1/tenants/" + UUID.randomUUID() + "/members", tenantAdminB, "GET", null)
                .body();
        assertThat(withoutTraceId(deniedMembers)).isEqualTo(withoutTraceId(genuineMissMembers));

        assertThat(leaks)
                .as("the cross-access matrix returned zero leaks (issue #10, acceptance criterion 5)")
                .isEmpty();
    }

    @Test
    void platformAdminOverrideIsExplicitAuditedAndClosedToOrgRoles() throws Exception {
        String orgOneAdmin = loginOfOrgAdmin("iso-ov-one-admin@example.com", "Isolation Override One Corp");
        String orgTwoAdmin = loginOfOrgAdmin("iso-ov-two-admin@example.com", "Isolation Override Two Corp");
        UUID tenantA = createTenant(
                orgOneAdmin, organizationIdByName("Isolation Override One Corp"), "Override Alpha", "iso-ov-a");
        UUID tenantB = createTenant(
                orgTwoAdmin, organizationIdByName("Isolation Override Two Corp"), "Override Beta", "iso-ov-b");
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));

        audit.reset();
        assertThat(get(devToken, "/api/v1/tenants/" + tenantA, 200).get("name").asText())
                .isEqualTo("Override Alpha");
        assertThat(get(devToken, "/api/v1/tenants/" + tenantB, 200).get("name").asText())
                .isEqualTo("Override Beta");
        assertThat(get(devToken, "/api/v1/tenants/" + tenantA + "/members", 200)
                        .get("items")
                        .size())
                .isZero();

        List<AuditEvent> overrides = audit.eventsOfType(AuditEventType.CROSS_TENANT_ACCESS);
        assertThat(overrides)
                .as("every platform-admin tenant access is audited (issue #10, acceptance criterion 4)")
                .hasSize(3)
                .allSatisfy(event -> {
                    assertThat(event.userId().toString())
                            .isEqualTo(userIdByEmail(DEV_ADMIN_EMAIL).toString());
                    assertThat(event.tenantId()).isIn(tenantA, tenantB);
                    assertThat(event.detail()).containsKeys("method", "uri");
                });

        audit.reset();
        assertThat(get(orgOneAdmin, "/api/v1/tenants/" + tenantA, 200)
                        .get("name")
                        .asText())
                .isEqualTo("Override Alpha");
        assertThat(get("/api/v1/tenants/" + tenantB, orgOneAdmin).status)
                .as("an organization administrator of one org cannot read another org's tenant")
                .isEqualTo(404);
        assertThat(audit.eventsOfType(AuditEventType.CROSS_TENANT_ACCESS))
                .as("the override path is unreachable by organization roles")
                .isEmpty();
    }

    @Test
    void outsidersAndAnonymousSeeNothing() throws Exception {
        String orgAdmin = loginOfOrgAdmin("iso-out-admin@example.com", "Isolation Out Corp");
        UUID tenantId = createTenant(orgAdmin, organizationIdByName("Isolation Out Corp"), "Out Bank", "iso-out-1");
        String outsider = registerOrglessUser("iso-outsider@example.com");

        assertThat(get("/api/v1/tenants/" + tenantId, outsider).status).isEqualTo(404);
        assertThat(get("/api/v1/tenants/" + tenantId + "/members", outsider).status)
                .isEqualTo(404);
        assertThat(get("/api/v1/tenants/" + tenantId, null).status)
                .as("anonymous calls are rejected by the security chain")
                .isEqualTo(401);
    }

    @Test
    void deactivatedTenantsStayAdministrableAtTheDataSeam() throws Exception {
        String orgAdmin = loginOfOrgAdmin("iso-deact-admin@example.com", "Isolation Deact Corp");
        UUID orgId = organizationIdByName("Isolation Deact Corp");
        UUID tenantId = createTenant(orgAdmin, orgId, "Deact Seam Bank", "iso-deact-1");
        UUID memberId = userIdByEmail(insertOrglessUser("iso-deact-member@example.com"));
        put(
                orgAdmin,
                "/api/v1/tenants/" + tenantId + "/members/" + memberId + "/role",
                Map.of("role", "TENANT_ADMIN"),
                200);
        String memberToken = accessToken(login("iso-deact-member@example.com", PASSWORD));

        put(orgAdmin, "/api/v1/tenants/" + tenantId, Map.of("status", "DEACTIVATED"), 200);

        assertThat(get(memberToken, "/api/v1/tenants/" + tenantId + "/members", 200)
                        .get("items")
                        .size())
                .as("recorded decision: member listings of a deactivated tenant stay readable (administrable)")
                .isEqualTo(1);
        put(
                orgAdmin,
                "/api/v1/tenants/" + tenantId + "/members/" + memberId + "/role",
                Map.of("role", "TENANT_TESTER"),
                200);
        assertThat(get(orgAdmin, "/api/v1/tenants/" + tenantId + "/members", 200)
                        .get("items")
                        .get(0)
                        .get("role")
                        .asText())
                .as("recorded decision: role changes on a deactivated tenant stay possible")
                .isEqualTo("TENANT_TESTER");
        UUID lateMember = userIdByEmail(insertOrglessUser("iso-deact-late@example.com"));
        assertThat(put(
                                "/api/v1/tenants/" + tenantId + "/members/" + lateMember + "/role",
                                orgAdmin,
                                Map.of("role", "TENANT_TESTER"))
                        .status)
                .as("deactivation still blocks new affiliations (issue #9 semantics, unchanged)")
                .isEqualTo(409);
    }

    // ---------------------------------------------------------------------
    // Matrix helpers
    // ---------------------------------------------------------------------

    private void expect(
            String token, String method, String uri, int expectedStatus, List<String> leaks, String otherTenantName) {
        Recording response = exchange(uri, token, method, "PUT".equals(method) ? Map.of() : null);
        if (response.status() != expectedStatus) {
            leaks.add(method + " " + uri + " by " + tokenSubject(token) + " returned " + response.status()
                    + " (expected " + expectedStatus + "): " + response.body());
        }
        String body = response.body() == null ? "" : response.body();
        if (body.contains(otherTenantName)) {
            leaks.add(method + " " + uri + " leaked the other tenant's name in its body: " + body);
        }
    }

    private String tokenSubject(String token) {
        return token.substring(0, 12);
    }

    private String withoutTraceId(String problemJson) throws Exception {
        JsonNode tree = mapper.readTree(problemJson);
        assertThat(tree.has("code")).as("denials use the problem+json envelope").isTrue();
        // trace_id and instance are per-request echoes (the caller's own
        // correlation id and URI); every concealment-bearing field — code,
        // type, title, detail, status — must match a genuine miss exactly.
        ((tools.jackson.databind.node.ObjectNode) tree).remove("trace_id");
        ((tools.jackson.databind.node.ObjectNode) tree).remove("instance");
        return mapper.writeValueAsString(tree);
    }

    // ---------------------------------------------------------------------
    // Seeding and HTTP helpers (same conventions as the lifecycle suite)
    // ---------------------------------------------------------------------

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

    /** Registers an orgless user through a plain invitation; returns the email. */
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
                        "Isolation User"))
                .retrieve()
                .body(String.class);
        return email;
    }

    /** Registers an orgless user through a plain invitation; returns an access token. */
    private String registerOrglessUser(String email) {
        insertOrglessUser(email);
        return accessToken(login(email, PASSWORD));
    }

    /** Creates an organization as dev admin and registers its named ORG_ADMIN. */
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
        assertThat(response.status()).as("POST %s", uri).isEqualTo(expectedStatus);
        return parse(response.body());
    }

    private JsonNode put(String bearer, String uri, Map<String, Object> body, int expectedStatus) {
        Recording response = exchange(uri, bearer, "PUT", body);
        assertThat(response.status()).as("PUT %s", uri).isEqualTo(expectedStatus);
        return parse(response.body());
    }

    private JsonNode get(String bearer, String uri, int expectedStatus) {
        Recording response = exchange(uri, bearer, "GET", null);
        assertThat(response.status()).as("GET %s", uri).isEqualTo(expectedStatus);
        return parse(response.body());
    }

    private Recording get(String uri, String bearer) {
        return exchange(uri, bearer, "GET", null);
    }

    private Recording put(String uri, String bearer, Map<String, Object> body) {
        return exchange(uri, bearer, "PUT", body);
    }

    private JsonNode parse(String body) {
        try {
            return body == null || body.isBlank() ? mapper.createObjectNode() : mapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
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
        return spec.exchange((request, response) -> new Recording(
                response.getStatusCode().value(),
                response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                new String(response.getBody().readAllBytes())));
    }

    private record Recording(int status, String contentType, String body) {}
}
