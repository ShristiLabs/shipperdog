package com.shipperdog.api.tester;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.AuditCapture;
import java.util.ArrayList;
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
 * End-to-end acceptance flow for issue #28 (S6-03, PRD 8.8/13): the
 * tenant-tester membership join — linking ACTIVE testers (membership
 * status tracked separately from tester status), the two-switches
 * effective-access rule in all four combinations, immediate exclusion
 * on suspension (live query, no cache), history-preserving removal,
 * cross-tenant concealment for foreign administrators, both listings,
 * audited mutations, the concurrent-link race, and Idempotency-Key
 * replay.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
@Import(AuditCapture.class)
class TenantTesterMembershipsIntegrationTest {

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
     * AC1 + AC2: only an ACTIVE tester links; the membership status is
     * tracked separately from the tester status; effective access needs
     * both switches on — all four combinations exercised.
     */
    @Test
    void effectiveAccessRequiresBothSwitchesOn() {
        String admin = loginOfOrgAdmin("tt-four@example.com", "TT Four Corp");
        UUID orgId = organizationIdByName("TT Four Corp");
        UUID tenantId = createTenant(admin, "Fourful", "fourful-external-1");
        JsonNode invited = inviteTester(admin, orgId, "four@example.com", "Four Tester");
        UUID testerId = UUID.fromString(invited.get("id").asText());

        Recording invitedLink =
                post("/api/v1/tenants/" + tenantId + "/testers", admin, Map.of("testerId", testerId.toString()));
        assertThat(invitedLink.status)
                .as("an INVITED tester cannot be linked yet (AC1: links an ACTIVE tester)")
                .isEqualTo(409);
        assertThat(invitedLink.body.get("code").asText()).isEqualTo("TESTER_NOT_ACTIVE");

        acceptInvitation(invited, "four@example.com", "Four Tester");
        JsonNode linked =
                post(admin, "/api/v1/tenants/" + tenantId + "/testers", Map.of("testerId", testerId.toString()), 201);
        assertThat(linked.get("linked").asBoolean()).isTrue();
        assertThat(linked.get("membershipStatus").asText()).isEqualTo("ACTIVE");
        assertThat(linked.get("effectiveAccess").asBoolean()).isTrue();

        // Combination 1: tester ACTIVE x membership ACTIVE -> effective.
        JsonNode bothOn = get(admin, "/api/v1/tenants/" + tenantId + "/testers/" + testerId, 200);
        assertThat(bothOn.get("testerStatus").asText()).isEqualTo("ACTIVE");
        assertThat(bothOn.get("membershipStatus").asText()).isEqualTo("ACTIVE");
        assertThat(bothOn.get("effectiveAccess").asBoolean())
                .as("AC2: tester ACTIVE and membership ACTIVE is the only effective combination")
                .isTrue();

        // Combination 2: tester ACTIVE x membership SUSPENDED -> not effective.
        put(
                admin,
                "/api/v1/tenants/" + tenantId + "/testers/" + testerId + "/status",
                Map.of("status", "SUSPENDED"),
                200);
        JsonNode membershipSuspended = get(admin, "/api/v1/tenants/" + tenantId + "/testers/" + testerId, 200);
        assertThat(membershipSuspended.get("testerStatus").asText()).isEqualTo("ACTIVE");
        assertThat(membershipSuspended.get("membershipStatus").asText()).isEqualTo("SUSPENDED");
        assertThat(membershipSuspended.get("effectiveAccess").asBoolean()).isFalse();

        // Combination 3: tester SUSPENDED x membership ACTIVE -> not effective.
        put(admin, "/api/v1/testers/" + testerId + "/status", Map.of("status", "SUSPENDED"), 200);
        put(admin, "/api/v1/tenants/" + tenantId + "/testers/" + testerId + "/status", Map.of("status", "ACTIVE"), 200);
        JsonNode testerSuspended = get(admin, "/api/v1/tenants/" + tenantId + "/testers/" + testerId, 200);
        assertThat(testerSuspended.get("testerStatus").asText()).isEqualTo("SUSPENDED");
        assertThat(testerSuspended.get("membershipStatus").asText()).isEqualTo("ACTIVE");
        assertThat(testerSuspended.get("effectiveAccess").asBoolean()).isFalse();
        assertThat(testerSuspended.get("membershipStatus").asText())
                .as("AC1: the membership switch is tracked separately and survives the tester switch")
                .isNotEqualTo(testerSuspended.get("testerStatus").asText());

        // Combination 4: tester SUSPENDED x membership SUSPENDED -> not effective.
        put(
                admin,
                "/api/v1/tenants/" + tenantId + "/testers/" + testerId + "/status",
                Map.of("status", "SUSPENDED"),
                200);
        JsonNode bothOff = get(admin, "/api/v1/tenants/" + tenantId + "/testers/" + testerId, 200);
        assertThat(bothOff.get("effectiveAccess").asBoolean()).isFalse();

        // Recovery restores effectiveness (both switches are reversible).
        put(admin, "/api/v1/testers/" + testerId + "/status", Map.of("status", "ACTIVE"), 200);
        put(admin, "/api/v1/tenants/" + tenantId + "/testers/" + testerId + "/status", Map.of("status", "ACTIVE"), 200);
        assertThat(get(admin, "/api/v1/tenants/" + tenantId + "/testers/" + testerId, 200)
                        .get("effectiveAccess")
                        .asBoolean())
                .isTrue();

        List<AuditEvent> switches = audit.eventsOfType(AuditEventType.TENANT_TESTER_MEMBERSHIP_STATUS_CHANGED);
        assertThat(switches)
                .as(
                        "every membership switch is audited with before/after (four switches; the tester-status changes audit separately)")
                .hasSize(4);
        assertThat(switches.getFirst().detail())
                .containsEntry("status.before", "ACTIVE")
                .containsEntry("status.after", "SUSPENDED")
                .containsEntry("testerId", testerId.toString());
    }

