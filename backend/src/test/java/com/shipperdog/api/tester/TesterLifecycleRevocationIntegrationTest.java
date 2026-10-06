package com.shipperdog.api.tester;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.AuditCapture;
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
 * End-to-end acceptance flow for issue #29 (S6-04, FR-010/INV-007):
 * deactivation is the revocation posture. A DISABLED tester loses every
 * credential path (login, refresh with family kill, PAT), every ACTIVE
 * membership cascades to SUSPENDED (retained — nothing is deleted, the
 * audit evidence of what the tester had stays queryable), the reason is
 * mandatory and audited (FR-024), and reactivation is explicit and
 * restores nothing silently (each membership is re-enabled per tenant).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
@Import(AuditCapture.class)
class TesterLifecycleRevocationIntegrationTest {

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

    /**
     * AC5/FR-024: the reason is mandatory on deactivation, bounded, and
     * reaches the audit trail; the distinct TESTER_DEACTIVATED event
     * carries the transition, the reason, the cascade count, and the
     * session-kill marker.
     */
    @Test
    void deactivationRequiresAReasonAndAuditsIt() {
        String admin = loginOfOrgAdmin("t64-reason@example.com", "Revocation Reason Corp");
        UUID orgId = organizationIdByName("Revocation Reason Corp");
        UUID testerId = activeTester(admin, orgId, "reasonful@example.com", "Reasonful Tester");

        Recording missing = put("/api/v1/testers/" + testerId + "/status", admin, Map.of("status", "DISABLED"));
        assertThat(missing.status)
                .as("deactivation without a reason is refused")
                .isEqualTo(400);
        assertThat(missing.body.get("code").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(jdbc.queryForObject("SELECT status FROM testers WHERE id = ?", String.class, testerId))
                .isEqualTo("ACTIVE");

        Recording blank =
                put("/api/v1/testers/" + testerId + "/status", admin, Map.of("status", "DISABLED", "reason", "   "));
        assertThat(blank.status).as("a blank reason is no reason").isEqualTo(400);

        Recording tooLong = put(
                "/api/v1/testers/" + testerId + "/status",
                admin,
                Map.of("status", "DISABLED", "reason", "x".repeat(501)));
        assertThat(tooLong.status).as("the reason is capped at 500 characters").isEqualTo(400);

        JsonNode deactivated = put(
                admin,
                "/api/v1/testers/" + testerId + "/status",
                Map.of("status", "DISABLED", "reason", "departed the company"),
                200);
        assertThat(deactivated.get("status").asText()).isEqualTo("DISABLED");

        AuditEvent event = audit.eventsOfType(AuditEventType.TESTER_DEACTIVATED).getFirst();
        assertThat(event.detail())
                .containsEntry("from", "ACTIVE")
                .containsEntry("to", "DISABLED")
                .containsEntry("reason", "departed the company")
                .containsEntry("suspendedMemberships", "0")
                .containsEntry("refreshFamiliesRevoked", "true");
    }

    /**
     * AC1: a deactivated tester is dead at every credential surface —
     * login refused (uniform 401, audit reason tester_deactivated),
     * refresh refused AND the family killed (the revocation, not the
     * suspension, posture: presenting the dead refresh token again
     * trips reuse detection), PAT authentication dead.
     */
    @Test
    void deactivationCutsEveryCredentialPathAndKillsTheSessionFamily() {
        String admin = loginOfOrgAdmin("t64-kill@example.com", "Revocation Kill Corp");
        UUID orgId = organizationIdByName("Revocation Kill Corp");
        UUID testerId = activeTester(admin, orgId, "cutshort@example.com", "Cutshort Tester");

        // Credentials must exist BEFORE deactivation to prove it outlives them.
        String refreshToken = refreshOf("cutshort@example.com");
        String pat = patOf("cutshort@example.com");
        assertThat(get("/api/v1/auth/me", pat).status).isEqualTo(200);

        put(
                admin,
                "/api/v1/testers/" + testerId + "/status",
                Map.of("status", "DISABLED", "reason", "compromised credentials"),
                200);

        assertThat(post("/api/v1/auth/login", null, Map.of("email", "cutshort@example.com", "password", PASSWORD))
                        .status)
                .as("deactivated testers cannot log in")
                .isEqualTo(401);
        assertThat(audit.eventsOfType(AuditEventType.LOGIN_FAILURE).getFirst().detail())
                .containsEntry("reason", "tester_deactivated");

        assertThat(audit.eventsOfType(AuditEventType.TOKEN_REVOKED).getFirst().detail())
                .as("the deactivating transaction killed the refresh families proactively (SessionRevocation)")
                .containsEntry("reason", "tester_deactivated");
        assertThat(post("/api/v1/auth/refresh", null, Map.of("refreshToken", refreshToken)).status)
                .as("deactivation outlives issued refresh tokens: the family is dead")
                .isEqualTo(401);
        assertThat(audit.eventsOfType(AuditEventType.TOKEN_REUSE_DETECTED))
                .as("presenting the dead token trips reuse detection — killed, not parked")
                .isNotEmpty();

        assertThat(get("/api/v1/auth/me", pat).status)
                .as("deactivation kills personal access tokens at the next request")
                .isEqualTo(401);
    }

    /**
     * AC4 cascade + AC2 (INV-007): deactivation suspends every ACTIVE
     * membership across the organization's tenants, keeps already-REVOKED
     * history untouched, deletes nothing, and ends effective access at
     * every decision surface that exists today (the two-switches rule;
     * the S7-05 suite re-verifies the full authorization chain).
     */
    @Test
    void deactivationCascadesMembershipSuspensionAndDeletesNothing() {
        String admin = loginOfOrgAdmin("t64-cascade@example.com", "Revocation Cascade Corp");
        UUID orgId = organizationIdByName("Revocation Cascade Corp");
        UUID tenantOne = createTenant(admin, "Cascade One", "cascade-one-ext");
        UUID tenantTwo = createTenant(admin, "Cascade Two", "cascade-two-ext");
        UUID testerId = activeTester(admin, orgId, "widespan@example.com", "Widespan Tester");
        linkTesterToTenant(admin, tenantOne, testerId);
        linkTesterToTenant(admin, tenantTwo, testerId);

        // A third tenant's membership is already removed: REVOKED history
        // must ride through the cascade untouched.
        UUID tenantThree = createTenant(admin, "Cascade Three", "cascade-three-ext");
        linkTesterToTenant(admin, tenantThree, testerId);
        delete(admin, "/api/v1/tenants/" + tenantThree + "/testers/" + testerId);
        audit.reset();

        put(
                admin,
                "/api/v1/testers/" + testerId + "/status",
                Map.of("status", "DISABLED", "reason", "offboarded"),
                200);

        assertThat(jdbc.queryForList(
                        "SELECT status FROM tenant_tester_memberships WHERE tester_id = ?", String.class, testerId))
                .as("every retained membership row is present: two SUSPENDED, one REVOKED — no deletes (INV-007)")
                .containsExactlyInAnyOrder("SUSPENDED", "SUSPENDED", "REVOKED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM testers WHERE id = ?", Integer.class, testerId))
                .as("the person record itself is retained")
                .isEqualTo(1);

        JsonNode membership = get(admin, "/api/v1/tenants/" + tenantOne + "/testers/" + testerId, 200);
        assertThat(membership.get("testerStatus").asText()).isEqualTo("DISABLED");
        assertThat(membership.get("membershipStatus").asText()).isEqualTo("SUSPENDED");
        assertThat(membership.get("effectiveAccess").asBoolean())
                .as("the decision layer answers false on the very next evaluation")
                .isFalse();

        UUID uatId = groupIdByName(tenantOne, "UAT");
        assertThat(post("/api/v1/tester-groups/" + uatId + "/members", admin, Map.of("testerId", testerId.toString()))
                        .status)
                .as("a deactivated tester joins no group (the S6-02 precondition denies)")
                .isEqualTo(409);
        Recording relink =
                post("/api/v1/tenants/" + tenantTwo + "/testers", admin, Map.of("testerId", testerId.toString()));
        assertThat(relink.status)
                .as("a deactivated tester takes no new membership either")
                .isEqualTo(409);

        assertThat(audit.eventsOfType(AuditEventType.TENANT_TESTER_MEMBERSHIP_STATUS_CHANGED))
                .as("each cascaded membership flip is audited with the cascade marker and reason")
                .hasSize(2)
                .allSatisfy(event -> {
                    assertThat(event.detail())
                            .containsEntry("cascade", "tester_deactivated")
                            .containsEntry("reason", "offboarded")
                            .containsEntry("status.after", "SUSPENDED");
                });
        assertThat(audit.eventsOfType(AuditEventType.TESTER_DEACTIVATED)
                        .getFirst()
                        .detail())
                .containsEntry("suspendedMemberships", "2");
    }

