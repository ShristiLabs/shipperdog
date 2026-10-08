package com.shipperdog.api.release;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.AuditCapture;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
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
 * End-to-end acceptance flow for the release-ring surface (issue #43,
 * S10-04, FR-017, PRD 8.10/33): the seeded five that exist on
 * organization creation and list in progressive order with Production
 * last (AC1), the add/rename/reorder CRUD with the ladder's order
 * rules and the system-ring immutability (AC1), the production
 * governance pair — the approval-policy reference and the required
 * gate set (AC3), the eligibility validation and the admission
 * arithmetic — distribution targets intersect ring eligibility on the
 * tenant and group axes, with the tester visibility decision on top
 * (AC2/AC4), the approval-policy precedence the ring leg owns (ring
 * named policy, environment named policy, ring-attached chains), the
 * delete guards (AC1's reference discipline), and the audit trail of
 * every ring edit (AC5 — ring edits change who can receive what).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
@Import(AuditCapture.class)
class RingIntegrationTest {

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

    private int versionCounter;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        audit.reset();
        versionCounter = 100 + (int) (Math.random() * 100);
    }

    // -----------------------------------------------------------------------
    // AC1: the seeded five and the effective vocabulary
    // -----------------------------------------------------------------------

    @Test
    void seededFiveExistOnOrgCreationAndListInOrderWithProductionLast() {
        World world = world("ring-seed");
        JsonNode listed = get(world.adminToken, "/api/v1/rings?organizationId=" + world.org, 200);
        List<String> codes = new ArrayList<>();
        listed.get("items").forEach(item -> codes.add(item.get("code").asText()));
        assertThat(codes).containsExactly("engineering", "qa", "tenant-pilot", "uat", "production");
        assertThat(listed.get("items").get(4).get("position").asInt()).isEqualTo(4);
        assertThat(listed.get("items").get(4).get("organizationScoped").asBoolean())
                .isTrue();
        assertThat(listed.get("items").get(4).get("promotionPolicy").asText()).isEqualTo("MANUAL");
        assertThat(listed.get("items").get(4).get("eligibility")).isEmpty();
        assertThat(listed.has("next_cursor")).isFalse();

        // Seeding wrote no audit events (part of creation, not edits).
        assertThat(audit.eventsOfType(AuditEventType.RING_CREATED)).isEmpty();
    }

    @Test
    void customRingsAddRenameAndReorderWithinTheLadder() {
        World world = world("ring-crud");
        JsonNode beta = createRing(world.adminToken, world.org, "beta", "Beta", 2, null, null, null, 201);
        assertThat(beta.get("organizationScoped").asBoolean()).isTrue();
        assertThat(audit.eventsOfType(AuditEventType.RING_CREATED))
                .anyMatch(event -> "beta".equals(event.detail().get("code")));

        // Rename and reorder in one PATCH; absent fields keep standing.
        Map<String, Object> edit = new LinkedHashMap<>();
        edit.put("name", "External Beta");
        edit.put("position", 3);
        JsonNode renamed = patch(
                world.adminToken,
                "/api/v1/rings/" + beta.get("id").asText() + "?organizationId=" + world.org,
                edit,
                200);
        assertThat(renamed.get("name").asText()).isEqualTo("External Beta");
        assertThat(renamed.get("position").asInt()).isEqualTo(3);
        assertThat(audit.eventsOfType(AuditEventType.RING_UPDATED))
                .anyMatch(event -> "Beta -> External Beta".equals(event.detail().get("name")));

        JsonNode listed = get(world.adminToken, "/api/v1/rings?organizationId=" + world.org, 200);
        List<String> codes = new ArrayList<>();
        listed.get("items").forEach(item -> codes.add(item.get("code").asText()));
        assertThat(codes).containsExactly("engineering", "qa", "tenant-pilot", "beta", "uat", "production");

        // The duplicate code is the precise 409; a SHARED position is
        // the documented interleave — the seeded five occupy 0-4
        // densely, a custom ring ties its lower neighbor and the
        // total order (position, then code) places it.
        Map<String, Object> clash = new LinkedHashMap<>();
        clash.put("organizationId", world.org.toString());
        clash.put("code", "beta");
        clash.put("name", "Beta Again");
        clash.put("position", 1);
        assertThat(postRaw(world.adminToken, "/api/v1/rings", clash)
                        .body
                        .get("code")
                        .asText())
                .isEqualTo("RING_ALREADY_EXISTS");
        createRing(world.adminToken, world.org, "canary", "Canary", 3, null, null, null, 201);
        JsonNode tied = get(world.adminToken, "/api/v1/rings?organizationId=" + world.org, 200);
        List<String> tiedCodes = new ArrayList<>();
        tied.get("items").forEach(item -> tiedCodes.add(item.get("code").asText()));
        assertThat(tiedCodes)
                .containsExactly("engineering", "qa", "tenant-pilot", "beta", "canary", "uat", "production");
    }

    @Test
    void productionLastIsEnforcedAndProductionCarriesGovernance() {
        World world = world("ring-prod");
        // A non-production ring never passes the production ring.
        Map<String, Object> past = new LinkedHashMap<>();
        past.put("organizationId", world.org.toString());
        past.put("code", "gamma");
        past.put("name", "Gamma");
        past.put("position", 4);
        assertThat(postRaw(world.adminToken, "/api/v1/rings", past)
                        .body
                        .get("code")
                        .asText())
                .isEqualTo("RING_POSITION_INVALID");

        // The production ring's writes demand the governance pair: the
        // approval-policy reference first, then the required gate set.
        UUID production = orgRingIdOf(world.org, "production");
        Map<String, Object> bare = new LinkedHashMap<>();
        bare.put("name", "Production");
        assertThat(patchRaw(world.adminToken, ringUri(world.org, production), bare)
                        .body
                        .get("code")
                        .asText())
                .isEqualTo("RING_PRODUCTION_POLICY_REQUIRED");
        Map<String, Object> policyOnly = new LinkedHashMap<>();
        policyOnly.put("name", "Production");
        policyOnly.put("approvalPolicy", "final-signoff");
        assertThat(patchRaw(world.adminToken, ringUri(world.org, production), policyOnly)
                        .body
                        .get("code")
                        .asText())
                .isEqualTo("RING_PRODUCTION_GATES_REQUIRED");

        // One org-wide required gate definition satisfies the gate-set
        // reference; the write lands and audits.
        createDefinition(world.managerToken, world.org, "coverage-threshold", null, true, Map.of("threshold", 80), 201);
        JsonNode governed = patch(world.adminToken, ringUri(world.org, production), policyOnly, 200);
        assertThat(governed.get("approvalPolicy").asText()).isEqualTo("final-signoff");
        assertThat(governed.get("gateSet").get(0).get("gateType").asText()).isEqualTo("coverage-threshold");

        // Moving production off the last position refuses.
        Map<String, Object> earlier = new LinkedHashMap<>();
        earlier.put("position", 2);
        assertThat(patchRaw(world.adminToken, ringUri(world.org, production), earlier)
                        .body
                        .get("code")
                        .asText())
                .isEqualTo("RING_POSITION_INVALID");
    }

    @Test
    void systemRingsAreImmutableAndShadowingCustomizesTheCode() {
        World world = world("ring-shadow");
        UUID systemQa = systemRingIdOf("qa");

        Map<String, Object> edit = new LinkedHashMap<>();
        edit.put("name", "Nope");
        assertThat(patchRaw(world.adminToken, ringUri(world.org, systemQa), edit)
                        .body
                        .get("code")
                        .asText())
                .isEqualTo("RING_SYSTEM_IMMUTABLE");
        assertThat(deleteRaw(world.adminToken, ringUri(world.org, systemQa))
                        .body
                        .get("code")
                        .asText())
                .isEqualTo("RING_SYSTEM_IMMUTABLE");

        // The organization's own ring under the SYSTEM code is the
        // shadow. Since V32 the seeds already occupy every system
        // code, so the shadow flow starts by DELETING the seeded ring
        // (unreferenced here) — the code's effective meaning falls
        // back to the system default until the shadow redefines it.
        deleteRaw(world.adminToken, ringUri(world.org, orgRingIdOf(world.org, "qa")));
        Map<String, Object> shadow = new LinkedHashMap<>();
        shadow.put("organizationId", world.org.toString());
        shadow.put("code", "qa");
        shadow.put("name", "Hardened QA");
        shadow.put("position", 1);
        JsonNode created = post(world.adminToken, "/api/v1/rings", shadow, 201);
        Release release = createRelease(world, "qa", List.of(), "tenant-pilot-shadow");
        assertThat(jdbc.queryForObject("SELECT starting_ring_id FROM releases WHERE id = ?", UUID.class, release.id()))
                .isEqualTo(UUID.fromString(created.get("id").asText()));

        // The effective vocabulary carries the code ONCE — the shadow,
        // never the system default beneath it.
        JsonNode listed = get(world.adminToken, "/api/v1/rings?organizationId=" + world.org, 200);
        List<String> codes = new ArrayList<>();
        listed.get("items").forEach(item -> codes.add(item.get("code").asText()));
        assertThat(codes).containsExactly("engineering", "qa", "tenant-pilot", "uat", "production");
        assertThat(listed.get("items").get(1).get("name").asText()).isEqualTo("Hardened QA");
        assertThat(listed.get("items").get(1).get("organizationScoped").asBoolean())
                .isTrue();
    }

    // -----------------------------------------------------------------------
    // AC2: the eligibility rules and the admission arithmetic
    // -----------------------------------------------------------------------

    @Test
    void eligibilityValidatesOrganizationMembership() {
        World world = world("ring-elig-val");
        World foreign = world("ring-elig-foreign");
        UUID foreignGroup = createGroup(foreign.adminToken, foreign.tenantA, "Foreign Group");

        // Unknown axis.
        Map<String, Object> badAxis =
                ringBody(world.org, "nightly", "Nightly", 1, Map.of("percent", List.of("50")), null, null);
        assertThat(postRaw(world.adminToken, "/api/v1/rings", badAxis)
                        .body
                        .get("code")
                        .asText())
                .isEqualTo("RING_ELIGIBILITY_INVALID");

        // Empty axis arrays.
        Map<String, Object> emptyAxis =
                ringBody(world.org, "nightly", "Nightly", 1, Map.of("tenants", List.of()), null, null);
        JsonNode refused = postRaw(world.adminToken, "/api/v1/rings", emptyAxis).body;
        assertThat(refused.get("code").asText()).isEqualTo("RING_ELIGIBILITY_INVALID");
        assertThat(refused.get("errors").get("eligibility.tenants").asText()).contains("at least one");

        // A tenant of another organization, and a group of another
        // organization: both refuse with the offending member keyed.
        Map<String, Object> foreignIds = ringBody(
                world.org,
                "nightly",
                "Nightly",
                1,
                Map.of("tenants", List.of(foreign.tenantA.toString()), "groups", List.of(foreignGroup.toString())),
                null,
                null);
        JsonNode rejected = postRaw(world.adminToken, "/api/v1/rings", foreignIds).body;
        assertThat(rejected.get("code").asText()).isEqualTo("RING_ELIGIBILITY_INVALID");
        assertThat(rejected.get("errors")
                        .get("eligibility.tenants[" + foreign.tenantA + "]")
                        .asText())
                .contains("not one of the organization's tenants");
        assertThat(rejected.get("errors")
                        .get("eligibility.groups[" + foreignGroup + "]")
                        .asText())
                .contains("does not belong");

        // The organization's own tenant passes; an empty object CLEARS
        // standing rules on edit (the documented reset).
        Map<String, Object> valid = ringBody(
                world.org, "nightly", "Nightly", 1, Map.of("tenants", List.of(world.tenantA.toString())), null, null);
        JsonNode created = post(world.adminToken, "/api/v1/rings", valid, 201);
        assertThat(created.get("eligibility").get("tenants").size()).isEqualTo(1);
        Map<String, Object> clear = new LinkedHashMap<>();
        clear.put("eligibility", Map.of());
        JsonNode cleared = patch(
                world.adminToken,
                ringUri(world.org, UUID.fromString(created.get("id").asText())),
                clear,
                200);
        assertThat(cleared.get("eligibility")).isEmpty();
    }

    @Test
    void eligibilityResolvesTargetsAndAudiences() {
        World world = world("ring-admit");
        UUID pilotGroup = createGroup(world.adminToken, world.tenantA, "Pilot Group");
        UUID otherGroup = createGroup(world.adminToken, world.tenantA, "Other Group");
        UUID tenantB = createTenant(world.adminToken, world.org, "Admit Bank B", "crm-ring-admit-b");
        world.app = createSharedApp(
                world.ownerToken, world.org, world.tenantA, tenantB, "Ring Admit Shared", "com.ringadmit.shared");

        // A release at 'qa' targeting tenantA (group narrowed) and
        // tenantB (at large), with the ring ruled: tenantA only, and
        // within it the pilot group alone.
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("tenants", List.of(world.tenantA.toString()));
        rule.put("groups", List.of(pilotGroup.toString()));
        Map<String, Object> ruledEdit = new LinkedHashMap<>();
        ruledEdit.put("name", "Ruled QA");
        ruledEdit.put("eligibility", rule);
        patch(world.adminToken, ringUri(world.org, orgRingIdOf(world.org, "qa")), ruledEdit, 200);

        Release release = createReleaseWithTargets(
                world,
                "qa",
                List.of(
                        Map.of("tenantId", world.tenantA.toString(), "testerGroupIds", List.of(pilotGroup.toString())),
                        Map.of("tenantId", tenantB.toString(), "testerGroupIds", List.of())),
                "ring-admit-rel");

        JsonNode eligibility = get(
                world.managerToken,
                "/api/v1/releases/" + release.id() + "/eligibility?applicationId=" + world.app,
                200);
        assertThat(eligibility.get("releaseRef").asText()).isEqualTo(release.ref());
        assertThat(eligibility.get("admission").get("ring").asText()).isEqualTo("qa");
        Map<String, JsonNode> byTenant = new HashMap<>();
        eligibility
                .get("admission")
                .get("targets")
                .forEach(row -> byTenant.put(row.get("tenantId").asText(), row));
        assertThat(byTenant.get(world.tenantA.toString()).get("admitted").asBoolean())
                .isTrue();
        assertThat(byTenant.get(world.tenantA.toString()).get("tenantAtLarge").asBoolean())
                .isFalse();
        assertThat(byTenant.get(world.tenantA.toString()).get("audienceGroups").toString())
                .contains(pilotGroup.toString());
        assertThat(byTenant.get(world.tenantA.toString()).get("audienceGroups").toString())
                .doesNotContain(otherGroup.toString());
        assertThat(byTenant.get(tenantB.toString()).get("admitted").asBoolean()).isFalse();
        assertThat(byTenant.get(tenantB.toString()).get("reasons").get(0).asText())
                .contains("not in the ring's eligibility");

        // Clearing the rules admits every target again (the seeded
        // standing: rings widen by configuration, never by default).
        UUID shadowId = orgRingIdOf(world.org, "qa");
        Map<String, Object> clear = new LinkedHashMap<>();
        clear.put("eligibility", Map.of());
        patch(world.adminToken, ringUri(world.org, shadowId), clear, 200);
        JsonNode open = get(
                world.managerToken,
                "/api/v1/releases/" + release.id() + "/eligibility?applicationId=" + world.app,
                200);
        open.get("admission")
                .get("targets")
                .forEach(row -> assertThat(row.get("admitted").asBoolean()).isTrue());
    }

    @Test
    void testerVisibilityFollowsTheRingAndTheMembership() {
        World world = world("ring-visibility");
        UUID pilotGroup = createGroup(world.adminToken, world.tenantA, "Pilot Group");
        UUID outsiderGroup = createGroup(world.adminToken, world.tenantA, "Outsider Group");
        UUID tenantB = createTenant(world.adminToken, world.org, "Visibility Bank B", "crm-ring-vis-b");
        world.app = createSharedApp(
                world.ownerToken, world.org, world.tenantA, tenantB, "Ring Visibility Shared", "com.ringvis.shared");

        UUID pilotTester = createTester(world.adminToken, world.org, "pilot-ring@example.com", "Pilot Tester");
        linkTesterToTenant(world.adminToken, world.tenantA, pilotTester);
        addMember(world.adminToken, pilotGroup, pilotTester);

        UUID otherGroupTester =
                createTester(world.adminToken, world.org, "other-group-ring@example.com", "Other Group Tester");
        linkTesterToTenant(world.adminToken, world.tenantA, otherGroupTester);
        addMember(world.adminToken, outsiderGroup, otherGroupTester);

        UUID tenantBTester = createTester(world.adminToken, world.org, "tenantb-ring@example.com", "Tenant B Tester");
        linkTesterToTenant(world.adminToken, tenantB, tenantBTester);

        // The release targets tenantA narrowed to the pilot group, and
        // tenantB at large; the ring's rule narrows to the pilot group.
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("groups", List.of(pilotGroup.toString()));
        Map<String, Object> groupedEdit = new LinkedHashMap<>();
        groupedEdit.put("name", "Grouped QA");
        groupedEdit.put("eligibility", rule);
        patch(world.adminToken, ringUri(world.org, orgRingIdOf(world.org, "qa")), groupedEdit, 200);
        Release release = createReleaseWithTargets(
                world,
                "qa",
                List.of(
                        Map.of("tenantId", world.tenantA.toString(), "testerGroupIds", List.of(pilotGroup.toString())),
                        Map.of("tenantId", tenantB.toString(), "testerGroupIds", List.of())),
                "ring-vis-rel");

        String base = "/api/v1/releases/" + release.id() + "/eligibility?applicationId=" + world.app;
        assertThat(get(world.managerToken, base + "&testerId=" + pilotTester, 200)
                        .get("admitsTester")
                        .asBoolean())
                .isTrue();
        // In the tenant, in a group — but not one the audience carries.
        assertThat(get(world.managerToken, base + "&testerId=" + otherGroupTester, 200)
                        .get("admitsTester")
                        .asBoolean())
                .isFalse();
        // The tenant-at-large target narrowed by the ring's group rule:
        // the rule lists no group of tenantB, so nobody there admits.
        assertThat(get(world.managerToken, base + "&testerId=" + tenantBTester, 200)
                        .get("admitsTester")
                        .asBoolean())
                .isFalse();

        // A suspended tester sees nothing (the two-switches rule): the
        // person record deactivates, the decision follows live.
        jdbc.update("UPDATE testers SET status = 'SUSPENDED' WHERE id = ?", pilotTester);
        assertThat(get(world.managerToken, base + "&testerId=" + pilotTester, 200)
                        .get("admitsTester")
                        .asBoolean())
                .isFalse();
    }

    // -----------------------------------------------------------------------
    // The approval-policy precedence: the ring leg the ring surface owns
    // -----------------------------------------------------------------------

    @Test
    void ringPolicyPrecedesEnvironmentPolicyAndFailsClosed() {
        World world = world("ring-precedence");
        createWorkflow(world.adminToken, world.org, "ring-chain", null, "RELEASE_MANAGER");
        createWorkflow(world.adminToken, world.org, "env-chain", null, "APP_OWNER");
        createEnvironment(world.adminToken, world.org, "prod-ctx", "Production Context", "env-chain");

        // The ring's named policy governs the release's crossing even
        // though the targeted environment binds its own.
        Map<String, Object> chainedEdit = new LinkedHashMap<>();
        chainedEdit.put("name", "Chained QA");
        chainedEdit.put("approvalPolicy", "ring-chain");
        patch(world.adminToken, ringUri(world.org, orgRingIdOf(world.org, "qa")), chainedEdit, 200);
        UUID environmentId = jdbc.queryForObject("SELECT id FROM environments WHERE code = 'prod-ctx'", UUID.class);
        put(
                world.adminToken,
                "/api/v1/environments/" + environmentId + "/tenants",
                Map.of("tenantIds", List.of(world.tenantA.toString())),
                200);
        Release release = createReleaseWithEnvironment(world, "qa", List.of(), environmentId, "ring-prec-rel");
        transition(world.managerToken, world.app, release.id(), "VALIDATING", 200);
        transition(world.managerToken, world.app, release.id(), "READY", 200);
        transition(world.managerToken, world.app, release.id(), "APPROVAL_PENDING", 200);
        JsonNode approvals = get(
                world.managerToken, "/api/v1/releases/" + release.id() + "/approvals?applicationId=" + world.app, 200);
        assertThat(approvals.get("rounds").get(0).get("workflowName").asText()).isEqualTo("ring-chain");

        // A ring naming a policy that does not resolve fails CLOSED at
        // the crossing: the release stays READY, the refusal carries
        // the policy name (the S10-02 decision, honored for rings).
        World strict = world("ring-failclosed");
        Map<String, Object> danglingEdit = new LinkedHashMap<>();
        danglingEdit.put("name", "Dangling QA");
        danglingEdit.put("approvalPolicy", "no-such-chain");
        patch(strict.adminToken, ringUri(strict.org, orgRingIdOf(strict.org, "qa")), danglingEdit, 200);
        Release stuck = createRelease(strict, "qa", List.of(), "ring-fc-rel");
        transition(strict.managerToken, strict.app, stuck.id(), "VALIDATING", 200);
        transition(strict.managerToken, strict.app, stuck.id(), "READY", 200);
        JsonNode refused = transition(strict.managerToken, strict.app, stuck.id(), "APPROVAL_PENDING", 422);
        assertThat(refused.get("code").asText()).isEqualTo("APPROVAL_POLICY_UNRESOLVED");
        assertThat(refused.get("errors").get("approvalPolicy").asText()).isEqualTo("no-such-chain");
    }

    // -----------------------------------------------------------------------
    // AC1/AC5: the delete guards and the audit discipline
    // -----------------------------------------------------------------------

    @Test
    void referencedRingsRefuseDeleteAndUnreferencedDeletesAudit() {
        World world = world("ring-delete");
        Release release = createRelease(world, "qa", List.of(), "ring-del-rel");
        UUID referenced = orgRingIdOf(world.org, "qa");
        assertThat(deleteRaw(world.adminToken, ringUri(world.org, referenced))
                        .body
                        .get("code")
                        .asText())
                .isEqualTo("RING_IN_USE");

        JsonNode spare = createRing(world.adminToken, world.org, "spare", "Spare", 2, null, null, null, 201);
        assertThat(deleteRaw(
                                world.adminToken,
                                ringUri(
                                        world.org,
                                        UUID.fromString(spare.get("id").asText())))
                        .status)
                .isEqualTo(204);
        assertThat(audit.eventsOfType(AuditEventType.RING_DELETED))
                .anyMatch(event -> "spare".equals(event.detail().get("code")));
    }

    @Test
    void crossOrganizationAndRoleConcealment() {
        World world = world("ring-conceal");
        World foreign = world("ring-conceal-foreign");
        JsonNode own = createRing(world.adminToken, world.org, "beta", "Beta", 2, null, null, null, 201);

        // A foreign ring conceals exactly like an unknown id; the
        // organization-grain write denies conceal through the same 404.
        assertThat(getRaw(
                                world.adminToken,
                                "/api/v1/rings/" + own.get("id").asText() + "?organizationId=" + foreign.org)
                        .status)
                .isEqualTo(404);
        Map<String, Object> edit = new LinkedHashMap<>();
        edit.put("name", "Hijack");
        assertThat(patchRaw(
                                world.adminToken,
                                "/api/v1/rings/" + own.get("id").asText() + "?organizationId=" + foreign.org,
                                edit)
                        .status)
                .isEqualTo(404);

        // Reads are the read-holders' surface; the manage authority is
        // the governance personas' alone (the developer conceals).
        assertThat(getRaw(world.developerToken, "/api/v1/rings?organizationId=" + world.org).status)
                .isEqualTo(200);
        assertThat(getRaw(world.viewerToken, "/api/v1/rings?organizationId=" + world.org).status)
                .isEqualTo(200);
        Map<String, Object> attempt = new LinkedHashMap<>();
        attempt.put("name", "Nope");
        assertThat(patchRaw(
                                world.developerToken,
                                ringUri(world.org, UUID.fromString(own.get("id").asText())),
                                attempt)
                        .status)
                .isEqualTo(404);

        // The release eligibility read conceals a foreign release
        // exactly like the release surface itself.
        assertThat(getRaw(
                                world.managerToken,
                                "/api/v1/releases/" + UUID.randomUUID() + "/eligibility?applicationId=" + foreign.app)
                        .status)
                .isEqualTo(404);
    }

    // -----------------------------------------------------------------------
    // The fixtures and helpers (the gate suite's standing)
    // -----------------------------------------------------------------------

    private static final class World {
        UUID org;
        String adminToken;
        String ownerToken;
        String managerToken;
        String developerToken;
        String viewerToken;
        UUID tenantA;
        UUID app;
    }

    private record Release(UUID id, String ref) {}

    private World world(String slug) {
        World world = new World();
        world.org = createOrg("Ring " + slug + " Corp");
        world.adminToken = loginOfOrgRoleIn(world.org, slug + "-admin", "ORG_ADMIN");
        world.ownerToken = loginOfOrgRoleIn(world.org, slug + "-owner", "APP_OWNER");
        world.managerToken = loginOfOrgRoleIn(world.org, slug + "-manager", "RELEASE_MANAGER");
        world.developerToken = loginOfOrgRoleIn(world.org, slug + "-dev", "DEVELOPER");
        world.viewerToken = loginOfOrgRoleIn(world.org, slug + "-viewer", "VIEWER");
        world.tenantA = createTenant(world.adminToken, world.org, "Ring " + slug + " Bank", "crm-" + slug + "-a");
        world.app = createTenantApp(
                world.adminToken,
                world.tenantA,
                "Ring " + slug + " Banking",
                "com." + slug.replace("-", "") + ".banking");
        return world;
    }

    private Release createRelease(World world, String ring, List<UUID> groups, String slug) {
        return createReleaseWithTargets(
                world,
                ring,
                List.of(Map.of(
                        "tenantId", world.tenantA.toString(),
                        "testerGroupIds", groups.stream().map(UUID::toString).toList())),
                slug);
    }

    private Release createReleaseWithTargets(World world, String ring, List<Map<String, Object>> targets, String slug) {
        return createReleaseWithTargets(world.ownerToken, world.managerToken, world.app, ring, targets, null, slug);
    }

    private Release createReleaseWithEnvironment(
            World world, String ring, List<UUID> groups, UUID environmentId, String slug) {
        return createReleaseWithTargets(
                world.ownerToken,
                world.managerToken,
                world.app,
                ring,
                List.of(Map.of(
                        "tenantId", world.tenantA.toString(),
                        "testerGroupIds", groups.stream().map(UUID::toString).toList())),
                environmentId,
                slug);
    }

    private Release createReleaseWithTargets(
            String ownerToken,
            String managerToken,
            UUID applicationId,
            String ring,
            List<Map<String, Object>> targets,
            UUID environmentId,
            String slug) {
        String name = String.format("%d.%d.%d", 1 + versionCounter / 10, versionCounter % 10, 7);
        versionCounter++;
        UUID version = createVersion(ownerToken, applicationId, name);
        UUID artifact = insertArtifact(version, "IOS", versionCounter);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", applicationId.toString());
        body.put("versionId", version.toString());
        body.put("ring", ring);
        if (environmentId != null) {
            body.put("environmentId", environmentId.toString());
        }
        body.put("artifacts", List.of(Map.of("platform", "IOS", "artifactId", artifact.toString())));
        body.put("targets", targets);
        JsonNode created = post(managerToken, "/api/v1/releases", body, 201);
        return new Release(
                UUID.fromString(created.get("id").asText()),
                created.get("releaseRef").asText());
    }

    private JsonNode transition(String bearer, UUID applicationId, UUID releaseId, String to, int expected) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", applicationId.toString());
        body.put("to", to);
        return post(bearer, "/api/v1/releases/" + releaseId + "/transitions", body, expected);
    }

    private JsonNode createRing(
            String token,
            UUID organizationId,
            String code,
            String name,
            int position,
            Map<String, Object> eligibility,
            String approvalPolicy,
            String promotionPolicy,
            int expected) {
        return post(
                token,
                "/api/v1/rings",
                ringBody(organizationId, code, name, position, eligibility, approvalPolicy, promotionPolicy),
                expected);
    }

    private static Map<String, Object> ringBody(
            UUID organizationId,
            String code,
            String name,
            int position,
            Map<String, Object> eligibility,
            String approvalPolicy,
            String promotionPolicy) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("organizationId", organizationId.toString());
        body.put("code", code);
        body.put("name", name);
        body.put("position", position);
        if (eligibility != null) {
            body.put("eligibility", eligibility);
        }
        if (approvalPolicy != null) {
            body.put("approvalPolicy", approvalPolicy);
        }
        if (promotionPolicy != null) {
            body.put("promotionPolicy", promotionPolicy);
        }
        return body;
    }

    private JsonNode createDefinition(
            String token,
            UUID organizationId,
            String gateType,
            String ring,
            Boolean required,
            Map<String, Object> config,
            int expected) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("organizationId", organizationId.toString());
        body.put("gateType", gateType);
        if (ring != null) {
            body.put("ring", ring);
        }
        if (required != null) {
            body.put("required", required);
        }
        if (config != null) {
            body.put("config", config);
        }
        return post(token, "/api/v1/gate-definitions", body, expected);
    }

    private JsonNode createWorkflow(String token, UUID organizationId, String name, String ring, String role) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("role", role);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("organizationId", organizationId.toString());
        body.put("name", name);
        if (ring != null) {
            body.put("ring", ring);
        }
        body.put("steps", List.of(step));
        return post(token, "/api/v1/approval-workflows", body, 201);
    }

    private JsonNode createEnvironment(
            String token, UUID organizationId, String code, String name, String approvalPolicy) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("organizationId", organizationId.toString());
        body.put("code", code);
        body.put("name", name);
        body.put("position", 9);
        body.put("approvalPolicy", approvalPolicy);
        return post(token, "/api/v1/environments", body, 201);
    }

    private UUID createGroup(String token, UUID tenantId, String name) {
        return UUID.fromString(
                post(token, "/api/v1/tester-groups", Map.of("tenantId", tenantId.toString(), "name", name), 201)
                        .get("id")
                        .asText());
    }

    private void addMember(String token, UUID groupId, UUID testerId) {
        post(token, "/api/v1/tester-groups/" + groupId + "/members", Map.of("testerId", testerId.toString()), 201);
    }

    private UUID createTester(String token, UUID organizationId, String email, String fullName) {
        JsonNode created = post(
                token,
                "/api/v1/testers",
                Map.of("organizationId", organizationId.toString(), "email", email, "fullName", fullName),
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

    private void linkTesterToTenant(String token, UUID tenantId, UUID testerId) {
        post(token, "/api/v1/tenants/" + tenantId + "/testers", Map.of("testerId", testerId.toString()), 201);
    }

    private UUID orgRingIdOf(UUID organizationId, String code) {
        return jdbc.queryForObject(
                "SELECT id FROM rings WHERE organization_id = ? AND code = ?", UUID.class, organizationId, code);
    }

    private UUID systemRingIdOf(String code) {
        return jdbc.queryForObject("SELECT id FROM rings WHERE organization_id IS NULL AND code = ?", UUID.class, code);
    }

    private static String ringUri(UUID organizationId, UUID ringId) {
        return "/api/v1/rings/" + ringId + "?organizationId=" + organizationId;
    }

    private UUID createVersion(String token, UUID applicationId, String versionName) {
        return UUID.fromString(post(
                        token,
                        "/api/v1/applications/" + applicationId + "/versions",
                        Map.of("versionName", versionName),
                        201)
                .get("id")
                .asText());
    }

    /** Inserts an artifact row directly (READY) — the release fixture's binary-free shortcut. */
    private UUID insertArtifact(UUID versionId, String platform, long build) {
        UUID artifactId = UUID.randomUUID();
        Map<String, Object> owner =
                jdbc.queryForMap("SELECT organization_id, tenant_id FROM versions WHERE id = ?", versionId);
        String extension = "ANDROID".equals(platform) ? "apk" : "ipa";
        jdbc.update(
                "INSERT INTO artifacts (id, version_id, platform, file_name, storage_key, sha256, size, "
                        + "build_number, status, organization_id, tenant_id) "
                        + "VALUES (?, ?, ?::platform, ?, ?, ?, ?, ?, 'READY'::artifact_status, ?, ?)",
                artifactId,
                versionId,
                platform,
                platform.toLowerCase(Locale.ROOT) + "-" + build + "." + extension,
                "fixture/" + artifactId + "/artifact",
                "a1".repeat(32),
                1024L,
                build,
                owner.get("organization_id"),
                owner.get("tenant_id"));
        return artifactId;
    }

    /** An ORGANIZATION-shared application both target tenants hold (the two-target fixtures). */
    private UUID createSharedApp(
            String token, UUID organizationId, UUID tenantA, UUID tenantB, String name, String bundleId) {
        Map<String, Object> body = new HashMap<>();
        body.put("ownershipScope", "ORGANIZATION");
        body.put("organizationId", organizationId.toString());
        body.put("name", name);
        body.put("platforms", List.of("IOS", "ANDROID"));
        body.put("bundleId", bundleId);
        body.put("packageName", bundleId);
        UUID appId = UUID.fromString(
                post(token, "/api/v1/applications", body, 201).get("id").asText());
        post(token, "/api/v1/applications/" + appId + "/tenants/" + tenantA, Map.of(), 201);
        post(token, "/api/v1/applications/" + appId + "/tenants/" + tenantB, Map.of(), 201);
        return appId;
    }

    private UUID createTenantApp(String token, UUID tenantId, String name, String bundleId) {
        Map<String, Object> body = new HashMap<>();
        body.put("tenantId", tenantId.toString());
        body.put("name", name);
        body.put("platforms", List.of("IOS", "ANDROID"));
        body.put("bundleId", bundleId);
        body.put("packageName", bundleId);
        return UUID.fromString(
                post(token, "/api/v1/applications", body, 201).get("id").asText());
    }

    private UUID createTenant(String token, UUID organizationId, String name, String externalId) {
        return UUID.fromString(post(
                        token,
                        "/api/v1/tenants",
                        Map.of("organizationId", organizationId.toString(), "name", name, "externalId", externalId),
                        201)
                .get("id")
                .asText());
    }

    /** Creates one organization per test; repeated calls for the same name are a fixture bug. */
    private UUID createOrg(String orgName) {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        return UUID.fromString(post(devToken, "/api/v1/organizations", Map.of("name", orgName), 201)
                .get("id")
                .asText());
    }

    /** Registers a user with the given role into an EXISTING organization; returns their token. */
    private String loginOfOrgRoleIn(UUID orgId, String slug, String role) {
        String email = slug + "@example.com";
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
                        "Ring " + role))
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

    private JsonNode patch(String bearer, String uri, Map<String, Object> body, int expectedStatus) {
        return exchange(uri, bearer, "PATCH", body).expect(expectedStatus);
    }

    private JsonNode put(String bearer, String uri, Map<String, Object> body, int expectedStatus) {
        return exchange(uri, bearer, "PUT", body).expect(expectedStatus);
    }

    private JsonNode get(String bearer, String uri, int expectedStatus) {
        return exchange(uri, bearer, "GET", null).expect(expectedStatus);
    }

    private Recording postRaw(String bearer, String uri, Map<String, Object> body) {
        return exchange(uri, bearer, "POST", body);
    }

    private Recording patchRaw(String bearer, String uri, Map<String, Object> body) {
        return exchange(uri, bearer, "PATCH", body);
    }

    private Recording getRaw(String bearer, String uri) {
        return exchange(uri, bearer, "GET", null);
    }

    private Recording deleteRaw(String bearer, String uri) {
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
                    bytes.length == 0 ? mapper.createObjectNode() : mapper.readTree(bytes));
        });
    }

    /** One recorded exchange: the status beside the parsed body (the suite's assertions). */
    private record Recording(int status, JsonNode body) {

        JsonNode expect(int expected) {
            assertThat(status).as("HTTP status for %s", body.toString()).isEqualTo(expected);
            return body;
        }
    }
}