    /**
     * AC3: suspending a membership excludes the tester from the
     * DEFAULT tenant-scoped visibility on the next request (live query,
     * no cache), while the administrative views keep the row; group
     * membership — the enforced precondition — stops accepting the
     * tester.
     */
    @Test
    void suspensionExcludesFromTenantScopedVisibilityImmediately() {
        String admin = loginOfOrgAdmin("tt-suspend@example.com", "TT Suspend Corp");
        UUID orgId = organizationIdByName("TT Suspend Corp");
        UUID tenantId = createTenant(admin, "Suspendful", "suspendful-external-1");
        UUID testerId = activeTester(admin, orgId, "suspend@example.com", "Suspend Tester");
        linkTesterToTenant(admin, tenantId, testerId);
        UUID pilotId = groupIdByName(tenantId, "Pilot");
        post(admin, "/api/v1/tester-groups/" + pilotId + "/members", Map.of("testerId", testerId.toString()), 201);

        assertThat(get(admin, "/api/v1/tenants/" + tenantId + "/testers", 200).get("items"))
                .as("the effective view lists the linked tester before suspension")
                .hasSize(1);

        put(
                admin,
                "/api/v1/tenants/" + tenantId + "/testers/" + testerId + "/status",
                Map.of("status", "SUSPENDED"),
                200);

        JsonNode effective = get(admin, "/api/v1/tenants/" + tenantId + "/testers", 200);
        assertThat(effective.get("items"))
                .as("AC3: the NEXT request's default view already excludes the tester (no cache TTL)")
                .isEmpty();
        assertThat(get(admin, "/api/v1/tenants/" + tenantId + "/testers?status=SUSPENDED", 200)
                        .get("items"))
                .as("the administrative view keeps the suspended row")
                .hasSize(1);
        assertThat(get(admin, "/api/v1/tenants/" + tenantId + "/testers?status=ALL", 200)
                        .get("items"))
                .hasSize(1);

        UUID uatId = groupIdByName(tenantId, "UAT");
        Recording groupAdd =
                post("/api/v1/tester-groups/" + uatId + "/members", admin, Map.of("testerId", testerId.toString()));
        assertThat(groupAdd.status)
                .as("the S6-02 narrowing enforces the precondition: a suspended member joins no new group")
                .isEqualTo(409);
        assertThat(groupAdd.body.get("code").asText()).isEqualTo("TESTER_NOT_TENANT_MEMBER");

        put(admin, "/api/v1/tenants/" + tenantId + "/testers/" + testerId + "/status", Map.of("status", "ACTIVE"), 200);
        assertThat(get(admin, "/api/v1/tenants/" + tenantId + "/testers", 200).get("items"))
                .as("reactivation restores visibility on the next request")
                .hasSize(1);
    }

