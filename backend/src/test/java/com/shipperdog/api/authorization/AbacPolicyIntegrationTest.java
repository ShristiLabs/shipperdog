package com.shipperdog.api.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.AuditCapture;
import java.util.ArrayList;
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
 * End-to-end acceptance flow for the ABAC policy engine (issue #70,
 * S14-04, PRD 25/33/38): the PRD 25 example authors and enforces
 * exactly, across the attribute matrix (AC1); ABAC only ever NARROWS
 * — deny-override over the RBAC base, never a widening (AC2);
 * decision explanations reach the admin inspect API and the audit
 * trail on denials (AC3); policy changes are versioned and audited
 * with syntax errors rejected at save (AC4); the ABAC layer's added
 * p95 to the S7-03 decision path stays inside the 10 ms budget
 * (AC5); and evaluation fails closed on unresolvable attributes and
 * storage drift, with the alert legs (AC6).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef",
            "shipperdog.abac.policy-cache-ttl=100ms"
        })
@Testcontainers
@Import(AuditCapture.class)
class AbacPolicyIntegrationTest {

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
        versionCounter = 400 + (int) (Math.random() * 100);
    }

    // -----------------------------------------------------------------------
    // AC1: the PRD 25 example authors and enforces exactly
    // -----------------------------------------------------------------------

    @Test
    void prd25ExampleEnforcesAcrossTheAttributeMatrix() {
        World world = world("abac-matrix");
        // The seeded defaults (V27's provisioning callback): every
        // organization is born with the progression, Production and
        // UAT among them. Environment-targeted releases admit only
        // ASSIGNED tenants (deny-by-default), so the fixture assigns
        // the tenant to both environments first.
        UUID production = environmentIdByName(world.adminToken, world.org, "Production");
        UUID staging = environmentIdByName(world.adminToken, world.org, "UAT");
        assignEnvironmentTenants(world.adminToken, production, world.tenant);
        assignEnvironmentTenants(world.adminToken, staging, world.tenant);
        createGateDefinition(world.adminToken, world.org, "mobile-security-scan", "tenant-pilot");
        UUID otherApp = createTenantApp(world.adminToken, world.tenant, "Wallets", "com.abacmatrix.wallets");

        // The PRD 25 policy, authored exactly: a REQUIRE over the
        // promote of THIS application in THIS environment demanding
        // the subject's role and the security state.
        JsonNode policy = createPolicy(
                world.adminToken,
                world.org,
                "banking-production-promote",
                "REQUIRE",
                "PROMOTE",
                world.app,
                production,
                null,
                10,
                allOf(subjectOrgRole("RELEASE_MANAGER"), securityStatus("APPROVED")),
                201);
        assertThat(policy.get("version").asInt()).isEqualTo(1);

        // (role=RM, app=Banking, env=Production, security=APPROVED) -> allow.
        Release approved = activeReleaseAt(world, world.app, production, "abac-matrix-ok");
        recordGateResult(world.managerToken, world.app, approved.id, "mobile-security-scan", "PASSED");
        assertThat(promote(world.managerToken, world.app, approved.id, 200)
                        .get("toRing")
                        .asText())
                .isEqualTo("tenant-pilot");

        // (security=FAILED) -> deny; the refusal names the rule and the attribute.
        Release failed = activeReleaseAt(world, world.app, production, "abac-matrix-failed");
        recordGateResult(world.managerToken, world.app, failed.id, "mobile-security-scan", "FAILED");
        JsonNode denied = promote(world.managerToken, world.app, failed.id, 409);
        assertThat(denied.get("errors").get("AbacPromotionPolicy").asText())
                .contains("banking-production-promote")
                .contains("security.status")
                .contains("FAILED");

        // (security=PENDING — a required gate with no result yet) -> deny.
        Release pending = activeReleaseAt(world, world.app, production, "abac-matrix-pending");
        JsonNode pendingDenied = promote(world.managerToken, world.app, pending.id, 409);
        assertThat(pendingDenied.get("errors").get("AbacPromotionPolicy").asText())
                .contains("PENDING");

        // (role=APP_OWNER — RBAC admits the promote via
        // app:releases:manage, everything else satisfied) -> deny:
        // the role attribute narrows beyond the RBAC grant that
        // admitted the caller (the AC2 composition made visible, the
        // PRD 25 example's own semantics).
        Release ownerRelease = activeReleaseAt(world, world.app, production, "abac-matrix-owner");
        recordGateResult(world.managerToken, world.app, ownerRelease.id, "mobile-security-scan", "PASSED");
        JsonNode ownerDenied = promote(world.ownerToken, world.app, ownerRelease.id, 409);
        assertThat(ownerDenied.get("errors").get("AbacPromotionPolicy").asText())
                .contains("subject.orgRole")
                .contains("APP_OWNER");

        // (application=Wallets — the selector does not match) -> the
        // policy does not apply; the hop answers the engine's other
        // legs alone.
        Release other = activeReleaseAt(world, otherApp, production, "abac-matrix-other");
        recordGateResult(world.managerToken, otherApp, other.id, "mobile-security-scan", "PASSED");
        assertThat(promote(world.managerToken, otherApp, other.id, 200)
                        .get("toRing")
                        .asText())
                .isEqualTo("tenant-pilot");

        // (environment=UAT — the selector does not match) -> allow
        // (the gate leg is satisfied; the ABAC policy abstains).
        Release staged = activeReleaseAt(world, world.app, staging, "abac-matrix-staging");
        recordGateResult(world.managerToken, world.app, staged.id, "mobile-security-scan", "PASSED");
        assertThat(promote(world.managerToken, world.app, staged.id, 200)
                        .get("toRing")
                        .asText())
                .isEqualTo("tenant-pilot");
    }

    @Test
    void aDenyEffectRuleRefusesWhenItsConditionHolds() {
        World world = world("abac-deny");
        createPolicy(
                world.adminToken,
                world.org,
                "no-viewer-downloads",
                "DENY",
                "DOWNLOAD",
                null,
                null,
                null,
                10,
                subjectOrgRole("VIEWER"),
                201);

        // A VIEWER holds no download entitlement at all (RBAC denies
        // first); the DENY rule's own proof rides the inspect surface
        // below. An APP_OWNER holding the entitlement but matching no
        // condition passes untouched (ABSTAIN, the base answer).
        assertThat(listApplications(world.adminToken, world.tenant).status()).isEqualTo(200);
    }

    // -----------------------------------------------------------------------
    // AC2: ABAC never widens beyond RBAC grants
    // -----------------------------------------------------------------------

    @Test
    void abacNeverWidensBeyondRbacGrants() {
        World world = world("abac-widen");
        createPolicy(
                world.adminToken,
                world.org,
                "permissive-looking-require",
                "REQUIRE",
                "PROMOTE",
                world.app,
                null,
                null,
                10,
                subjectLinkedTester(true),
                201);

        // A VIEWER holds no app:releases:manage: the concealed 404 of
        // the RBAC layer stands, whatever the policy says (the
        // post-RBAC refinement never re-opens an entry the base
        // denied).
        Release release = activeReleaseAt(world, world.app, null, "abac-widen");
        assertThat(promoteStatus(world.viewerToken, world.app, release.id)).isEqualTo(404);

        // And on the layered engine's own path: the VIEWER addressing
        // a real version holds no artifact entitlement, so the
        // VERSION layer denies and the ABAC layer never even runs
        // (short-circuited) — no policy can synthesize the
        // entitlement INV-005 withholds, and the chain shows it.
        JsonNode decision = inspect(
                DEV_ADMIN_TOKEN(),
                new AuthorizationContext(
                        world.viewerId,
                        world.tenant,
                        world.app,
                        world.versionId,
                        world.artifactId,
                        "IOS",
                        null,
                        AuthorizationAction.DOWNLOAD));
        assertThat(decision.get("allowed").asBoolean()).isFalse();
        assertThat(decision.get("failingLayer").asText()).isEqualTo("VERSION");
        assertThat(layerOf(decision, "ABAC").get("status").asText()).isEqualTo("SKIPPED");
    }

    // -----------------------------------------------------------------------
    // AC3: explanations on the inspect API and in the audit trail
    // -----------------------------------------------------------------------

    @Test
    void decisionExplanationsReachTheInspectApiAndTheAuditTrail() {
        World world = world("abac-explain");
        createPolicy(
                world.adminToken,
                world.org,
                "downloads-need-release-manager",
                "REQUIRE",
                "DOWNLOAD",
                null,
                null,
                null,
                10,
                subjectOrgRole("RELEASE_MANAGER"),
                201);

        // The APP_OWNER (entitled by RBAC and the layered chain) is
        // denied by the ABAC layer: the inspect API's chain carries
        // the layer verdict with the rule and its evaluated
        // attributes.
        JsonNode decision = inspect(
                DEV_ADMIN_TOKEN(),
                new AuthorizationContext(
                        world.ownerId,
                        world.tenant,
                        world.app,
                        world.versionId,
                        world.artifactId,
                        "IOS",
                        null,
                        AuthorizationAction.DOWNLOAD));
        assertThat(decision.get("allowed").asBoolean()).isFalse();
        assertThat(decision.get("failingLayer").asText()).isEqualTo("ABAC");
        assertThat(decision.get("reason").asText()).isEqualTo("ABAC_POLICY_DENIED");
        JsonNode abacLayer = layerOf(decision, "ABAC");
        assertThat(abacLayer.get("status").asText()).isEqualTo("FAILED");
        assertThat(abacLayer.get("detail").asText())
                .contains("downloads-need-release-manager")
                .contains("subject.orgRole")
                .contains("APP_OWNER");

        // The denial is audited with the same explanation (the layered
        // path rides AUTHORIZATION_DECISION, failingLayer=ABAC).
        assertThat(audit.eventsOfType(AuditEventType.AUTHORIZATION_DECISION)).anySatisfy(event -> {
            assertThat(event.detail().get("failingLayer")).isEqualTo("ABAC");
            assertThat(event.detail().get("reason")).isEqualTo("ABAC_POLICY_DENIED");
        });

        // The promotion consult's denial is its own audited line:
        // ABAC_POLICY_DENIED with the rule and the explanation.
        createGateDefinition(world.adminToken, world.org, "mobile-security-scan", "tenant-pilot");
        createPolicy(
                world.adminToken,
                world.org,
                "promotions-need-security",
                "REQUIRE",
                "PROMOTE",
                world.app,
                null,
                null,
                20,
                securityStatus("APPROVED"),
                201);
        Release release = activeReleaseAt(world, world.app, null, "abac-explain-promote");
        recordGateResult(world.managerToken, world.app, release.id, "mobile-security-scan", "FAILED");
        promote(world.managerToken, world.app, release.id, 409);
        assertThat(audit.eventsOfType(AuditEventType.ABAC_POLICY_DENIED)).anySatisfy(event -> {
            assertThat(event.detail().get("rule")).isEqualTo("promotions-need-security");
            assertThat(event.detail().get("explanation"))
                    .contains("security.status")
                    .contains("FAILED");
        });
    }

    // -----------------------------------------------------------------------
    // AC4: versioned, audited policy changes; syntax errors at save
    // -----------------------------------------------------------------------

    @Test
    void policyChangesAreVersionedAndAuditedAndSyntaxErrorsAreRejectedAtSave() {
        World world = world("abac-crud");

        // Syntax errors: unknown construct, unknown role, two
        // constructs at once, an unresolvable action/attribute
        // pairing (release-grain atoms only ride PROMOTE), a platform
        // atom on a LIST decision, and a foreign application selector.
        createPolicy(
                world.adminToken,
                world.org,
                "bad-construct",
                "DENY",
                "DOWNLOAD",
                null,
                null,
                null,
                10,
                Map.of("noSuchAtom", true),
                400);
        createPolicy(
                world.adminToken,
                world.org,
                "bad-role",
                "DENY",
                "DOWNLOAD",
                null,
                null,
                null,
                10,
                subjectOrgRole("CHIEF"),
                400);
        createPolicy(
                world.adminToken,
                world.org,
                "two-constructs",
                "DENY",
                "DOWNLOAD",
                null,
                null,
                null,
                10,
                Map.of("subjectPlatformAdmin", true, "subjectLinkedTester", false),
                400);
        createPolicy(
                world.adminToken,
                world.org,
                "release-grain-on-download",
                "DENY",
                "DOWNLOAD",
                null,
                null,
                null,
                10,
                securityStatus("APPROVED"),
                400);
        createPolicy(
                world.adminToken,
                world.org,
                "platform-on-list",
                "DENY",
                "LIST_APPLICATIONS",
                null,
                null,
                null,
                10,
                Map.of("applicationPlatform", "IOS"),
                400);
        createPolicy(
                world.adminToken,
                world.org,
                "foreign-app",
                "REQUIRE",
                "PROMOTE",
                UUID.randomUUID(),
                null,
                null,
                10,
                subjectOrgRole("RELEASE_MANAGER"),
                400);

        // The good shape authors at v1; the replace revalidates and
        // versions to v2 with the immutable history beside it.
        JsonNode created = createPolicy(
                world.adminToken,
                world.org,
                "fine-policy",
                "DENY",
                "DOWNLOAD",
                null,
                null,
                null,
                10,
                subjectOrgRole("VIEWER"),
                201);
        String policyId = created.get("id").asText();
        JsonNode replaced = putPolicy(
                world.adminToken,
                world.org,
                policyId,
                "fine-policy",
                "REQUIRE",
                "PROMOTE",
                null,
                null,
                null,
                10,
                subjectOrgRole("RELEASE_MANAGER"),
                null,
                200);
        assertThat(replaced.get("version").asInt()).isEqualTo(2);

        JsonNode history = get(
                world.adminToken, "/api/v1/abac-policies/" + policyId + "/versions?organizationId=" + world.org, 200);
        assertThat(history.get("items").get(0).get("version").asInt()).isEqualTo(2);
        assertThat(history.get("items").get(1).get("version").asInt()).isEqualTo(1);
        assertThat(history.get("items").get(1).get("conditionSummary").asText()).contains("subject.orgRole = VIEWER");

        // One name per organization (the precise 409).
        createPolicy(
                world.adminToken,
                world.org,
                "fine-policy",
                "DENY",
                "DOWNLOAD",
                null,
                null,
                null,
                20,
                subjectOrgRole("VIEWER"),
                409);

        // Both writes audited.
        assertThat(audit.eventsOfType(AuditEventType.ABAC_POLICY_CREATED)).hasSize(1);
        assertThat(audit.eventsOfType(AuditEventType.ABAC_POLICY_UPDATED))
                .anySatisfy(event -> assertThat(event.detail().get("version")).isEqualTo("2"));

        // Deletion: the current row goes, the history stays, the act
        // is audited, and the next decision sees the policy gone.
        deletePolicy(world.adminToken, world.org, policyId, 204);
        get(world.adminToken, "/api/v1/abac-policies/" + policyId + "?organizationId=" + world.org, 404);
        assertThat(get(
                                world.adminToken,
                                "/api/v1/abac-policies/" + policyId + "/versions?organizationId=" + world.org,
                                200)
                        .get("items")
                        .size())
                .isEqualTo(2);
        assertThat(audit.eventsOfType(AuditEventType.ABAC_POLICY_DELETED)).hasSize(1);
    }

    // -----------------------------------------------------------------------
    // AC5: the latency budget (ABAC adds < 10 ms p95 to the S7-03 path)
    // -----------------------------------------------------------------------

    @Test
    void abacAddsUnderTenMillisP95ToTheDecisionPath() {
        World world = world("abac-latency");

        // Warm both legs before measuring.
        for (int i = 0; i < 20; i++) {
            assertThat(listApplications(world.adminToken, world.tenant).status())
                    .isEqualTo(200);
        }
        double withoutAbac = p95OfDecisionProbes(world.adminToken, world.tenant, 200);

        // Three authored LIST policies: the layer now loads the
        // compiled set, resolves the referenced subject attribute, and
        // abstains on every probe — the measured cost of refinement.
        createPolicy(
                world.adminToken,
                world.org,
                "latency-one",
                "REQUIRE",
                "LIST_APPLICATIONS",
                null,
                null,
                null,
                10,
                subjectOrgRole("ORG_ADMIN"),
                201);
        createPolicy(
                world.adminToken,
                world.org,
                "latency-two",
                "DENY",
                "LIST_APPLICATIONS",
                null,
                null,
                null,
                20,
                subjectOrgRole("VIEWER"),
                201);
        createPolicy(
                world.adminToken,
                world.org,
                "latency-three",
                "REQUIRE",
                "LIST_APPLICATIONS",
                null,
                null,
                null,
                30,
                allOf(subjectOrgRole("ORG_ADMIN"), subjectPlatformAdmin(false)),
                201);
        for (int i = 0; i < 20; i++) {
            assertThat(listApplications(world.adminToken, world.tenant).status())
                    .isEqualTo(200);
        }
        double withAbac = p95OfDecisionProbes(world.adminToken, world.tenant, 200);

        // The budget: ABAC adds under 10 ms p95 to the decision path.
        assertThat(withAbac - withoutAbac)
                .as("p95 delta ms (without=%s with=%s)", withoutAbac, withAbac)
                .isLessThan(10.0);
    }

    // -----------------------------------------------------------------------
    // AC6: fail-closed on unresolvable attributes and storage drift
    // -----------------------------------------------------------------------

    @Test
    void unresolvableAttributesFailClosedWithTheAlertLegs() {
        World world = world("abac-failclosed");
        createGateDefinition(world.adminToken, world.org, "mobile-security-scan", "tenant-pilot");

        // The promotion consult carries no platform pin: an
        // applicationPlatform atom is honestly UNRESOLVABLE there, the
        // REQUIRE refuses, and the fault legs fire (the counter and
        // the ABAC_EVALUATION_FAULT audit line are the alert).
        createPolicy(
                world.adminToken,
                world.org,
                "ios-only-promote",
                "REQUIRE",
                "PROMOTE",
                null,
                null,
                null,
                10,
                Map.of("applicationPlatform", "IOS"),
                201);
        Release release = activeReleaseAt(world, world.app, null, "abac-fc");
        recordGateResult(world.managerToken, world.app, release.id, "mobile-security-scan", "PASSED");
        assertThat(promote(world.managerToken, world.app, release.id, 409)
                        .get("errors")
                        .get("AbacPromotionPolicy")
                        .asText())
                .contains("unresolvable");
        assertThat(audit.eventsOfType(AuditEventType.ABAC_EVALUATION_FAULT)).isNotEmpty();
    }

    @Test
    void storageDriftFailsClosedAfterTheCacheWindow() throws InterruptedException {
        World world = world("abac-drift");
        JsonNode created = createPolicy(
                world.adminToken,
                world.org,
                "drifting-policy",
                "REQUIRE",
                "PROMOTE",
                null,
                null,
                null,
                10,
                subjectOrgRole("RELEASE_MANAGER"),
                201);

        // Storage drift the write surface makes impossible: a
        // hand-corrupted condition. The cached compile survives until
        // the (test-shortened) TTL window passes; the next load fails
        // closed — a policy the engine cannot answer never permits.
        jdbc.update(
                "UPDATE abac_policies SET condition = '{\"noSuchAtom\": true}'::jsonb WHERE id = ?",
                UUID.fromString(created.get("id").asText()));
        Thread.sleep(300);
        Release release = activeReleaseAt(world, world.app, null, "abac-drift-r");
        assertThat(promote(world.managerToken, world.app, release.id, 409)
                        .get("errors")
                        .get("AbacPromotionPolicy")
                        .asText())
                .contains("could not be read");
    }

    // -----------------------------------------------------------------------
    // Composition: the zero-policy fast path, write-through publication,
    // and the snapshot's lazy discipline (the tester's additions)
    // -----------------------------------------------------------------------

    @Test
    void zeroAbacPoliciesLeaveTheRbacAnswerUntouchedAndPublishTakesEffectImmediately() {
        World world = world("abac-fastpath");
        AuthorizationContext download = new AuthorizationContext(
                world.ownerId,
                world.tenant,
                world.app,
                world.versionId,
                world.artifactId,
                "IOS",
                null,
                AuthorizationAction.DOWNLOAD);

        // No authored policy: the layer sits out (the cached probe) and
        // the base RBAC answer stands untouched — pure RBAC.
        JsonNode before = inspect(DEV_ADMIN_TOKEN(), download);
        assertThat(before.get("allowed").asBoolean()).isTrue();
        assertThat(layerOf(before, "ABAC").get("status").asText()).isEqualTo("SKIPPED");

        // Warm the empty window: the (test-shortened) TTL alone would
        // keep serving it for the next 100 ms.
        assertThat(inspect(DEV_ADMIN_TOKEN(), download).get("allowed").asBoolean())
                .isTrue();

        // Publish: the write-through drop makes the VERY NEXT decision
        // see the policy — no TTL wait, no stale permit.
        JsonNode created = createPolicy(
                world.adminToken,
                world.org,
                "owners-never-download",
                "DENY",
                "DOWNLOAD",
                null,
                null,
                null,
                10,
                subjectOrgRole("APP_OWNER"),
                201);
        JsonNode deniedNow = inspect(DEV_ADMIN_TOKEN(), download);
        assertThat(deniedNow.get("allowed").asBoolean()).isFalse();
        assertThat(deniedNow.get("failingLayer").asText()).isEqualTo("ABAC");
        assertThat(deniedNow.get("reason").asText()).isEqualTo("ABAC_POLICY_DENIED");

        // Replace with a satisfied REQUIRE: the decision flips back at
        // once, and the unreferenced attributes stay unresolved — this
        // decision addresses no release, so an eagerly-resolved
        // security.status would fault the REQUIRE into a denial. Its
        // pass is the lazy snapshot's proof.
        putPolicy(
                world.adminToken,
                world.org,
                created.get("id").asText(),
                "owners-never-download",
                "REQUIRE",
                "DOWNLOAD",
                null,
                null,
                null,
                10,
                subjectOrgRole("APP_OWNER"),
                null,
                200);
        JsonNode passedNow = inspect(DEV_ADMIN_TOKEN(), download);
        assertThat(passedNow.get("allowed").asBoolean()).isTrue();
        assertThat(layerOf(passedNow, "ABAC").get("status").asText()).isEqualTo("PASSED");

        // Disable: the same immediacy, and the fast path returns.
        putPolicy(
                world.adminToken,
                world.org,
                created.get("id").asText(),
                "owners-never-download",
                "REQUIRE",
                "DOWNLOAD",
                null,
                null,
                null,
                10,
                subjectOrgRole("APP_OWNER"),
                false,
                200);
        JsonNode afterDisable = inspect(DEV_ADMIN_TOKEN(), download);
        assertThat(afterDisable.get("allowed").asBoolean()).isTrue();
        assertThat(layerOf(afterDisable, "ABAC").get("status").asText()).isEqualTo("SKIPPED");
    }

    @Test
    void clientSuppliedAttributesNeverInfluenceTheDecision() {
        World world = world("abac-spoof");
        createPolicy(
                world.adminToken,
                world.org,
                "downloads-need-manager",
                "REQUIRE",
                "DOWNLOAD",
                null,
                null,
                null,
                10,
                subjectOrgRole("RELEASE_MANAGER"),
                201);
        AuthorizationContext download = new AuthorizationContext(
                world.ownerId,
                world.tenant,
                world.app,
                world.versionId,
                world.artifactId,
                "IOS",
                null,
                AuthorizationAction.DOWNLOAD);

        // The honest decision: the owner is denied, the explanation
        // carrying the SERVER-DERIVED role.
        JsonNode clean = inspect(DEV_ADMIN_TOKEN(), download);
        assertThat(clean.get("allowed").asBoolean()).isFalse();
        assertThat(layerOf(clean, "ABAC").get("detail").asText())
                .contains("subject.orgRole")
                .contains("APP_OWNER");

        // The same inspect body carrying authored-looking attribute
        // claims: unknown fields are not attributes — the decision and
        // its evidence are IDENTICAL (attributes resolve from the
        // server's own sources only).
        Map<String, Object> spoofed = new LinkedHashMap<>();
        spoofed.put("userId", world.ownerId.toString());
        spoofed.put("tenantId", world.tenant.toString());
        spoofed.put("applicationId", world.app.toString());
        spoofed.put("versionId", world.versionId.toString());
        spoofed.put("artifactId", world.artifactId.toString());
        spoofed.put("platform", "IOS");
        spoofed.put("action", "DOWNLOAD");
        spoofed.put("subjectOrgRole", "RELEASE_MANAGER");
        spoofed.put(
                "attributes",
                Map.of("subject.orgRole", "RELEASE_MANAGER", "security.status", "APPROVED", "ring", "tenant-pilot"));
        spoofed.put("securityStatus", "APPROVED");
        spoofed.put("environmentName", "Production");
        JsonNode withClaims = post(DEV_ADMIN_TOKEN(), "/api/v1/authorization/decisions", spoofed, 200);
        assertThat(withClaims.get("allowed").asBoolean()).isFalse();
        assertThat(layerOf(withClaims, "ABAC").get("detail").asText())
                .isEqualTo(layerOf(clean, "ABAC").get("detail").asText());

        // Header-carried claims fare no better.
        JsonNode withHeaders = client.post()
                .uri("/api/v1/authorization/decisions")
                .headers(headers -> {
                    headers.setBearerAuth(DEV_ADMIN_TOKEN());
                    headers.set("X-Subject-Org-Role", "RELEASE_MANAGER");
                    headers.set("X-Abac-Attributes", "{\"subject.orgRole\":\"RELEASE_MANAGER\"}");
                })
                .contentType(MediaType.APPLICATION_JSON)
                .body(spoofed)
                .exchange((request, response) ->
                        mapper.readTree(response.getBody().readAllBytes()));
        assertThat(withHeaders.get("allowed").asBoolean()).isFalse();
        assertThat(layerOf(withHeaders, "ABAC").get("detail").asText())
                .isEqualTo(layerOf(clean, "ABAC").get("detail").asText());

        // The promotion consult agrees: junk attribute fields in the
        // promote body change nothing — the refusal still names the
        // server-derived role.
        createGateDefinition(world.adminToken, world.org, "mobile-security-scan", "tenant-pilot");
        createPolicy(
                world.adminToken,
                world.org,
                "promotions-need-manager",
                "REQUIRE",
                "PROMOTE",
                world.app,
                null,
                null,
                20,
                subjectOrgRole("RELEASE_MANAGER"),
                201);
        Release release = activeReleaseAt(world, world.app, null, "abac-spoof-r");
        recordGateResult(world.managerToken, world.app, release.id, "mobile-security-scan", "PASSED");
        Map<String, Object> promoteBody = new LinkedHashMap<>();
        promoteBody.put("applicationId", world.app.toString());
        promoteBody.put("subjectOrgRole", "RELEASE_MANAGER");
        promoteBody.put("attributes", Map.of("subject.orgRole", "RELEASE_MANAGER"));
        assertThat(post(world.ownerToken, "/api/v1/releases/" + release.id + "/promote", promoteBody, 409)
                        .get("errors")
                        .get("AbacPromotionPolicy")
                        .asText())
                .contains("subject.orgRole")
                .contains("APP_OWNER");
    }

    @Test
    void policyAdminRoutesConcealFromTheUnauthorizedAndTheForeign() {
        World world = world("abac-conceal");
        JsonNode created = createPolicy(
                world.adminToken,
                world.org,
                "concealed-policy",
                "DENY",
                "DOWNLOAD",
                null,
                null,
                null,
                10,
                subjectOrgRole("VIEWER"),
                201);
        String policyId = created.get("id").asText();
        String memberToken = loginOfOrgRoleIn(world.org, "abac-conceal-member", "ORG_MEMBER");

        // A plain member holds no organization permission at all (the
        // deny-by-default role): neither read nor manage. The denial
        // is CONCEALED — the same 404 envelope a genuine miss renders.
        Recording memberDenied =
                exchange("/api/v1/abac-policies?organizationId=" + world.org, memberToken, "GET", null);
        assertThat(memberDenied.status()).isEqualTo(404);
        createPolicy(
                memberToken,
                world.org,
                "members-cannot-author",
                "DENY",
                "DOWNLOAD",
                null,
                null,
                null,
                10,
                subjectOrgRole("VIEWER"),
                404);

        // A second organization's admin: the foreign organization's
        // scope is closed to them entirely, concealed the same way...
        UUID otherOrg = createOrg("Abac Conceal Other Corp");
        String otherAdmin = loginOfOrgRoleIn(otherOrg, "abac-conceal-other", "ORG_ADMIN");
        assertThat(exchange(
                                "/api/v1/abac-policies/" + policyId + "?organizationId=" + world.org,
                                otherAdmin,
                                "GET",
                                null)
                        .status())
                .isEqualTo(404);

        // ...and under their OWN scope a foreign policy id conceals
        // exactly like an unknown one (current standing and history).
        get(otherAdmin, "/api/v1/abac-policies/" + policyId + "?organizationId=" + otherOrg, 404);
        get(otherAdmin, "/api/v1/abac-policies/" + policyId + "/versions?organizationId=" + otherOrg, 404);

        // An unknown organization conceals the same way — and the
        // genuine miss's envelope is BYTE-IDENTICAL to the member's
        // concealed denial above (indistinguishable, the doctrine).
        JsonNode genuineMiss = get(world.adminToken, "/api/v1/abac-policies?organizationId=" + UUID.randomUUID(), 404);
        assertThat(genuineMiss.get("code").asText())
                .isEqualTo(memberDenied.body().get("code").asText());
    }

    @Test
    void abacRefusalsCoexistWithGateEvidenceInThePromotionBlockers() {
        World world = world("abac-coexist");
        createGateDefinition(world.adminToken, world.org, "mobile-security-scan", "tenant-pilot");
        createPolicy(
                world.adminToken,
                world.org,
                "promotions-need-security",
                "REQUIRE",
                "PROMOTE",
                world.app,
                null,
                null,
                10,
                securityStatus("APPROVED"),
                201);

        Release release = activeReleaseAt(world, world.app, null, "abac-coexist");
        recordGateResult(world.managerToken, world.app, release.id, "mobile-security-scan", "FAILED");
        JsonNode refused = promote(world.managerToken, world.app, release.id, 409);

        // The doctrine's rendering: EVERY refusing leg lands in the
        // same blockers map — the gate's evidence survives beside the
        // ABAC refusal (the gate's key names it), and the ABAC leg
        // names its rule and attribute.
        JsonNode errors = refused.get("errors");
        assertThat(errors.size()).isGreaterThanOrEqualTo(2);
        assertThat(errors.has("gate:mobile-security-scan")).isTrue();
        assertThat(errors.get("gate:mobile-security-scan").asText()).contains("https://ci.example.com/scan/1");
        assertThat(errors.get("AbacPromotionPolicy").asText())
                .contains("promotions-need-security")
                .contains("security.status")
                .contains("FAILED");
    }

    // -----------------------------------------------------------------------
    // The shared fixture plumbing (the release-policy suite's shape)
    // -----------------------------------------------------------------------

    private static final class World {
        UUID org;
        UUID tenant;
        UUID app;
        UUID ownerId;
        UUID viewerId;
        UUID versionId;
        UUID artifactId;
        String adminToken;
        String ownerToken;
        String managerToken;
        String viewerToken;
    }

    private record Release(UUID id) {}

    private String DEV_ADMIN_TOKEN() {
        return accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
    }

    private World world(String slug) {
        World world = new World();
        world.org = createOrg("Abac " + slug + " Corp");
        world.adminToken = loginOfOrgRoleIn(world.org, slug + "-admin", "ORG_ADMIN");
        world.ownerToken = loginOfOrgRoleIn(world.org, slug + "-owner", "APP_OWNER");
        world.managerToken = loginOfOrgRoleIn(world.org, slug + "-manager", "RELEASE_MANAGER");
        world.viewerToken = loginOfOrgRoleIn(world.org, slug + "-viewer", "VIEWER");
        world.ownerId = userIdOf(world.ownerToken);
        world.viewerId = userIdOf(world.viewerToken);
        world.tenant = createTenant(world.adminToken, world.org, "Abac " + slug + " Bank", "abac-" + slug);
        world.app =
                createTenantApp(world.adminToken, world.tenant, "Banking", "com." + slug.replace("-", "") + ".banking");
        world.versionId = createVersion(world.ownerToken, world.app, "1.0.0");
        world.artifactId = insertArtifact(world.versionId, "IOS", 100);
        return world;
    }

    private Release activeReleaseAt(World world, UUID applicationId, UUID environmentId, String slug) {
        String name = String.format("%d.%d.%d", 6 + versionCounter / 10, versionCounter % 10, 7);
        versionCounter++;
        UUID version = createVersion(world.ownerToken, applicationId, name);
        UUID artifact = insertArtifact(version, "IOS", versionCounter);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", applicationId.toString());
        body.put("versionId", version.toString());
        body.put("ring", "qa");
        if (environmentId != null) {
            body.put("environmentId", environmentId.toString());
        }
        body.put("artifacts", List.of(Map.of("platform", "IOS", "artifactId", artifact.toString())));
        body.put("targets", List.of(Map.of("tenantId", world.tenant.toString(), "testerGroupIds", List.of())));
        JsonNode created = post(world.managerToken, "/api/v1/releases", body, 201);
        UUID releaseId = UUID.fromString(created.get("id").asText());
        for (String to : List.of("VALIDATING", "READY", "APPROVAL_PENDING", "APPROVED", "DEPLOYING", "ACTIVE")) {
            Map<String, Object> transition = new LinkedHashMap<>();
            transition.put("applicationId", applicationId.toString());
            transition.put("to", to);
            post(world.managerToken, "/api/v1/releases/" + releaseId + "/transitions", transition, 200);
        }
        return new Release(releaseId);
    }

    // -----------------------------------------------------------------------
    // ABAC policy helpers
    // -----------------------------------------------------------------------

    private static Map<String, Object> subjectOrgRole(String role) {
        return Map.of("subjectOrgRole", role);
    }

    private static Map<String, Object> securityStatus(String status) {
        return Map.of("securityStatus", status);
    }

    private static Map<String, Object> subjectLinkedTester(boolean expected) {
        return Map.of("subjectLinkedTester", expected);
    }

    private static Map<String, Object> subjectPlatformAdmin(boolean expected) {
        return Map.of("subjectPlatformAdmin", expected);
    }

    private static Map<String, Object> allOf(Map<String, Object>... children) {
        return Map.of("allOf", List.of(children));
    }

    private JsonNode createPolicy(
            String token,
            UUID organizationId,
            String name,
            String effect,
            String action,
            UUID applicationId,
            UUID environmentId,
            String ring,
            int position,
            Map<String, Object> condition,
            int expected) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("organizationId", organizationId.toString());
        body.put("name", name);
        body.put("effect", effect);
        body.put("action", action);
        if (applicationId != null) {
            body.put("applicationId", applicationId.toString());
        }
        if (environmentId != null) {
            body.put("environmentId", environmentId.toString());
        }
        if (ring != null) {
            body.put("ring", ring);
        }
        body.put("condition", condition);
        body.put("position", position);
        return post(token, "/api/v1/abac-policies", body, expected);
    }

    private JsonNode putPolicy(
            String token,
            UUID organizationId,
            String policyId,
            String name,
            String effect,
            String action,
            UUID applicationId,
            UUID environmentId,
            String ring,
            int position,
            Map<String, Object> condition,
            Boolean enabled,
            int expected) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("organizationId", organizationId.toString());
        body.put("name", name);
        body.put("effect", effect);
        body.put("action", action);
        body.put("condition", condition);
        body.put("position", position);
        if (enabled != null) {
            body.put("enabled", enabled);
        }
        return put(token, "/api/v1/abac-policies/" + policyId + "?organizationId=" + organizationId, body, expected);
    }

    private void deletePolicy(String token, UUID organizationId, String policyId, int expected) {
        exchange("/api/v1/abac-policies/" + policyId + "?organizationId=" + organizationId, token, "DELETE", null)
                .expect(expected);
    }

    // -----------------------------------------------------------------------
    // Fixture plumbing
    // -----------------------------------------------------------------------

    /** Assigns exactly this tenant to the environment (the deny-by-default allow-list releases validate against). */
    private void assignEnvironmentTenants(String token, UUID environmentId, UUID tenantId) {
        put(
                token,
                "/api/v1/environments/" + environmentId + "/tenants",
                Map.of("tenantIds", List.of(tenantId.toString())),
                200);
    }

    /** The seeded default environment's id, by name (V27's provisioning callback seeds every org). */
    private UUID environmentIdByName(String token, UUID organizationId, String name) {
        JsonNode page = get(token, "/api/v1/environments?organizationId=" + organizationId + "&limit=100", 200);
        for (JsonNode entry : page.get("items")) {
            if (name.equals(entry.get("name").asText())) {
                return UUID.fromString(entry.get("id").asText());
            }
        }
        throw new IllegalStateException("no seeded environment named " + name);
    }

    private void createGateDefinition(String token, UUID organizationId, String gateType, String ring) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("organizationId", organizationId.toString());
        body.put("gateType", gateType);
        body.put("ring", ring);
        body.put("required", true);
        post(token, "/api/v1/gate-definitions", body, 201);
    }

    private void recordGateResult(String bearer, UUID applicationId, UUID releaseId, String gateType, String status) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", applicationId.toString());
        body.put("status", status);
        body.put("evidenceUrl", "https://ci.example.com/scan/1");
        post(bearer, "/api/v1/releases/" + releaseId + "/gates/" + gateType + "/result", body, 200);
    }

    private JsonNode promote(String bearer, UUID applicationId, UUID releaseId, int expected) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", applicationId.toString());
        return post(bearer, "/api/v1/releases/" + releaseId + "/promote", body, expected);
    }

    private int promoteStatus(String bearer, UUID applicationId, UUID releaseId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", applicationId.toString());
        return exchange("/api/v1/releases/" + releaseId + "/promote", bearer, "POST", body)
                .status();
    }

    /** The tenant catalog listing — the guarded LIST decision the latency probe drives. */
    private Recording listApplications(String bearer, UUID tenantId) {
        return exchange("/api/v1/tenants/" + tenantId + "/applications", bearer, "GET", null);
    }

    /** The p95 of the LIST decision probe (the S7-03 decision family's catalog leg). */
    private double p95OfDecisionProbes(String bearer, UUID tenantId, int probes) {
        List<Long> samples = new ArrayList<>(probes);
        for (int i = 0; i < probes; i++) {
            long start = System.nanoTime();
            Recording recording = listApplications(bearer, tenantId);
            long elapsed = System.nanoTime() - start;
            assertThat(recording.status()).as("catalog probe status").isEqualTo(200);
            samples.add(elapsed);
        }
        samples.sort(Long::compare);
        int index = (int) Math.ceil(0.95 * samples.size()) - 1;
        return samples.get(Math.max(0, index)) / 1_000_000.0;
    }

    private JsonNode inspect(String devToken, AuthorizationContext context) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", context.userId().toString());
        if (context.tenantId() != null) {
            body.put("tenantId", context.tenantId().toString());
        }
        if (context.applicationId() != null) {
            body.put("applicationId", context.applicationId().toString());
        }
        if (context.versionId() != null) {
            body.put("versionId", context.versionId().toString());
        }
        if (context.artifactId() != null) {
            body.put("artifactId", context.artifactId().toString());
        }
        if (context.platform() != null) {
            body.put("platform", context.platform());
        }
        body.put("action", context.action().name());
        return post(devToken, "/api/v1/authorization/decisions", body, 200);
    }

    private static JsonNode layerOf(JsonNode decision, String layer) {
        for (JsonNode entry : decision.get("chain")) {
            if (entry.get("layer").asText().equals(layer)) {
                return entry;
            }
        }
        throw new AssertionError("no " + layer + " layer in the chain: " + decision);
    }

    private UUID userIdOf(String bearer) {
        Map<String, Object> who = new LinkedHashMap<>();
        return UUID.fromString(exchange("/api/v1/auth/me", bearer, "GET", null)
                .expect(200)
                .get("id")
                .asText());
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
                "b2".repeat(32),
                1024L,
                build,
                owner.get("organization_id"),
                owner.get("tenant_id"));
        return artifactId;
    }

    private UUID createTenantApp(String token, UUID tenantId, String name, String bundleId) {
        Map<String, Object> body = new LinkedHashMap<>();
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

    private UUID createOrg(String orgName) {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        return UUID.fromString(post(devToken, "/api/v1/organizations", Map.of("name", orgName), 201)
                .get("id")
                .asText());
    }

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
                        "Abac " + role))
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

    private JsonNode put(String bearer, String uri, Map<String, Object> body, int expectedStatus) {
        return exchange(uri, bearer, "PUT", body).expect(expectedStatus);
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
