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
 * End-to-end acceptance flow for issue #27 (S6-02, PRD 8.9/FR-010)
 * against a real PostgreSQL and HTTP stack: group CRUD scoped to a
 * tenant with per-tenant name uniqueness, idempotent membership
 * add/remove with a tester in multiple groups, tenant isolation with
 * concealed cross-tenant/cross-organization 404s, default-group
 * seeding on tenant creation (editable), testers-per-group and
 * groups-per-tester listings, the deactivated-tenant affiliation rule,
 * audited mutations, and Idempotency-Key replay.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
@Import(AuditCapture.class)
class TesterGroupsIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";
    private static final String PASSWORD = "a-perfectly-valid-password";

    /** PRD 8.9's example list, the application default (AC4). */
    private static final List<String> DEFAULT_GROUPS =
            List.of("Internal QA", "Tenant QA", "UAT", "Pilot", "Executive Beta", "External Beta");

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

    /** AC4: tenant creation seeds the configurable defaults; they are editable rows. */
    @Test
    void tenantCreationSeedsTheDefaultGroups() {
        String admin = loginOfOrgAdmin("tg-seed@example.com", "TG Seed Corp");
        UUID tenantId = createTenant(admin, "Seedful", "seed-external-1");

        JsonNode seeded = get(admin, "/api/v1/tester-groups?tenantId=" + tenantId, 200);
        assertThat(seeded.get("items"))
                .as("the PRD 8.9 examples are seeded on creation (AC4)")
                .hasSize(DEFAULT_GROUPS.size());
        List<String> names = new ArrayList<>();
        seeded.get("items").forEach(item -> names.add(item.get("name").asText()));
        assertThat(names).containsExactlyInAnyOrderElementsOf(DEFAULT_GROUPS);
        assertThat(seeded.get("items").get(0).get("tenantId").asText()).isEqualTo(tenantId.toString());

        assertThat(audit.eventsOfType(AuditEventType.TESTER_GROUP_CREATED))
                .as("seeding is audited like any creation")
                .hasSize(DEFAULT_GROUPS.size());
        assertThat(audit.eventsOfType(AuditEventType.TESTER_GROUP_CREATED)
                        .getFirst()
                        .detail())
                .containsEntry("seeded", "true");

        // The seeded groups are ordinary editable rows (the issue's
        // "templates, editable" wording).
        UUID pilotId = groupIdByName(tenantId, "Pilot");
        JsonNode renamed = put(admin, "/api/v1/tester-groups/" + pilotId, Map.of("name", "Early Pilot"), 200);
        assertThat(renamed.get("name").asText()).isEqualTo("Early Pilot");
        UUID uatId = groupIdByName(tenantId, "UAT");
        assertThat(delete(admin, "/api/v1/tester-groups/" + uatId).status).isEqualTo(204);
        assertThat(get(admin, "/api/v1/tester-groups?tenantId=" + tenantId, 200).get("items"))
                .hasSize(DEFAULT_GROUPS.size() - 1);
    }

    /** AC1: creation, per-tenant name uniqueness, validation, and unknown-tenant concealment. */
    @Test
    void createIsGuardedByPerTenantNameUniqueness() {
        String admin = loginOfOrgAdmin("tg-create@example.com", "TG Create Corp");
        UUID orgId = organizationIdByName("TG Create Corp");
        UUID tenantId = createTenant(admin, "Guarded", "guarded-external-1");
        UUID otherTenantId = createTenant(admin, "Guarded Two", "guarded-external-2");

        JsonNode created = post(
                admin, "/api/v1/tester-groups", Map.of("tenantId", tenantId.toString(), "name", "  Nightly  "), 201);
        UUID groupId = UUID.fromString(created.get("id").asText());
        assertThat(created.get("name").asText()).as("names are trimmed").isEqualTo("Nightly");
        assertThat(created.get("tenantId").asText()).isEqualTo(tenantId.toString());
        assertThat(created.get("createdAt")).isNotNull();

        Recording duplicate =
                post("/api/v1/tester-groups", admin, Map.of("tenantId", tenantId.toString(), "name", "Nightly"));
        assertThat(duplicate.status).as("names are unique per tenant (AC1)").isEqualTo(409);
        assertThat(duplicate.body.get("code").asText()).isEqualTo("TESTER_GROUP_NAME_TAKEN");

        JsonNode sameNameOtherTenant = post(
                admin, "/api/v1/tester-groups", Map.of("tenantId", otherTenantId.toString(), "name", "Nightly"), 201);
        assertThat(sameNameOtherTenant.get("id").asText())
                .as("the same name is free in the next tenant (per-tenant uniqueness)")
                .isNotEqualTo(groupId.toString());

        assertThat(post("/api/v1/tester-groups", admin, Map.of("tenantId", tenantId.toString(), "name", " ")).status)
                .isEqualTo(400);
        assertThat(post(
                                "/api/v1/tester-groups",
                                admin,
                                Map.of("tenantId", tenantId.toString(), "name", "x".repeat(201)))
                        .status)
                .isEqualTo(400);
        assertThat(post("/api/v1/tester-groups", admin, Map.of("tenantId", tenantId.toString())).status)
                .isEqualTo(400);
        assertThat(post(
                                "/api/v1/tester-groups",
                                admin,
                                Map.of("tenantId", UUID.randomUUID().toString(), "name", "Nowhere"))
                        .status)
                .as("an unknown tenant is concealed (platform-admin wildcard reaches the service)")
                .isEqualTo(404);

        AuditEvent createdEvent = audit.eventsOfType(AuditEventType.TESTER_GROUP_CREATED).stream()
                .filter(event -> event.detail().containsKey("name")
                        && "Nightly".equals(event.detail().get("name"))
                        && !event.detail().containsKey("seeded"))
                .findFirst()
                .orElseThrow();
        assertThat(createdEvent.tenantId()).isEqualTo(tenantId);
        assertThat(createdEvent.orgId())
                .as("tenant-grain events mirror the membership precedent")
                .isNull();

        // S1-08 registry: creation honors Idempotency-Key replay.
        String key = UUID.randomUUID().toString();
        Recording first = exchangeWithIdempotencyKey(
                "/api/v1/tester-groups", admin, Map.of("tenantId", tenantId.toString(), "name", "Keyed"), key);
        assertThat(first.status).isEqualTo(201);
        Recording replay = exchangeWithIdempotencyKey(
                "/api/v1/tester-groups", admin, Map.of("tenantId", tenantId.toString(), "name", "Keyed"), key);
        assertThat(replay.status).isEqualTo(201);
        assertThat(replay.body.get("id").asText())
                .isEqualTo(first.body.get("id").asText());
        assertThat(replay.replayed).isTrue();
    }

    /** AC3: tenant isolation within an organization and concealment across organizations. */
    @Test
    void groupsObeyTenantIsolationWithConcealedMisses() {
        String admin = loginOfOrgAdmin("tg-iso@example.com", "TG Iso Corp");
        UUID tenantA = createTenant(admin, "Alpha Tenant", "alpha-external-1");
        UUID tenantB = createTenant(admin, "Beta Tenant", "beta-external-1");
        UUID groupA = UUID.fromString(
                post(admin, "/api/v1/tester-groups", Map.of("tenantId", tenantA.toString(), "name", "Alpha Only"), 201)
                        .get("id")
                        .asText());
        UUID testerId = createTester(admin, organizationIdByName("TG Iso Corp"), "iso@example.com", "Iso Tester");

        JsonNode tenantBGroups = get(admin, "/api/v1/tester-groups?tenantId=" + tenantB, 200);
        assertThat(tenantBGroups.get("items"))
                .as("tenant B's listing never shows tenant A's group (AC3)")
                .extracting(item -> item.get("name").asText())
                .doesNotContain("Alpha Only");

        String stranger = loginOfOrgAdmin("tg-stranger@example.com", "TG Stranger Corp");
        assertThat(get("/api/v1/tester-groups/" + groupA, stranger).status)
                .as("a foreign organization's group is concealed (AC3)")
                .isEqualTo(404);
        assertThat(put("/api/v1/tester-groups/" + groupA, stranger, Map.of("name", "Stolen")).status)
                .isEqualTo(404);
        assertThat(delete(stranger, "/api/v1/tester-groups/" + groupA).status).isEqualTo(404);
        assertThat(get("/api/v1/tester-groups/" + groupA + "/members", stranger).status)
                .isEqualTo(404);
        assertThat(post(
                                "/api/v1/tester-groups/" + groupA + "/members",
                                stranger,
                                Map.of("testerId", testerId.toString()))
                        .status)
                .isEqualTo(404);
        assertThat(delete(stranger, "/api/v1/tester-groups/" + groupA + "/members/" + testerId).status)
                .isEqualTo(404);
        assertThat(get("/api/v1/tester-groups?tenantId=" + tenantA, stranger).status)
                .as("a foreign tenant listing is concealed as well")
                .isEqualTo(404);
        assertThat(get("/api/v1/tester-groups/" + UUID.randomUUID(), admin).status)
                .as("a genuine miss renders the same 404")
                .isEqualTo(404);
        assertThat(get("/api/v1/tester-groups/" + UUID.randomUUID(), admin)
                        .body
                        .get("code")
                        .asText())
                .isEqualTo("TESTER_GROUP_NOT_FOUND");

        assertThat(audit.eventsOfType(AuditEventType.ACCESS_DENIED))
                .as("concealed denials are audited (PRD 12)")
                .isNotEmpty();
    }

    /** AC2: membership add/remove idempotent; a tester joins multiple groups; listings both ways. */
    @Test
    void membershipIsIdempotentAndSpansGroups() {
        String admin = loginOfOrgAdmin("tg-member@example.com", "TG Member Corp");
        UUID orgId = organizationIdByName("TG Member Corp");
        UUID tenantId = createTenant(admin, "Memberful", "memberful-external-1");
        UUID pilotId = groupIdByName(tenantId, "Pilot");
        UUID uatId = groupIdByName(tenantId, "UAT");
        UUID anaId = createTester(admin, orgId, "ana.groups@example.com", "Ana Groups");
        UUID boId = createTester(admin, orgId, "bo.groups@example.com", "Bo Groups");
        linkTesterToTenant(admin, tenantId, anaId);
        linkTesterToTenant(admin, tenantId, boId);

        JsonNode added =
                post(admin, "/api/v1/tester-groups/" + pilotId + "/members", Map.of("testerId", anaId.toString()), 201);
        assertThat(added.get("added").asBoolean()).isTrue();
        JsonNode replayed =
                post(admin, "/api/v1/tester-groups/" + pilotId + "/members", Map.of("testerId", anaId.toString()), 200);
        assertThat(replayed.get("added").asBoolean())
                .as("membership add is idempotent (AC2)")
                .isFalse();
        assertThat(audit.eventsOfType(AuditEventType.TESTER_GROUP_MEMBER_ADDED))
                .as("the idempotent replay writes and audits nothing")
                .hasSize(1);

        post(admin, "/api/v1/tester-groups/" + uatId + "/members", Map.of("testerId", anaId.toString()), 201);
        post(admin, "/api/v1/tester-groups/" + pilotId + "/members", Map.of("testerId", boId.toString()), 201);

        JsonNode pilotMembers = get(admin, "/api/v1/tester-groups/" + pilotId + "/members", 200);
        assertThat(pilotMembers.get("items"))
                .as("testers per group, join-ordered")
                .hasSize(2);
        assertThat(pilotMembers.get("items").get(0).get("email").asText()).isEqualTo("ana.groups@example.com");
        assertThat(pilotMembers.get("items").get(0).get("status").asText()).isEqualTo("ACTIVE");
        assertThat(pilotMembers.get("items").get(0).get("addedAt")).isNotNull();

        JsonNode firstMember = get(admin, "/api/v1/tester-groups/" + pilotId + "/members?limit=1", 200);
        assertThat(firstMember.get("items")).hasSize(1);
        assertThat(firstMember.get("next_cursor")).isNotNull();
        JsonNode secondMember = get(
                admin,
                "/api/v1/tester-groups/" + pilotId + "/members?limit=1&cursor="
                        + firstMember.get("next_cursor").asText(),
                200);
        assertThat(secondMember.get("items"))
                .as("the members listing pages with the cursor convention too")
                .hasSize(1);
        assertThat(secondMember.get("items").get(0).get("email").asText()).isEqualTo("bo.groups@example.com");
        assertThat(secondMember.get("next_cursor")).isNull();

        JsonNode anaGroups = get(admin, "/api/v1/tester-groups?testerId=" + anaId, 200);
        assertThat(anaGroups.get("items"))
                .as("groups per tester, across the tenant (AC2: multiple groups)")
                .extracting(item -> item.get("name").asText())
                .containsExactly("Pilot", "UAT");
        assertThat(get(admin, "/api/v1/tester-groups?testerId=" + boId, 200).get("items"))
                .extracting(item -> item.get("name").asText())
                .containsExactly("Pilot");

        // Idempotency-Key on the membership route (registry member).
        String key = UUID.randomUUID().toString();
        Recording keyed = exchangeWithIdempotencyKey(
                "/api/v1/tester-groups/" + uatId + "/members", admin, Map.of("testerId", boId.toString()), key);
        assertThat(keyed.status).isEqualTo(201);
        Recording keyedReplay = exchangeWithIdempotencyKey(
                "/api/v1/tester-groups/" + uatId + "/members", admin, Map.of("testerId", boId.toString()), key);
        assertThat(keyedReplay.status).isEqualTo(201);
        assertThat(keyedReplay.replayed).isTrue();

        // Removal is idempotent too (AC2).
        assertThat(delete(admin, "/api/v1/tester-groups/" + uatId + "/members/" + anaId).status)
                .isEqualTo(204);
        assertThat(delete(admin, "/api/v1/tester-groups/" + uatId + "/members/" + anaId).status)
                .as("removing an absent membership is a no-op success")
                .isEqualTo(204);
        assertThat(audit.eventsOfType(AuditEventType.TESTER_GROUP_MEMBER_REMOVED))
                .hasSize(1);
        assertThat(get(admin, "/api/v1/tester-groups?testerId=" + anaId, 200).get("items"))
                .extracting(item -> item.get("name").asText())
                .containsExactly("Pilot");
    }

    /**
     * The groups-per-tester listing is organization-grain: a tester's
     * groups span the organization's tenants, never another
     * organization's, and unknown/foreign testers conceal as misses.
     */
    @Test
    void groupsPerTesterSpanTheOrganizationsTenantsOnly() {
        String admin = loginOfOrgAdmin("tg-span@example.com", "TG Span Corp");
        UUID orgId = organizationIdByName("TG Span Corp");
        UUID tenantOne = createTenant(admin, "Span One", "span-one-external-1");
        UUID tenantTwo = createTenant(admin, "Span Two", "span-two-external-1");
        UUID testerId = createTester(admin, orgId, "spanning@example.com", "Spanning Tester");
        linkTesterToTenant(admin, tenantOne, testerId);
        linkTesterToTenant(admin, tenantTwo, testerId);
        UUID pilotOne = groupIdByName(tenantOne, "Pilot");
        UUID customTwo = UUID.fromString(
                post(admin, "/api/v1/tester-groups", Map.of("tenantId", tenantTwo.toString(), "name", "Ring Zero"), 201)
                        .get("id")
                        .asText());
        post(admin, "/api/v1/tester-groups/" + pilotOne + "/members", Map.of("testerId", testerId.toString()), 201);
        post(admin, "/api/v1/tester-groups/" + customTwo + "/members", Map.of("testerId", testerId.toString()), 201);

        JsonNode groups = get(admin, "/api/v1/tester-groups?testerId=" + testerId, 200);
        assertThat(groups.get("items"))
                .as("the listing spans both of the organization's tenants, name-ordered")
                .extracting(item -> item.get("name").asText())
                .containsExactly("Pilot", "Ring Zero");
        assertThat(groups.get("items").get(0).get("tenantId").asText()).isEqualTo(tenantOne.toString());
        assertThat(groups.get("items").get(1).get("tenantId").asText()).isEqualTo(tenantTwo.toString());

        String stranger = loginOfOrgAdmin("tg-spanstranger@example.com", "TG Span Stranger Corp");
        assertThat(get("/api/v1/tester-groups?testerId=" + testerId, stranger).status)
                .as("a foreign organization cannot list another org's tester groups")
                .isEqualTo(404);
        assertThat(get("/api/v1/tester-groups?testerId=" + UUID.randomUUID(), admin).status)
                .as("an unknown tester is concealed")
                .isEqualTo(404);

        delete(admin, "/api/v1/tester-groups/" + customTwo);
        assertThat(get(admin, "/api/v1/tester-groups?testerId=" + testerId, 200).get("items"))
                .as("deleting the tenant-two group shrinks the org-grain listing")
                .extracting(item -> item.get("name").asText())
                .containsExactly("Pilot");
    }

    /** Membership validation: unknown and foreign-organization testers are concealed misses. */
    @Test
    void membershipRefusesUnknownAndForeignTesters() {
        String admin = loginOfOrgAdmin("tg-foreign@example.com", "TG Foreign Corp");
        UUID tenantId = createTenant(admin, "Foreignful", "foreignful-external-1");
        UUID pilotId = groupIdByName(tenantId, "Pilot");
        String otherAdmin = loginOfOrgAdmin("tg-other@example.com", "TG Other Corp");
        UUID otherTester =
                createTester(otherAdmin, organizationIdByName("TG Other Corp"), "far@example.com", "Far Tester");

        assertThat(post(
                                "/api/v1/tester-groups/" + pilotId + "/members",
                                admin,
                                Map.of("testerId", otherTester.toString()))
                        .status)
                .as("a foreign organization's tester is indistinguishable from a missing one")
                .isEqualTo(404);
        assertThat(post(
                                "/api/v1/tester-groups/" + pilotId + "/members",
                                admin,
                                Map.of("testerId", UUID.randomUUID().toString()))
                        .status)
                .isEqualTo(404);
        assertThat(post("/api/v1/tester-groups/" + pilotId + "/members", admin, Map.of()).status)
                .as("testerId is required")
                .isEqualTo(400);
    }

    /** Group rename/delete: audited, race-safe uniqueness, delete clears the roster. */
    @Test
    void renameAndDeleteAreAuditedAndClean() {
        String admin = loginOfOrgAdmin("tg-crud@example.com", "TG Crud Corp");
        UUID orgId = organizationIdByName("TG Crud Corp");
        UUID tenantId = createTenant(admin, "Crudful", "crudful-external-1");
        UUID keepId = UUID.fromString(
                post(admin, "/api/v1/tester-groups", Map.of("tenantId", tenantId.toString(), "name", "Keep"), 201)
                        .get("id")
                        .asText());
        UUID doomedId = UUID.fromString(
                post(admin, "/api/v1/tester-groups", Map.of("tenantId", tenantId.toString(), "name", "Doomed"), 201)
                        .get("id")
                        .asText());
        UUID testerId = createTester(admin, orgId, "roster@example.com", "Roster Tester");
        linkTesterToTenant(admin, tenantId, testerId);
        post(admin, "/api/v1/tester-groups/" + doomedId + "/members", Map.of("testerId", testerId.toString()), 201);

        Recording conflict = put("/api/v1/tester-groups/" + keepId, admin, Map.of("name", "Doomed"));
        assertThat(conflict.status).isEqualTo(409);
        assertThat(conflict.body.get("code").asText()).isEqualTo("TESTER_GROUP_NAME_TAKEN");
        JsonNode renamed = put(admin, "/api/v1/tester-groups/" + keepId, Map.of("name", "Kept"), 200);
        assertThat(renamed.get("name").asText()).isEqualTo("Kept");
        AuditEvent rename =
                audit.eventsOfType(AuditEventType.TESTER_GROUP_UPDATED).getFirst();
        assertThat(rename.detail()).containsEntry("name.before", "Keep").containsEntry("name.after", "Kept");

        assertThat(delete(admin, "/api/v1/tester-groups/" + doomedId).status).isEqualTo(204);
        assertThat(get("/api/v1/tester-groups/" + doomedId, admin).status).isEqualTo(404);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM tester_group_members WHERE group_id = ?", Integer.class, doomedId))
                .as("deleting a group removes its memberships")
                .isZero();
        AuditEvent deleted =
                audit.eventsOfType(AuditEventType.TESTER_GROUP_DELETED).getFirst();
        assertThat(deleted.detail()).containsEntry("membersRemoved", "1");
        assertThat(get(admin, "/api/v1/tester-groups?testerId=" + testerId, 200).get("items"))
                .as("the tester's group listing no longer shows the deleted group")
                .isEmpty();
    }

    /** The issue #9 affiliation rule, applied to groups: deactivated tenants take nothing new. */
    @Test
    void deactivatedTenantRefusesNewGroupsAndMembers() {
        String admin = loginOfOrgAdmin("tg-deact@example.com", "TG Deact Corp");
        UUID orgId = organizationIdByName("TG Deact Corp");
        UUID tenantId = createTenant(admin, "Deactful", "deactful-external-1");
        UUID pilotId = groupIdByName(tenantId, "Pilot");
        UUID existingTester = createTester(admin, orgId, "early@example.com", "Early Tester");
        linkTesterToTenant(admin, tenantId, existingTester);
        post(
                admin,
                "/api/v1/tester-groups/" + pilotId + "/members",
                Map.of("testerId", existingTester.toString()),
                201);
        // Linked before the deactivation: the group's own affiliation
        // rule stays the rule under test (the S6-03 link route would
        // refuse an unlinked tester's link first — this story's own
        // suite covers that refusal).
        UUID testerId = createTester(admin, orgId, "late@example.com", "Late Tester");
        linkTesterToTenant(admin, tenantId, testerId);
        put(admin, "/api/v1/tenants/" + tenantId, Map.of("status", "DEACTIVATED"), 200);

        Recording newGroup =
                post("/api/v1/tester-groups", admin, Map.of("tenantId", tenantId.toString(), "name", "Too Late"));
        assertThat(newGroup.status).isEqualTo(409);
        assertThat(newGroup.body.get("code").asText()).isEqualTo("TESTER_GROUP_TENANT_DEACTIVATED");
        Recording newMember =
                post("/api/v1/tester-groups/" + pilotId + "/members", admin, Map.of("testerId", testerId.toString()));
        assertThat(newMember.status).isEqualTo(409);
        assertThat(newMember.body.get("code").asText()).isEqualTo("TESTER_GROUP_TENANT_DEACTIVATED");

        assertThat(put(admin, "/api/v1/tester-groups/" + pilotId, Map.of("name", "Pilot Retired"), 200)
                        .get("name")
                        .asText())
                .as("a deactivated tenant stays administrable (reads, renames)")
                .isEqualTo("Pilot Retired");
        assertThat(get(admin, "/api/v1/tester-groups?tenantId=" + tenantId, 200).get("items"))
                .isNotEmpty();
        assertThat(delete(admin, "/api/v1/tester-groups/" + pilotId + "/members/" + existingTester).status)
                .as("member removal continues on a deactivated tenant (administrable, not new)")
                .isEqualTo(204);
        assertThat(get(admin, "/api/v1/tester-groups/" + pilotId + "/members", 200)
                        .get("items"))
                .isEmpty();
    }

    /** The tenant-grain listing pages with the platform cursor convention. */
    @Test
    void tenantListingIsCursorPaginated() {
        String admin = loginOfOrgAdmin("tg-page@example.com", "TG Page Corp");
        UUID tenantId = createTenant(admin, "Pageful", "pageful-external-1");
        for (int i = 1; i <= 3; i++) {
            post(admin, "/api/v1/tester-groups", Map.of("tenantId", tenantId.toString(), "name", "Extra " + i), 201);
        }
        JsonNode firstPage = get(admin, "/api/v1/tester-groups?tenantId=" + tenantId + "&limit=4", 200);
        assertThat(firstPage.get("items")).hasSize(4);
        assertThat(firstPage.get("next_cursor")).isNotNull();
        JsonNode secondPage = get(
                admin,
                "/api/v1/tester-groups?tenantId=" + tenantId + "&limit=4&cursor="
                        + firstPage.get("next_cursor").asText(),
                200);
        assertThat(secondPage.get("items"))
                .as("six seeded defaults plus three custom groups page 4+4+1")
                .hasSize(4);
        assertThat(secondPage.get("next_cursor")).isNotNull();
        JsonNode thirdPage = get(
                admin,
                "/api/v1/tester-groups?tenantId=" + tenantId + "&limit=4&cursor="
                        + secondPage.get("next_cursor").asText(),
                200);
        assertThat(thirdPage.get("items")).hasSize(1);
        assertThat(thirdPage.get("next_cursor")).isNull();
        assertThat(get("/api/v1/tester-groups?tenantId=" + tenantId + "&limit=0", admin).status)
                .isEqualTo(400);
        assertThat(get("/api/v1/tester-groups", admin).status)
                .as("with no target at all the permission check denies first (authorization precedes validation)")
                .isEqualTo(403);
        assertThat(get("/api/v1/tester-groups?tenantId=" + tenantId + "&testerId=" + UUID.randomUUID(), admin).status)
                .isEqualTo(400);
    }

    /** FR-010 "assign groups": QA_ENGINEER (the story persona) manages groups. */
    @Test
    void qaEngineersManageGroups() {
        String admin = loginOfOrgAdmin("tg-qa@example.com", "TG Qa Corp");
        UUID orgId = organizationIdByName("TG Qa Corp");
        UUID tenantId = createTenant(admin, "Qaful", "qaful-external-1");
        String qaEngineer = inviteOrgMember(admin, orgId, "qa.person@example.com", "QA_ENGINEER");

        JsonNode created = post(
                qaEngineer, "/api/v1/tester-groups", Map.of("tenantId", tenantId.toString(), "name", "QA Built"), 201);
        UUID createdId = UUID.fromString(created.get("id").asText());
        assertThat(get(qaEngineer, "/api/v1/tester-groups?tenantId=" + tenantId, 200)
                        .get("items"))
                .extracting(item -> item.get("name").asText())
                .contains("QA Built");
        assertThat(put(qaEngineer, "/api/v1/tester-groups/" + createdId, Map.of("name", "QA Renamed"), 200)
                        .get("name")
                        .asText())
                .isEqualTo("QA Renamed");

        String developer = inviteOrgMember(admin, orgId, "dev.person@example.com", "DEVELOPER");
        assertThat(get("/api/v1/tester-groups?tenantId=" + tenantId, developer).status)
                .as("DEVELOPER holds no app:tester-groups:manage (concealed)")
                .isEqualTo(404);
    }

    /** AC2's concurrency core: simultaneous adds of one pair collapse to one membership. */
    @Test
    void concurrentMembershipAddsCollapseToOneRow() throws Exception {
        String admin = loginOfOrgAdmin("tg-race@example.com", "TG Race Corp");
        UUID orgId = organizationIdByName("TG Race Corp");
        UUID tenantId = createTenant(admin, "Raceful", "raceful-external-1");
        UUID pilotId = groupIdByName(tenantId, "Pilot");
        UUID testerId = createTester(admin, orgId, "racing@example.com", "Racing Tester");
        linkTesterToTenant(admin, tenantId, testerId);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Recording>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return exchange(
                            "/api/v1/tester-groups/" + pilotId + "/members",
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
                    .as("every concurrent add answers 201 or the idempotent 200, never a 500")
                    .containsOnly(201, 200);
            assertThat(statuses.stream().filter(status -> status == 201).count())
                    .as("exactly one caller created the membership")
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM tester_group_members WHERE group_id = ? AND tester_id = ?",
                            Integer.class,
                            pilotId,
                            testerId))
                    .isEqualTo(1);
            assertThat(audit.eventsOfType(AuditEventType.TESTER_GROUP_MEMBER_ADDED))
                    .as("one audit event for one mutation")
                    .hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * The platform-administrator wildcard must not turn an unknown group
     * into a seam failure: the evaluator passes platform admins on any
     * target, the route interceptor binds no context for an unknown
     * group, and the seam then refuses unscoped — the caller must still
     * see the concealed miss, never a 500 (live-smoke finding).
     */
    @Test
    void platformAdminSeesConcealedMissesForUnknownAndDeletedGroups() {
        String devAdmin = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        UUID unknown = UUID.randomUUID();
        assertThat(get("/api/v1/tester-groups/" + unknown, devAdmin).status)
                .as("an unknown group is a concealed miss even for the platform wildcard")
                .isEqualTo(404);
        assertThat(get("/api/v1/tester-groups/" + unknown, devAdmin)
                        .body
                        .get("code")
                        .asText())
                .isEqualTo("TESTER_GROUP_NOT_FOUND");
        assertThat(get("/api/v1/tester-groups/" + unknown + "/members", devAdmin).status)
                .isEqualTo(404);

        String admin = loginOfOrgAdmin("tg-padev@example.com", "TG Pa Dev Corp");
        UUID tenantId = createTenant(admin, "Deletable", "deletable-external-1");
        UUID groupId = UUID.fromString(
                post(admin, "/api/v1/tester-groups", Map.of("tenantId", tenantId.toString(), "name", "Vanishing"), 201)
                        .get("id")
                        .asText());
        assertThat(delete(devAdmin, "/api/v1/tester-groups/" + groupId).status)
                .as("the platform administrator may administer the tenant's groups")
                .isEqualTo(204);
        assertThat(get("/api/v1/tester-groups/" + groupId, devAdmin).status)
                .as("a deleted group renders as the same concealed miss (the live-smoke 500)")
                .isEqualTo(404);
    }

    /** AC1's concurrency core: simultaneous creates of one name yield one group and 409s. */
    @Test
    void concurrentCreatesOfOneNameYieldOneGroup() throws Exception {
        String admin = loginOfOrgAdmin("tg-race2@example.com", "TG Race Two Corp");
        UUID tenantId = createTenant(admin, "Raceful Two", "raceful-two-external-1");

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Recording>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return exchange(
                            "/api/v1/tester-groups",
                            admin,
                            "POST",
                            Map.of("tenantId", tenantId.toString(), "name", "Contested"),
                            null);
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Recording> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS).status());
            }
            assertThat(statuses).containsOnly(201, 409);
            assertThat(statuses.stream().filter(status -> status == 201).count())
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM tester_groups WHERE tenant_id = ? AND name = 'Contested'",
                            Integer.class,
                            tenantId))
                    .isEqualTo(1);
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

    /**
     * Creates a tester and completes their onboarding (the invitee
     * registers, the person record turns ACTIVE) — since S6-03 a
     * tester must be ACTIVE before a tenant can link them, and group
     * membership requires the tenant link.
     */
    private UUID createTester(String admin, UUID orgId, String email, String fullName) {
        JsonNode created = post(
                admin,
                "/api/v1/testers",
                Map.of("organizationId", orgId.toString(), "email", email, "fullName", fullName),
                201);
        client.post()
                .uri("/api/v1/auth/register")
                .body(Map.of(
                        "invitationToken",
                        created.get("invitation").get("invitationToken").asText(),
                        "email",
                        email,
                        "password",
                        PASSWORD,
                        "fullName",
                        fullName))
                .retrieve()
                .body(String.class);
        return UUID.fromString(created.get("id").asText());
    }

    /** Links an ACTIVE tester to the tenant (S6-03) — group membership's precondition. */
    private void linkTesterToTenant(String bearer, UUID tenantId, UUID testerId) {
        post(bearer, "/api/v1/tenants/" + tenantId + "/testers", Map.of("testerId", testerId.toString()), 201);
    }

    private UUID groupIdByName(UUID tenantId, String name) {
        return jdbc.queryForObject(
                "SELECT id FROM tester_groups WHERE tenant_id = ? AND name = ?", UUID.class, tenantId, name);
    }

    private UUID organizationIdByName(String name) {
        return jdbc.queryForObject("SELECT id FROM organizations WHERE name = ?", UUID.class, name);
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

    private Recording exchangeWithIdempotencyKey(
            String uri, String bearer, Map<String, Object> body, String idempotencyKey) {
        return exchange(uri, bearer, "POST", body, idempotencyKey);
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