    /**
     * AC4: removal retains the row REVOKED — history stays visible in
     * the read-only queries (both listings), the audit carries the
     * removal, and a re-link reactivates the retained row instead of
     * writing a second one.
     */
    @Test
    void removalPreservesHistoryAndRelinkReactivates() {
        String admin = loginOfOrgAdmin("tt-remove@example.com", "TT Remove Corp");
        UUID orgId = organizationIdByName("TT Remove Corp");
        UUID tenantId = createTenant(admin, "Removeful", "removeful-external-1");
        UUID testerId = activeTester(admin, orgId, "remove@example.com", "Remove Tester");
        linkTesterToTenant(admin, tenantId, testerId);

        assertThat(delete(admin, "/api/v1/tenants/" + tenantId + "/testers/" + testerId).status)
                .isEqualTo(204);
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM tenant_tester_memberships WHERE tenant_id = ? AND tester_id = ?",
                        String.class,
                        tenantId,
                        testerId))
                .as("the row is retained REVOKED, never deleted (AC4)")
                .isEqualTo("REVOKED");
        AuditEvent removed = audit.eventsOfType(AuditEventType.TENANT_TESTER_MEMBERSHIP_REMOVED)
                .getFirst();
        assertThat(removed.detail()).containsEntry("status.before", "ACTIVE").containsEntry("status.after", "REVOKED");

        assertThat(get(admin, "/api/v1/tenants/" + tenantId + "/testers", 200).get("items"))
                .as("a removed membership is not effective visibility")
                .isEmpty();
        JsonNode revoked = get(admin, "/api/v1/tenants/" + tenantId + "/testers?status=REVOKED", 200);
        assertThat(revoked.get("items"))
                .as("the history stays visible in the read-only query (AC4)")
                .hasSize(1);
        assertThat(revoked.get("items").get(0).get("testerId").asText()).isEqualTo(testerId.toString());
        assertThat(revoked.get("items").get(0).get("effectiveAccess").asBoolean())
                .isFalse();
        JsonNode tenantsOfTester = get(admin, "/api/v1/testers/" + testerId + "/tenants", 200);
        assertThat(tenantsOfTester.get("items"))
                .as("the tenants-of-tester read-only query keeps the historical row too")
                .hasSize(1);
        assertThat(tenantsOfTester.get("items").get(0).get("membershipStatus").asText())
                .isEqualTo("REVOKED");

        assertThat(delete(admin, "/api/v1/tenants/" + tenantId + "/testers/" + testerId).status)
                .as("removal is idempotent")
                .isEqualTo(204);

        JsonNode relinked =
                post(admin, "/api/v1/tenants/" + tenantId + "/testers", Map.of("testerId", testerId.toString()), 200);
        assertThat(relinked.get("linked").asBoolean()).isFalse();
        assertThat(relinked.get("reactivated").asBoolean()).isTrue();
        assertThat(relinked.get("membershipStatus").asText()).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM tenant_tester_memberships WHERE tenant_id = ? AND tester_id = ?",
                        Integer.class,
                        tenantId,
                        testerId))
                .as("the re-link reactivates the retained row, never a second one")
                .isEqualTo(1);
        AuditEvent relinkAudit = audit.eventsOfType(AuditEventType.TENANT_TESTER_MEMBERSHIP_CREATED)
                .getLast();
        assertThat(relinkAudit.detail()).containsEntry("reactivated", "true");

        delete(admin, "/api/v1/tenants/" + tenantId + "/testers/" + testerId);
        Recording revokedSwitch = put(
                "/api/v1/tenants/" + tenantId + "/testers/" + testerId + "/status", admin, Map.of("status", "ACTIVE"));
        assertThat(revokedSwitch.status)
                .as("a removed membership is not administrable — reactivation is a re-link")
                .isEqualTo(409);
        assertThat(revokedSwitch.body.get("code").asText()).isEqualTo("TESTER_INVALID_TRANSITION");

        Recording absentMiss = get("/api/v1/tenants/" + tenantId + "/testers/" + UUID.randomUUID(), admin);
        assertThat(absentMiss.status).isEqualTo(404);
        assertThat(absentMiss.body.get("code").asText()).isEqualTo("TENANT_TESTER_MEMBERSHIP_NOT_FOUND");
    }

    /**
     * AC5 (matrix): cross-tenant administrators, foreign-organization
     * administrators, and role-holders without the permission cannot
     * manipulate another tenant's memberships — every mutation and
     * read renders the concealed 404 a genuine miss produces.
     */
    @Test
    void crossTenantActorsCannotManipulateForeignMemberships() {
        String admin = loginOfOrgAdmin("tt-matrix@example.com", "TT Matrix Corp");
        UUID orgId = organizationIdByName("TT Matrix Corp");
        UUID tenantA = createTenant(admin, "Matrix A", "matrix-a-external-1");
        UUID tenantB = createTenant(admin, "Matrix B", "matrix-b-external-1");
        UUID testerId = activeTester(admin, orgId, "matrix@example.com", "Matrix Tester");
        linkTesterToTenant(admin, tenantA, testerId);

        // A TENANT_ADMIN of tenant A (via the console-membership join).
        String tenantAdminEmail = "matrix.admin@example.com";
        String tenantAdmin = inviteOrgMember(admin, orgId, tenantAdminEmail, "ORG_MEMBER");
        post(
                admin,
                "/api/v1/tenants/" + tenantA + "/members",
                Map.of("userId", userIdByEmail(tenantAdminEmail), "role", "TENANT_ADMIN"),
                201);
        // A tenant TESTER of tenant A: a role-holding member without the
        // new permissions.
        String tenantTesterEmail = "matrix.tester@example.com";
        String tenantTester = inviteOrgMember(admin, orgId, tenantTesterEmail, "ORG_MEMBER");
        post(
                admin,
                "/api/v1/tenants/" + tenantA + "/members",
                Map.of("userId", userIdByEmail(tenantTesterEmail), "role", "TENANT_TESTER"),
                201);
        // An administrator of a DIFFERENT organization.
        String stranger = loginOfOrgAdmin("tt-stranger@example.com", "TT Stranger Corp");

        post(admin, "/api/v1/tenants/" + tenantB + "/testers", Map.of("testerId", testerId.toString()), 201);
        for (String actor : List.of(tenantAdmin, tenantTester, stranger)) {
            assertThat(post("/api/v1/tenants/" + tenantB + "/testers", actor, Map.of("testerId", testerId.toString()))
                            .status)
                    .as("a cross-tenant actor cannot link into tenant B")
                    .isEqualTo(404);
            assertThat(put(
                                    "/api/v1/tenants/" + tenantB + "/testers/" + testerId + "/status",
                                    actor,
                                    Map.of("status", "SUSPENDED"))
                            .status)
                    .as("a cross-tenant actor cannot suspend tenant B's membership")
                    .isEqualTo(404);
            assertThat(delete(actor, "/api/v1/tenants/" + tenantB + "/testers/" + testerId).status)
                    .as("a cross-tenant actor cannot remove tenant B's membership")
                    .isEqualTo(404);
            assertThat(get("/api/v1/tenants/" + tenantB + "/testers", actor).status)
                    .as("a cross-tenant actor cannot even list tenant B's testers")
                    .isEqualTo(404);
            assertThat(get("/api/v1/tenants/" + tenantB + "/testers/" + testerId, actor).status)
                    .isEqualTo(404);
        }
        assertThat(get("/api/v1/testers/" + testerId + "/tenants", stranger).status)
                .as("the tenants-of-tester read conceals a foreign organization's tester")
                .isEqualTo(404);

        // The tenant's OWN administrator administrates normally (the
        // matrix's positive control), and the org overlay reaches both.
        JsonNode suspended = put(
                admin,
                "/api/v1/tenants/" + tenantA + "/testers/" + testerId + "/status",
                Map.of("status", "SUSPENDED"),
                200);
        assertThat(suspended.get("membershipStatus").asText()).isEqualTo("SUSPENDED");
        assertThat(get(admin, "/api/v1/tenants/" + tenantB + "/testers", 200).get("items"))
                .hasSize(1);
        assertThat(audit.eventsOfType(AuditEventType.ACCESS_DENIED)).isNotEmpty();
    }

    /** The issue #9 affiliation rule: deactivated tenants take no new links; existing stay administrable. */
    @Test
    void deactivatedTenantsTakeNoNewLinksButStayAdministrable() {
        String admin = loginOfOrgAdmin("tt-deact@example.com", "TT Deact Corp");
        UUID orgId = organizationIdByName("TT Deact Corp");
        UUID tenantId = createTenant(admin, "Deactful Two", "tt-deactful-external-1");
        UUID testerId = activeTester(admin, orgId, "tt-early@example.com", "TT Early Tester");
        linkTesterToTenant(admin, tenantId, testerId);
        UUID lateTesterId = activeTester(admin, orgId, "tt-late@example.com", "TT Late Tester");
        put(admin, "/api/v1/tenants/" + tenantId, Map.of("status", "DEACTIVATED"), 200);

        Recording lateLink =
                post("/api/v1/tenants/" + tenantId + "/testers", admin, Map.of("testerId", lateTesterId.toString()));
        assertThat(lateLink.status).isEqualTo(409);
        assertThat(lateLink.body.get("code").asText()).isEqualTo("TENANT_DEACTIVATED");

        JsonNode suspendedSwitch = put(
                admin,
                "/api/v1/tenants/" + tenantId + "/testers/" + testerId + "/status",
                Map.of("status", "SUSPENDED"),
                200);
        assertThat(suspendedSwitch.get("membershipStatus").asText())
                .as("the switch continues on a deactivated tenant (administrable, not new)")
                .isEqualTo("SUSPENDED");
        assertThat(delete(admin, "/api/v1/tenants/" + tenantId + "/testers/" + testerId).status)
                .isEqualTo(204);
        assertThat(get(admin, "/api/v1/tenants/" + tenantId + "/testers?status=ALL", 200)
                        .get("items"))
                .isNotEmpty();
    }

    /** Validation, unknowns, idempotency, the tenants-of-tester shape, and cursor pagination. */
    @Test
    void listingsConcealUnknownsAndPageWithTheCursor() {
        String admin = loginOfOrgAdmin("tt-list@example.com", "TT List Corp");
        UUID orgId = organizationIdByName("TT List Corp");
        UUID tenantOne = createTenant(admin, "List One", "list-one-external-1");
        UUID tenantTwo = createTenant(admin, "List Two", "list-two-external-1");
        List<UUID> testers = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            UUID testerId = activeTester(admin, orgId, "list" + i + "@example.com", "List Tester " + i);
            linkTesterToTenant(admin, tenantOne, testerId);
            testers.add(testerId);
        }
        UUID crossTester = testers.get(1);
        linkTesterToTenant(admin, tenantTwo, crossTester);

        assertThat(post("/api/v1/tenants/" + tenantOne + "/testers", admin, Map.of()).status)
                .isEqualTo(400);
        Recording unknownTester = post(
                "/api/v1/tenants/" + tenantOne + "/testers",
                admin,
                Map.of("testerId", UUID.randomUUID().toString()));
        assertThat(unknownTester.status)
                .as("an unknown tester is a concealed miss")
                .isEqualTo(404);
        String otherAdmin = loginOfOrgAdmin("tt-other@example.com", "TT Other Corp");
        JsonNode foreignInvitation =
                inviteTester(otherAdmin, organizationIdByName("TT Other Corp"), "tt.far@example.com", "Far Tester");
        UUID foreignTester = UUID.fromString(foreignInvitation.get("id").asText());
        acceptInvitation(foreignInvitation, "tt.far@example.com", "Far Tester");
        assertThat(post(
                                "/api/v1/tenants/" + tenantOne + "/testers",
                                admin,
                                Map.of("testerId", foreignTester.toString()))
                        .status)
                .as("a foreign organization's tester is indistinguishable from a missing one")
                .isEqualTo(404);
        assertThat(get("/api/v1/tenants/" + UUID.randomUUID() + "/testers", admin).status)
                .as("an unknown tenant's listing is concealed for the platform administrator too")
                .isEqualTo(404);

        JsonNode firstPage = get(admin, "/api/v1/tenants/" + tenantOne + "/testers?limit=2", 200);
        assertThat(firstPage.get("items")).hasSize(2);
        assertThat(firstPage.get("next_cursor")).isNotNull();
        JsonNode secondPage = get(
                admin,
                "/api/v1/tenants/" + tenantOne + "/testers?limit=2&cursor="
                        + firstPage.get("next_cursor").asText(),
                200);
        assertThat(secondPage.get("items")).hasSize(1);
        assertThat(secondPage.get("next_cursor")).isNull();
        assertThat(get("/api/v1/tenants/" + tenantOne + "/testers?status=NOPE", admin).status)
                .isEqualTo(400);

        // Pagination exactness on the EFFECTIVE view: with a
        // non-effective row present (the OLDEST link suspended), pages
        // stay full-size and boundaries follow the effective rows — the
        // filter runs inside the query, not as an in-memory post-filter
        // that would silently shrink pages and shift boundaries.
        UUID fourth = activeTester(admin, orgId, "list4@example.com", "List Tester 4");
        linkTesterToTenant(admin, tenantOne, fourth);
        put(
                admin,
                "/api/v1/tenants/" + tenantOne + "/testers/" + testers.get(0) + "/status",
                Map.of("status", "SUSPENDED"),
                200);
        JsonNode effectivePage = get(admin, "/api/v1/tenants/" + tenantOne + "/testers?limit=2", 200);
        assertThat(effectivePage.get("items"))
                .as("a full first page of effective rows despite the suspended oldest row")
                .hasSize(2);
        JsonNode effectiveTail = get(
                admin,
                "/api/v1/tenants/" + tenantOne + "/testers?limit=2&cursor="
                        + effectivePage.get("next_cursor").asText(),
                200);
        assertThat(effectiveTail.get("items")).hasSize(1);
        assertThat(effectiveTail.get("next_cursor")).isNull();
        assertThat(get(admin, "/api/v1/tenants/" + tenantOne + "/testers?status=SUSPENDED", 200)
                        .get("items"))
                .as("the suspended row stays administratively visible")
                .hasSize(1);

        JsonNode tenantsOfTester = get(admin, "/api/v1/testers/" + crossTester + "/tenants", 200);
        assertThat(tenantsOfTester.get("items"))
                .as("the tester's tenants span their organization only, link-ordered")
                .hasSize(2);
        assertThat(tenantsOfTester.get("items").get(0).get("tenant").get("name").asText())
                .isEqualTo("List One");
        assertThat(tenantsOfTester.get("items").get(1).get("tenant").get("name").asText())
                .isEqualTo("List Two");
        assertThat(tenantsOfTester.get("items").get(0).get("effectiveAccess").asBoolean())
                .isTrue();
        assertThat(get("/api/v1/testers/" + UUID.randomUUID() + "/tenants", admin).status)
                .isEqualTo(404);

        String key = UUID.randomUUID().toString();
        Recording keyed = exchange(
                "/api/v1/tenants/" + tenantTwo + "/testers",
                admin,
                "POST",
                Map.of("testerId", testers.get(0).toString()),
                key);
        assertThat(keyed.status).isEqualTo(201);
        Recording keyedReplay = exchange(
                "/api/v1/tenants/" + tenantTwo + "/testers",
                admin,
                "POST",
                Map.of("testerId", testers.get(0).toString()),
                key);
        assertThat(keyedReplay.status).isEqualTo(201);
        assertThat(keyedReplay.replayed)
                .as("Idempotency-Key replays the stored outcome")
                .isTrue();
    }

    /**
     * The S6-02 narrowing enforced by this story: a tester joins a
     * tenant's groups only while EFFECTIVELY active on that tenant. The
     * org-visible-but-unlinked tester answers the explicit conflict
     * (not the concealment 404), each way a switch can turn
     * effectiveness off is honored at the group gate, and a re-link
     * restores eligibility.
     */
    @Test
    void groupMembershipRequiresEffectiveTenantMembership() {
        String admin = loginOfOrgAdmin("tt-precond@example.com", "TT Precond Corp");
        UUID orgId = organizationIdByName("TT Precond Corp");
        UUID tenantId = createTenant(admin, "Precondful", "tt-precond-external-1");
        UUID testerId = activeTester(admin, orgId, "precond@example.com", "Precond Tester");
        UUID pilotId = groupIdByName(tenantId, "Pilot");
        UUID uatId = groupIdByName(tenantId, "UAT");

        Recording unlinked =
                post("/api/v1/tester-groups/" + pilotId + "/members", admin, Map.of("testerId", testerId.toString()));
        assertThat(unlinked.status)
                .as("an org-visible tester without a tenant membership answers the explicit conflict, not a 404")
                .isEqualTo(409);
        assertThat(unlinked.body.get("code").asText()).isEqualTo("TESTER_NOT_TENANT_MEMBER");

        linkTesterToTenant(admin, tenantId, testerId);
        post(admin, "/api/v1/tester-groups/" + pilotId + "/members", Map.of("testerId", testerId.toString()), 201);

        put(
                admin,
                "/api/v1/tenants/" + tenantId + "/testers/" + testerId + "/status",
                Map.of("status", "SUSPENDED"),
                200);
        assertThat(post("/api/v1/tester-groups/" + uatId + "/members", admin, Map.of("testerId", testerId.toString()))
                        .status)
                .as("a suspended membership is not effective at the group gate")
                .isEqualTo(409);
        put(admin, "/api/v1/tenants/" + tenantId + "/testers/" + testerId + "/status", Map.of("status", "ACTIVE"), 200);

        put(admin, "/api/v1/testers/" + testerId + "/status", Map.of("status", "SUSPENDED"), 200);
        assertThat(post("/api/v1/tester-groups/" + uatId + "/members", admin, Map.of("testerId", testerId.toString()))
                        .status)
                .as("a suspended person record under an ACTIVE membership is not effective either")
                .isEqualTo(409);
        put(admin, "/api/v1/testers/" + testerId + "/status", Map.of("status", "ACTIVE"), 200);

        delete(admin, "/api/v1/tenants/" + tenantId + "/testers/" + testerId);
        assertThat(post("/api/v1/tester-groups/" + uatId + "/members", admin, Map.of("testerId", testerId.toString()))
                        .status)
                .as("a removed membership ends the precondition")
                .isEqualTo(409);

        Recording relink =
                post("/api/v1/tenants/" + tenantId + "/testers", admin, Map.of("testerId", testerId.toString()));
        assertThat(relink.status)
                .as("the re-link reactivates the retained row (idempotent 200)")
                .isEqualTo(200);
        post(admin, "/api/v1/tester-groups/" + uatId + "/members", Map.of("testerId", testerId.toString()), 201);
    }

    /** The concurrency core: simultaneous links of one pair collapse to one ACTIVE row. */
    @Test
    void concurrentLinksOfOnePairYieldOneRow() throws Exception {
        String admin = loginOfOrgAdmin("tt-race@example.com", "TT Race Corp");
        UUID orgId = organizationIdByName("TT Race Corp");
        UUID tenantId = createTenant(admin, "TT Raceful", "tt-raceful-external-1");
        UUID testerId = activeTester(admin, orgId, "tt-racing@example.com", "TT Racing Tester");

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Recording>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return exchange(
                            "/api/v1/tenants/" + tenantId + "/testers",
                            admin,
                            "POST",
                            Map.of("testerId", testerId.toString()),
                            null);
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Recording> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS).status());
            }
            assertThat(statuses)
                    .as("every concurrent link answers 201 or the idempotent 200, never a 500")
                    .containsOnly(201, 200);
            assertThat(statuses.stream().filter(status -> status == 201).count())
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM tenant_tester_memberships WHERE tenant_id = ? AND tester_id = ?",
                            Integer.class,
                            tenantId,
                            testerId))
                    .isEqualTo(1);
            assertThat(audit.eventsOfType(AuditEventType.TENANT_TESTER_MEMBERSHIP_CREATED))
                    .as("one audit event for one mutation")
                    .hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
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

    /** Invites and registers an organization member with a role; returns their token. */
    private String inviteOrgMember(String admin, UUID orgId, String email, String role) {
        JsonNode invitation = post(
                admin,
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
                        "Role Holder"))
                .retrieve()
                .body(String.class);
        return accessToken(login(email, PASSWORD));
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

    private UUID createOrganization(String bearer, String name) {
        JsonNode created = post(bearer, "/api/v1/organizations", Map.of("name", name), 201);
        return UUID.fromString(created.get("id").asText());
    }

    private UUID organizationIdByName(String name) {
        return jdbc.queryForObject("SELECT id FROM organizations WHERE name = ?", UUID.class, name);
    }

    /** The registered user's id by email, for the console-membership matrix. */
    private UUID userIdByEmail(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
    }

    /** Invites a tester (born INVITED) and returns the creation response (invitation token shown once). */
    private JsonNode inviteTester(String admin, UUID orgId, String email, String fullName) {
        return post(
                admin,
                "/api/v1/testers",
                Map.of("organizationId", orgId.toString(), "email", email, "fullName", fullName),
                201);
    }

    /** The invitee registers: the person record turns ACTIVE (S6-01 flow). */
    private void acceptInvitation(JsonNode invitationResponse, String email, String fullName) {
        client.post()
                .uri("/api/v1/auth/register")
                .body(Map.of(
                        "invitationToken",
                        invitationResponse
                                .get("invitation")
                                .get("invitationToken")
                                .asText(),
                        "email",
                        email,
                        "password",
                        PASSWORD,
                        "fullName",
                        fullName))
                .retrieve()
                .body(String.class);
    }

    /** An ACTIVE tester person record: invited and accepted. */
    private UUID activeTester(String admin, UUID orgId, String email, String fullName) {
        JsonNode invited = inviteTester(admin, orgId, email, fullName);
        acceptInvitation(invited, email, fullName);
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

    private JsonNode post(String bearer, String uri, Map<String, Object> body, int expectedStatus) {
        Recording response = exchange(uri, bearer, "POST", body, null);
        assertThat(response.status).as("POST %s", uri).isEqualTo(expectedStatus);
        return response.body;
    }

    private JsonNode put(String bearer, String uri, Map<String, Object> body, int expectedStatus) {
        Recording response = exchange(uri, bearer, "PUT", body, null);
        assertThat(response.status).as("PUT %s", uri).isEqualTo(expectedStatus);
        return response.body;
    }

    private Recording post(String uri, String bearer, Map<String, Object> body) {
        return exchange(uri, bearer, "POST", body, null);
    }

    private Recording put(String uri, String bearer, Map<String, Object> body) {
        return exchange(uri, bearer, "PUT", body, null);
    }

    private JsonNode get(String bearer, String uri, int expectedStatus) {
        Recording response = exchange(uri, bearer, "GET", null, null);
        assertThat(response.status).as("GET %s", uri).isEqualTo(expectedStatus);
        return response.body;
    }

    private Recording get(String uri, String bearer) {
        return exchange(uri, bearer, "GET", null, null);
    }

    private Recording delete(String bearer, String uri) {
        return exchange(uri, bearer, "DELETE", null, null);
    }

    private Recording exchange(
            String uri, String bearer, String method, Map<String, Object> body, String idempotencyKey) {
        RestClient.RequestBodySpec spec =
                client.method(HttpMethod.valueOf(method)).uri(uri);
        if (bearer != null) {
            spec.headers(headers -> headers.setBearerAuth(bearer));
        }
        if (idempotencyKey != null) {
            spec.headers(headers -> headers.set("Idempotency-Key", idempotencyKey));
        }
        if (body != null) {
            spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((request, response) -> {
            byte[] bytes = response.getBody().readAllBytes();
            return new Recording(
                    response.getStatusCode().value(),
                    response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                    bytes.length == 0 ? mapper.createObjectNode() : mapper.readTree(bytes),
                    Boolean.parseBoolean(response.getHeaders().getFirst("Idempotency-Replayed")));
        });
    }

    private record Recording(int status, String contentType, JsonNode body, boolean replayed) {}
}