    /**
     * AC6: reactivation is the explicit re-enable and restores NOTHING
     * silently — memberships stay SUSPENDED until each is re-enabled
     * per tenant; the tester's own login returns with the record.
     */
    @Test
    void reactivationIsExplicitAndRestoresNoMembershipSilently() {
        String admin = loginOfOrgAdmin("t64-reactivate@example.com", "Revocation Reactivate Corp");
        UUID orgId = organizationIdByName("Revocation Reactivate Corp");
        UUID tenantId = createTenant(admin, "Reactivateful", "reactivateful-ext");
        UUID testerId = activeTester(admin, orgId, "backagain@example.com", "Back Again Tester");
        linkTesterToTenant(admin, tenantId, testerId);

        // The suspension-vs-revocation distinction, first hand: a parked
        // tester's refresh family SURVIVES — the same token rotates again
        // after reactivation (deactivation above kills the family instead).
        String parkedRefresh = refreshOf("backagain@example.com");
        Recording oversizeReason = put(
                "/api/v1/testers/" + testerId + "/status",
                admin,
                Map.of("status", "SUSPENDED", "reason", "y".repeat(501)));
        assertThat(oversizeReason.status)
                .as("the optional reason is capped at 500 characters on every transition")
                .isEqualTo(400);
        put(
                admin,
                "/api/v1/testers/" + testerId + "/status",
                Map.of("status", "SUSPENDED", "reason", "away on leave"),
                200);
        assertThat(audit.eventsOfType(AuditEventType.TESTER_STATUS_CHANGED)
                        .getFirst()
                        .detail())
                .as("the optional reason is recorded on suspension too")
                .containsEntry("from", "ACTIVE")
                .containsEntry("to", "SUSPENDED")
                .containsEntry("reason", "away on leave");
        assertThat(post("/api/v1/auth/refresh", null, Map.of("refreshToken", parkedRefresh)).status)
                .as("suspension still refuses refresh (the S6-01 rule)")
                .isEqualTo(401);
        assertThat(audit.eventsOfType(AuditEventType.TOKEN_REFRESHED).getFirst().detail())
                .containsEntry("reason", "tester_suspended");
        put(admin, "/api/v1/testers/" + testerId + "/status", Map.of("status", "ACTIVE"), 200);
        assertThat(post("/api/v1/auth/refresh", null, Map.of("refreshToken", parkedRefresh)).status)
                .as("suspension parks the family; the pre-suspension token rotates again")
                .isEqualTo(200);

        // The emergency also works from the parked state.
        put(admin, "/api/v1/testers/" + testerId + "/status", Map.of("status", "SUSPENDED"), 200);
        put(
                admin,
                "/api/v1/testers/" + testerId + "/status",
                Map.of("status", "DISABLED", "reason", "security hold"),
                200);

        Recording reDeactivation = put(
                "/api/v1/testers/" + testerId + "/status",
                admin,
                Map.of("status", "DISABLED", "reason", "re-issued after review"));
        assertThat(reDeactivation.status)
                .as("re-deactivating a DISABLED tester is the idempotent emergency re-issue")
                .isEqualTo(200);

        Recording sneaky = put("/api/v1/testers/" + testerId + "/status", admin, Map.of("status", "SUSPENDED"));
        assertThat(sneaky.status)
                .as("DISABLED -> SUSPENDED is not a transition")
                .isEqualTo(409);

        JsonNode reactivated = put(admin, "/api/v1/testers/" + testerId + "/status", Map.of("status", "ACTIVE"), 200);
        assertThat(reactivated.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(audit.eventsOfType(AuditEventType.TESTER_REACTIVATED)
                        .getFirst()
                        .detail())
                .containsEntry("from", "DISABLED")
                .containsEntry("to", "ACTIVE");

        assertThat(login("backagain@example.com", PASSWORD)).isNotNull();

        JsonNode membership = get(admin, "/api/v1/tenants/" + tenantId + "/testers/" + testerId, 200);
        assertThat(membership.get("membershipStatus").asText())
                .as("no silent restore: the cascaded membership stays SUSPENDED (manual re-enable default)")
                .isEqualTo("SUSPENDED");
        assertThat(membership.get("effectiveAccess").asBoolean()).isFalse();

        // The explicit re-grant: a per-tenant re-enable restores effectiveness.
        put(admin, "/api/v1/tenants/" + tenantId + "/testers/" + testerId + "/status", Map.of("status", "ACTIVE"), 200);
        assertThat(get(admin, "/api/v1/tenants/" + tenantId + "/testers/" + testerId, 200)
                        .get("effectiveAccess")
                        .asBoolean())
                .isTrue();
    }

    /**
     * Deactivation also covers the pre-account record: an INVITED tester
     * can be deactivated to kill the invitation funnel, and a record
     * that never accepted (no linked account) cannot be reactivated —
     * the audit history stays, onboarding starts over with a new record.
     */
    @Test
    void invitedTesterCanBeDeactivatedButNeverReactivated() {
        String admin = loginOfOrgAdmin("t64-invited@example.com", "Revocation Invited Corp");
        UUID orgId = organizationIdByName("Revocation Invited Corp");
        JsonNode invited = inviteTester(admin, orgId, "neverwas@example.com", "Neverwas Tester");
        UUID testerId = UUID.fromString(invited.get("id").asText());

        put(
                admin,
                "/api/v1/testers/" + testerId + "/status",
                Map.of("status", "DISABLED", "reason", "invitation withdrawn"),
                200);

        Recording accept = post(
                "/api/v1/auth/register",
                null,
                Map.of(
                        "invitationToken",
                        invited.get("invitation").get("invitationToken").asText(),
                        "email",
                        "neverwas@example.com",
                        "password",
                        PASSWORD,
                        "fullName",
                        "Neverwas Tester"));
        assertThat(accept.status)
                .as("the live invitation token died with the deactivation (acceptance requires INVITED)")
                .isEqualTo(400);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM users WHERE email = ?", Integer.class, "neverwas@example.com"))
                .as("no account was created")
                .isEqualTo(0);

        Recording reactivate = put("/api/v1/testers/" + testerId + "/status", admin, Map.of("status", "ACTIVE"));
        assertThat(reactivate.status)
                .as("a record with no linked account has nothing to re-enable")
                .isEqualTo(409);
        assertThat(reactivate.body.get("code").asText()).isEqualTo("TESTER_INVALID_TRANSITION");
        Recording reinvite = post("/api/v1/testers/" + testerId + "/invitations", admin, Map.of());
        assertThat(reinvite.status)
                .as("a deactivated record is not re-invitable")
                .isEqualTo(409);
    }

    // ---------------------------------------------------------------------
    // Helpers (the S6-01/S6-03 suite shapes)
    // ---------------------------------------------------------------------

    private String loginOfOrgAdmin(String email, String orgName) {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        UUID orgId = createOrganization(devToken, orgName);
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

    private UUID createOrganization(String bearer, String name) {
        JsonNode created = post(bearer, "/api/v1/organizations", Map.of("name", name), 201);
        return UUID.fromString(created.get("id").asText());
    }

    private UUID createTenant(String bearer, String name, String externalId) {
        JsonNode created = post(
                bearer,
                "/api/v1/tenants",
                Map.of("organizationId", organizationIdOf(bearer).toString(), "name", name, "externalId", externalId),
                201);
        return UUID.fromString(created.get("id").asText());
    }

    /** The caller's own organization (their first membership's org). */
    private UUID organizationIdOf(String bearer) {
        return UUID.fromString(get(bearer, "/api/v1/organizations", 200)
                .get("items")
                .get(0)
                .get("id")
                .asText());
    }

    private UUID organizationIdByName(String name) {
        return jdbc.queryForObject("SELECT id FROM organizations WHERE name = ?", UUID.class, name);
    }

    private JsonNode inviteTester(String admin, UUID orgId, String email, String fullName) {
        return post(
                admin,
                "/api/v1/testers",
                Map.of("organizationId", orgId.toString(), "email", email, "fullName", fullName),
                201);
    }

    /** An ACTIVE tester person record: invited and accepted. */
    private UUID activeTester(String admin, UUID orgId, String email, String fullName) {
        JsonNode invited = inviteTester(admin, orgId, email, fullName);
        client.post()
                .uri("/api/v1/auth/register")
                .body(Map.of(
                        "invitationToken",
                        invited.get("invitation").get("invitationToken").asText(),
                        "email",
                        email,
                        "password",
                        PASSWORD,
                        "fullName",
                        fullName))
                .retrieve()
                .body(String.class);
        return UUID.fromString(invited.get("id").asText());
    }

    private void linkTesterToTenant(String bearer, UUID tenantId, UUID testerId) {
        post(bearer, "/api/v1/tenants/" + tenantId + "/testers", Map.of("testerId", testerId.toString()), 201);
    }

    private UUID groupIdByName(UUID tenantId, String name) {
        return jdbc.queryForObject(
                "SELECT id FROM tester_groups WHERE tenant_id = ? AND name = ?", UUID.class, tenantId, name);
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

    private String refreshOf(String email) {
        try {
            return mapper.readTree(login(email, PASSWORD)).get("refreshToken").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String patOf(String email) {
        String accessToken = accessToken(login(email, PASSWORD));
        JsonNode pat = post(accessToken, "/api/v1/auth/tokens", Map.of("name", "s6-04"), 201);
        return pat.get("token").asText();
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

    private Recording post(String uri, String bearer, Map<String, Object> body) {
        return exchange(uri, bearer, "POST", body);
    }

    private Recording put(String uri, String bearer, Map<String, Object> body) {
        return exchange(uri, bearer, "PUT", body);
    }

    private Recording get(String uri, String bearer) {
        return exchange(uri, bearer, "GET", null);
    }

    private JsonNode get(String bearer, String uri, int expectedStatus) {
        Recording response = exchange(uri, bearer, "GET", null);
        assertThat(response.status).as("GET %s", uri).isEqualTo(expectedStatus);
        return response.body;
    }

    private Recording delete(String bearer, String uri) {
        return exchange(uri, bearer, "DELETE", null);
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
