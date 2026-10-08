package com.shipperdog.api.gate;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.AuditCapture;
import java.util.HashMap;
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
 * End-to-end acceptance flow for the quality gates framework (issue
 * #42, S10-03, FR-026, PRD 32/33): the definition surface (AC1 — the
 * FR-026 catalog's expected-result schemas, the ring attachment, the
 * duplicate authorities), the result ingestion (AC2 — pass/fail with
 * evidence and submitter, latest-wins-with-history idempotency, the
 * open vocabulary), the blocking semantics and the override path
 * (AC3 — a FAILED required gate blocks the VALIDATING to READY
 * crossing until passed or overridden; the override is privileged,
 * reasoned, audited, and surfaced), the metric-backed fail-closed
 * model (AC4 — crash-free and critical-bugs gates resolve from
 * analytics at evaluation time; no data blocks with the distinct
 * NO_DATA state), the dashboard feed (AC5 — the §32 block's per-gate
 * states with evidence links), and the audit discipline (AC6 —
 * submission, pass, fail, and override events).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
@Import(AuditCapture.class)
class GateIntegrationTest {

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
    }

    // -----------------------------------------------------------------------
    // AC1: the definition surface — the catalog as organizational data
    // -----------------------------------------------------------------------

    @Test
    void definitionsCreateListReadAndUpdateWithSchemaValidation() {
        World world = world("gate-def");
        JsonNode coverage = createDefinition(
                world.managerToken, world.org, "coverage-threshold", null, true, Map.of("threshold", 80), 201);
        assertThat(coverage.get("gateType").asText()).isEqualTo("coverage-threshold");
        assertThat(coverage.get("schema").asText()).isEqualTo("NUMERIC_MIN");
        assertThat(coverage.get("required").asBoolean()).isTrue();
        assertThat(coverage.get("ring").isNull()).isTrue();
        assertThat(coverage.get("config").get("threshold").asDouble()).isEqualTo(80.0);

        // The type normalizes to the canonical lowercase kebab.
        JsonNode sast = createDefinition(world.managerToken, world.org, " SAST ", null, null, null, 201);
        assertThat(sast.get("gateType").asText()).isEqualTo("sast");
        assertThat(sast.get("schema").asText()).isEqualTo("PASS_FAIL");

        // The ring attachment resolves and renders (PRD 33).
        JsonNode onQa = createDefinition(world.managerToken, world.org, "unit-tests", "qa", true, null, 201);
        assertThat(onQa.get("ring").asText()).isEqualTo("qa");

        // The listing: the whole catalog, type order, paginated shape.
        JsonNode listed = get(world.managerToken, "/api/v1/gate-definitions?organizationId=" + world.org, 200);
        List<String> types = new java.util.ArrayList<>();
        listed.get("items").forEach(item -> types.add(item.get("gateType").asText()));
        assertThat(types).containsExactly("coverage-threshold", "sast", "unit-tests");
        assertThat(listed.has("next_cursor")).isFalse();

        // The ring filter narrows to the attachment.
        JsonNode qaOnly =
                get(world.managerToken, "/api/v1/gate-definitions?organizationId=" + world.org + "&ring=qa", 200);
        assertThat(qaOnly.get("items").size()).isEqualTo(1);
        assertThat(qaOnly.get("items").get(0).get("gateType").asText()).isEqualTo("unit-tests");

        // The detail read; a foreign organization's id conceals.
        JsonNode detail = get(
                world.managerToken,
                "/api/v1/gate-definitions/" + onQa.get("id").asText() + "?organizationId=" + world.org,
                200);
        assertThat(detail.get("gateType").asText()).isEqualTo("unit-tests");
        JsonNode foreign = get(
                world.managerToken,
                "/api/v1/gate-definitions/" + onQa.get("id").asText() + "?organizationId=" + world.otherOrg,
                404);
        assertThat(foreign.get("code").asText()).isEqualTo("ORGANIZATION_NOT_FOUND");

        // The update: config and required move; the ring detaches.
        Map<String, Object> edit = new LinkedHashMap<>();
        edit.put("required", false);
        edit.put("config", Map.of("threshold", 85));
        JsonNode updated = put(
                world.managerToken,
                "/api/v1/gate-definitions/" + coverage.get("id").asText() + "?organizationId=" + world.org,
                edit,
                200);
        assertThat(updated.get("required").asBoolean()).isFalse();
        assertThat(updated.get("config").get("threshold").asDouble()).isEqualTo(85.0);

        // The config schema validation: a threshold outside the
        // percentage range, a pass/fail gate carrying config, a
        // numeric gate missing its threshold (an EMPTY config on a
        // pass/fail gate is the same as none — legitimate).
        createDefinition(world.managerToken, world.org, "dependency-scan", null, true, Map.of("threshold", 5), 400);
        createDefinition(world.managerToken, world.org, "integration-tests", null, true, Map.of("max", 1), 400);
        createDefinition(world.managerToken, world.org, "open-critical-bugs", null, true, Map.of("threshold", 3), 400);
        createDefinition(world.managerToken, world.org, "mobile-security-scan", "uat", true, Map.of("max", 1), 400);
        createDefinition(world.managerToken, world.org, "crash-free-threshold", null, true, Map.of("max", 2), 400);

        // An integration-defined type of the open vocabulary defaults
        // to binary reporting — no rows in a platform catalog needed.
        JsonNode custom = createDefinition(world.managerToken, world.org, "custom-scan", null, true, null, 201);
        assertThat(custom.get("schema").asText()).isEqualTo("PASS_FAIL");
        assertThat(custom.get("metricBacked").asBoolean()).isFalse();

        // A malformed type shape is the 400 naming the field.
        createDefinition(world.managerToken, world.org, "Not A Type", null, true, null, 400);
        createDefinition(world.managerToken, world.org, "unit-tests", "no-such-ring", true, null, 400);

        // The duplicate authorities: org-wide and ring-attached.
        createDefinition(world.managerToken, world.org, "coverage-threshold", null, true, Map.of("threshold", 60), 409);
        createDefinition(world.managerToken, world.org, "unit-tests", "qa", true, null, 409);
        createDefinition(world.managerToken, world.org, "unit-tests", "uat", true, null, 201);

        // The audits of the definition surface.
        assertThat(audit.eventsOfType(AuditEventType.GATE_DEFINITION_CREATED).size())
                .isEqualTo(5);
        assertThat(audit.eventsOfType(AuditEventType.GATE_DEFINITION_UPDATED).size())
                .isEqualTo(1);
    }

    @Test
    void definitionRoutesAreRoleGated() {
        World world = world("gate-authz");
        createDefinition(world.managerToken, world.org, "sast", null, true, null, 201);
        // The read is every governance persona's.
        assertThat(get(world.viewerToken, "/api/v1/gate-definitions?organizationId=" + world.org, 200)
                        .get("items")
                        .size())
                .isEqualTo(1);
        // The write is the manage personas' — a viewer is refused,
        // the organization-grain denial concealing as the standard
        // 404 ORGANIZATION_NOT_FOUND shape.
        createDefinition(world.viewerToken, world.org, "unit-tests", null, true, null, 404);
        // An unknown organization conceals.
        get(world.managerToken, "/api/v1/gate-definitions?organizationId=" + UUID.randomUUID(), 404);
    }

    // -----------------------------------------------------------------------
    // AC2: the result ingestion — external CI submits with evidence
    // -----------------------------------------------------------------------

    @Test
    void resultsRecordWithEvidenceAndLatestWinsHistory() {
        World world = world("gate-result");
        Release release = releaseAtValidating(world);
        JsonNode first = submit(world.managerToken, world.app, release, "unit-tests", "pass", EVIDENCE, null, 200);
        assertThat(first.get("status").asText()).isEqualTo("PASSED");
        assertThat(first.get("evidenceUrl").asText()).isEqualTo(EVIDENCE);
        assertThat(first.get("source").asText()).isEqualTo("SUBMITTED");
        assertThat(first.get("required").asBoolean()).isFalse();

        // The re-run flips the outcome: latest wins, history keeps both.
        JsonNode second = submit(world.managerToken, world.app, release, "unit-tests", "fail", EVIDENCE_ALT, null, 200);
        assertThat(second.get("status").asText()).isEqualTo("FAILED");
        assertThat(second.get("history").size()).isEqualTo(2);
        assertThat(second.get("history").get(0).get("outcome").asText()).isEqualTo("FAILED");
        assertThat(second.get("history").get(1).get("outcome").asText()).isEqualTo("PASSED");

        // The numeric gates carry their observed value beside the
        // outcome; the pass/fail kinds refuse one.
        JsonNode coverage =
                submit(world.managerToken, world.app, release, "coverage-threshold", "pass", EVIDENCE, 87.5, 200);
        assertThat(coverage.get("observedValue").asDouble()).isEqualTo(87.5);
        submit(world.managerToken, world.app, release, "sast", "pass", EVIDENCE, 12.0, 422);
        submit(world.managerToken, world.app, release, "crash-free-threshold", "pass", EVIDENCE, 99.9, 422);

        // The evidence contract: mandatory, an http(s) link.
        submit(world.managerToken, world.app, release, "sast", "pass", null, null, 422);
        submit(world.managerToken, world.app, release, "sast", "pass", "ftp://example.com/x", null, 422);
        submit(world.managerToken, world.app, release, "sast", "maybe", EVIDENCE, null, 400);

        // The open vocabulary: an integration-defined kind reports
        // like any built-in (binary, no definition needed).
        JsonNode custom = submit(world.managerToken, world.app, release, "custom-scan", "pass", EVIDENCE, null, 200);
        assertThat(custom.get("schema").asText()).isEqualTo("PASS_FAIL");
        assertThat(custom.get("attached").asBoolean()).isFalse();

        // The audits: one event per recorded result, the outcome
        // member carrying pass and fail (AC6). The refused
        // submissions (no evidence, wrong shape, metric-backed)
        // record nothing: 2x unit-tests, coverage, custom.
        assertThat(audit.eventsOfType(AuditEventType.RELEASE_GATE_RESULT_RECORDED).stream()
                        .filter(event -> "SUBMITTED".equals(event.detail().get("source")))
                        .count())
                .isEqualTo(4);
    }

    @Test
    void resultSurfaceConcealsForeignAndUnknownReleases() {
        World world = world("gate-conceal");
        Release release = releaseAtValidating(world);
        // A release of ANOTHER organization's application, addressed
        // through the caller's own application: the service-level
        // concealed miss, byte-identical to an unknown id.
        Release foreignRelease =
                releaseAtValidatingFor(world.otherOwnerToken, world.otherOwnerToken, world.otherApp, world.otherTenant);
        JsonNode foreign = submit(world.managerToken, world.app, foreignRelease, "sast", "pass", EVIDENCE, null, 404);
        JsonNode unknown = submit(
                world.managerToken,
                world.app,
                new Release(UUID.randomUUID(), "R-0"),
                "sast",
                "pass",
                EVIDENCE,
                null,
                404);
        assertThat(foreign.get("code").asText()).isEqualTo("RELEASE_NOT_FOUND");
        assertThat(problemOf(foreign)).isEqualTo(problemOf(unknown));
        // The application-target denial (the manager holds no role at
        // the foreign application's organization) conceals at the
        // permission check — the APPLICATION_NOT_FOUND shape.
        submit(world.managerToken, world.otherApp, release, "sast", "pass", EVIDENCE, null, 404);
        // A viewer reads the gates but cannot submit (the manage
        // persona's surface); the @PreAuthorize denial conceals at
        // the application target (the standard 404 shape).
        get(world.viewerToken, "/api/v1/releases/" + release.id() + "/gates?applicationId=" + world.app, 200);
        submit(world.viewerToken, world.app, release, "sast", "pass", EVIDENCE, null, 404);
    }

    // -----------------------------------------------------------------------
    // AC3: the blocking semantics and the override path
    // -----------------------------------------------------------------------

    @Test
    void failedRequiredGateBlocksUntilPassedOrOverridden() {
        World world = world("gate-block");
        createDefinition(world.managerToken, world.org, "unit-tests", "qa", true, null, 201);
        createDefinition(world.managerToken, world.org, "sast", "qa", true, null, 201);
        Release release = releaseAtValidating(world);

        // Nothing reported: the crossing is refused naming the gates.
        JsonNode blocked = transition(world.managerToken, world, release, "READY", 422);
        assertThat(blocked.get("code").asText()).isEqualTo("RELEASE_TRANSITION_BLOCKED");
        String denial = blocked.get("errors").get("ReleaseGatePolicy").asText();
        assertThat(denial).contains("sast").contains("unit-tests").contains("awaiting a result");

        // One passes, one fails: still blocked, the failure named.
        submit(world.managerToken, world.app, release, "unit-tests", "pass", EVIDENCE, null, 200);
        submit(world.managerToken, world.app, release, "sast", "fail", EVIDENCE_ALT, null, 200);
        JsonNode stillBlocked = transition(world.managerToken, world, release, "READY", 422);
        assertThat(stillBlocked.get("errors").get("ReleaseGatePolicy").asText())
                .contains("sast")
                .contains("failed")
                .contains(EVIDENCE_ALT);

        // The dashboard renders the states with the evidence link
        // (AC5): FAILED with evidence, PASSED beside it.
        JsonNode gates = gatesOf(world, release);
        assertThat(gates.get("gates").size()).isEqualTo(2);
        JsonNode sast = gateOf(gates, "sast");
        assertThat(sast.get("status").asText()).isEqualTo("FAILED");
        assertThat(sast.get("evidenceUrl").asText()).isEqualTo(EVIDENCE_ALT);
        assertThat(gateOf(gates, "unit-tests").get("status").asText()).isEqualTo("PASSED");

        // The override: privileged, reasoned, audited — and the
        // crossing then permits. A developer (no override authority)
        // is refused first — the @PreAuthorize denial concealing at
        // the application target.
        override(world.developerToken, world.app, release, "sast", "risk accepted for pilot", 404);
        JsonNode overridden = override(world.managerToken, world.app, release, "sast", "risk accepted for pilot", 200);
        assertThat(overridden.get("overridden").asBoolean()).isTrue();
        assertThat(overridden.get("overriddenReason").asText()).isEqualTo("risk accepted for pilot");
        assertThat(overridden.get("status").asText()).isEqualTo("FAILED");

        // The override audit (AC6).
        assertThat(audit.eventsOfType(AuditEventType.RELEASE_GATE_OVERRIDDEN).size())
                .isEqualTo(1);

        // The crossing permits with the override recorded.
        transition(world.managerToken, world, release, "READY", 200);

        // The override discipline: once, and only on a blocking state.
        override(world.managerToken, world.app, release, "sast", "again", 409);
        submit(world.managerToken, world.app, release, "unit-tests", "fail", EVIDENCE_ALT, null, 200);
        // The gates guard the VALIDATING to READY edge only: later
        // crossings (the approval moment) are the approval engine's.
        transition(world.managerToken, world, release, "APPROVAL_PENDING", 200);
        // A FAILED gate stays overridable wherever the release sits
        // (its state is the rule, not the release's status) — and a
        // used override never moves.
        override(world.managerToken, world.app, release, "unit-tests", "late", 200);
        override(world.managerToken, world.app, release, "unit-tests", "twice", 409);
    }

    @Test
    void nonRequiredGatesAndUnconfiguredOrganizationsNeverBlock() {
        World world = world("gate-open");
        // A non-required definition records for visibility only.
        createDefinition(world.managerToken, world.org, "sast", "qa", false, null, 201);
        Release release = releaseAtValidating(world);
        submit(world.managerToken, world.app, release, "sast", "fail", EVIDENCE_ALT, null, 200);
        transition(world.managerToken, world, release, "READY", 200);

        // An organization that defines nothing keeps the ungated
        // machine (FR-026's configurability).
        World bare = world("gate-bare");
        Release ungated = releaseAtValidating(bare);
        transition(bare.managerToken, bare, ungated, "READY", 200);

        // The ring scoping (PRD 33): a gate attached to UAT does not
        // evaluate for a QA release's VALIDATING to READY crossing.
        createDefinition(world.managerToken, world.org, "open-critical-bugs", "uat", true, Map.of("max", 0), 201);
        Release next = releaseAtValidating(world);
        submit(world.managerToken, world.app, next, "sast", "pass", EVIDENCE, null, 200);
        transition(world.managerToken, world, next, "READY", 200);
    }

    // -----------------------------------------------------------------------
    // AC4: metric-backed gates — fail-closed on missing analytics
    // -----------------------------------------------------------------------

    @Test
    void metricBackedGatesFailClosedWithDistinctNoDataState() {
        World world = world("gate-metric");
        createDefinition(
                world.managerToken, world.org, "crash-free-threshold", "qa", true, Map.of("threshold", 99.5), 201);
        Release release = releaseAtValidating(world);

        // No analytics registrant ships (S11-04 pending): the
        // evaluation resolves NO_DATA and blocks fail-closed.
        JsonNode blocked = transition(world.managerToken, world, release, "READY", 422);
        assertThat(blocked.get("errors").get("ReleaseGatePolicy").asText())
                .contains("crash-free-threshold")
                .contains("no analytics data yet");

        // The dashboard renders NO_DATA — distinct from FAILED (the
        // issue's technical note), with the threshold beside it.
        JsonNode gates = gatesOf(world, release);
        JsonNode crashFree = gateOf(gates, "crash-free-threshold");
        assertThat(crashFree.get("status").asText()).isEqualTo("NO_DATA");
        assertThat(crashFree.get("threshold").asDouble()).isEqualTo(99.5);
        assertThat(crashFree.get("schema").asText()).isEqualTo("NUMERIC_MIN");
        assertThat(crashFree.get("metricBacked").asBoolean()).isTrue();

        // The metric evaluation is itself audited (AC6): the NO_DATA
        // record with its fail-closed note.
        assertThat(audit.eventsOfType(AuditEventType.RELEASE_GATE_RESULT_RECORDED).stream()
                        .anyMatch(event -> "NO_DATA".equals(event.detail().get("outcome"))
                                && "METRIC".equals(event.detail().get("source"))))
                .isTrue();

        // The override path opens the fail-closed gate.
        override(world.managerToken, world.app, release, "crash-free-threshold", "telemetry lag accepted", 200);
        transition(world.managerToken, world, release, "READY", 200);
    }

    // -----------------------------------------------------------------------
    // AC5: the dashboard feed — §32's per-gate block
    // -----------------------------------------------------------------------

    @Test
    void gatesViewRendersExpectedSetStatesAndHistory() {
        World world = world("gate-view");
        createDefinition(world.managerToken, world.org, "coverage-threshold", "qa", true, Map.of("threshold", 80), 201);
        Release release = releaseAtValidating(world);

        // Before anything reports: the VALIDATING crossing
        // instantiated the expected row (PENDING — the dashboard's
        // "awaiting result" state), the threshold carried for the
        // "Coverage … (threshold 80%)" render.
        JsonNode before = gatesOf(world, release);
        assertThat(before.get("releaseRef").asText()).isEqualTo(release.ref());
        assertThat(before.get("ring").asText()).isEqualTo("qa");
        JsonNode expected = gateOf(before, "coverage-threshold");
        assertThat(expected.get("status").asText()).isEqualTo("PENDING");
        assertThat(expected.get("required").asBoolean()).isTrue();
        assertThat(expected.get("attached").asBoolean()).isTrue();
        assertThat(expected.get("threshold").asDouble()).isEqualTo(80.0);

        // After a report: the observed value beside the threshold,
        // the evidence link, and the history trail.
        submit(world.managerToken, world.app, release, "coverage-threshold", "pass", EVIDENCE, 87.0, 200);
        JsonNode after = gatesOf(world, release);
        JsonNode reported = gateOf(after, "coverage-threshold");
        assertThat(reported.get("status").asText()).isEqualTo("PASSED");
        assertThat(reported.get("observedValue").asDouble()).isEqualTo(87.0);
        assertThat(reported.get("history").size()).isEqualTo(1);
        assertThat(reported.get("history").get(0).get("evidenceUrl").asText()).isEqualTo(EVIDENCE);
    }

    // -----------------------------------------------------------------------
    // The fixtures and helpers (the workflow suite's standing)
    // -----------------------------------------------------------------------

    private static final String EVIDENCE = "https://ci.internal/run/1/report";
    private static final String EVIDENCE_ALT = "https://ci.internal/run/2/report";

    private static final class World {
        UUID org;
        UUID otherOrg;
        UUID otherApp;
        UUID otherTenant;
        String adminToken;
        String ownerToken;
        String managerToken;
        String developerToken;
        String viewerToken;
        String otherAdminToken;
        String otherOwnerToken;
        UUID tenantA;
        UUID app;
    }

    /** One walked release and its quotable identity. */
    private record Release(UUID id, String ref) {}

    private World world(String slug) {
        World world = new World();
        world.org = createOrg("Gate " + slug + " Corp");
        world.adminToken = loginOfOrgRoleIn(world.org, slug + "-admin", "ORG_ADMIN");
        world.ownerToken = loginOfOrgRoleIn(world.org, slug + "-owner", "APP_OWNER");
        world.managerToken = loginOfOrgRoleIn(world.org, slug + "-manager", "RELEASE_MANAGER");
        world.developerToken = loginOfOrgRoleIn(world.org, slug + "-dev", "DEVELOPER");
        world.viewerToken = loginOfOrgRoleIn(world.org, slug + "-viewer", "VIEWER");
        world.tenantA = createTenant(world.adminToken, world.org, "Gate " + slug + " Bank", "crm-" + slug + "-a");
        world.app = createTenantApp(
                world.adminToken,
                world.tenantA,
                "Gate " + slug + " Banking",
                "com." + slug.replace("-", "") + ".banking");
        world.otherOrg = createOrg("Gate " + slug + " Other Corp");
        world.otherAdminToken = loginOfOrgRoleIn(world.otherOrg, slug + "-other-admin", "ORG_ADMIN");
        world.otherOwnerToken = loginOfOrgRoleIn(world.otherOrg, slug + "-other-owner", "APP_OWNER");
        world.otherTenant =
                createTenant(world.otherAdminToken, world.otherOrg, "Gate Other Bank", "crm-" + slug + "-x");
        world.otherApp = createTenantApp(
                world.otherAdminToken,
                world.otherTenant,
                "Gate " + slug + " Other",
                "com." + slug.replace("-", "") + ".other");
        return world;
    }

    /** A fresh release walked to VALIDATING — the crossing the gate policy owns. */
    private Release releaseAtValidating(World world) {
        return releaseAtValidatingFor(world.ownerToken, world.managerToken, world.app, world.tenantA);
    }

    /** The same walk for any application's tokens (the concealment probes' foreign world). */
    private Release releaseAtValidatingFor(String ownerToken, String managerToken, UUID applicationId, UUID tenantId) {
        String name = String.format("%d.%d.%d", 1 + versionCounter / 10, versionCounter % 10, 7);
        versionCounter++;
        UUID version = createVersion(ownerToken, applicationId, name);
        UUID artifact = insertArtifact(version, "IOS", versionCounter);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", applicationId.toString());
        body.put("versionId", version.toString());
        body.put("ring", "qa");
        body.put("artifacts", List.of(Map.of("platform", "IOS", "artifactId", artifact.toString())));
        body.put("targets", List.of(Map.of("tenantId", tenantId.toString(), "testerGroupIds", List.of())));
        JsonNode created = post(managerToken, "/api/v1/releases", body, 201);
        Release release = new Release(
                UUID.fromString(created.get("id").asText()),
                created.get("releaseRef").asText());
        Map<String, Object> validating = new LinkedHashMap<>();
        validating.put("applicationId", applicationId.toString());
        validating.put("to", "VALIDATING");
        exchange("/api/v1/releases/" + release.id() + "/transitions", managerToken, "POST", validating, null)
                .expect(200);
        return release;
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
        if (required != null) {
            body.put("required", required);
        }
        if (config != null) {
            body.put("config", config);
        }
        if (ring != null) {
            body.put("ring", ring);
        }
        return post(token, "/api/v1/gate-definitions", body, expected);
    }

    private JsonNode submit(
            String token,
            UUID applicationId,
            Release release,
            String gateType,
            String status,
            String evidenceUrl,
            Double observedValue,
            int expected) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", applicationId.toString());
        body.put("status", status);
        if (evidenceUrl != null) {
            body.put("evidenceUrl", evidenceUrl);
        }
        if (observedValue != null) {
            body.put("observedValue", observedValue);
        }
        return post(token, "/api/v1/releases/" + release.id() + "/gates/" + gateType + "/result", body, expected);
    }

    private JsonNode override(
            String token, UUID applicationId, Release release, String gateType, String reason, int expected) {
        return post(
                token,
                "/api/v1/releases/" + release.id() + "/gates/" + gateType + "/override",
                Map.of("applicationId", applicationId.toString(), "reason", reason),
                expected);
    }

    private JsonNode gatesOf(World world, Release release) {
        return get(world.managerToken, "/api/v1/releases/" + release.id() + "/gates?applicationId=" + world.app, 200);
    }

    private static JsonNode gateOf(JsonNode gates, String gateType) {
        for (JsonNode gate : gates.get("gates")) {
            if (gateType.equals(gate.get("gateType").asText())) {
                return gate;
            }
        }
        throw new IllegalStateException("gate '" + gateType + "' not in view");
    }

    private JsonNode transition(String token, World world, Release release, String to, int expected) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", world.app.toString());
        body.put("to", to);
        return exchange("/api/v1/releases/" + release.id() + "/transitions", token, "POST", body, null)
                .expect(expected);
    }

    /** The problem envelope minus the per-request trace id (the concealment-parity comparison). */
    private static Map<String, Object> problemOf(JsonNode problem) {
        Map<String, Object> shape = new LinkedHashMap<>();
        shape.put("type", problem.get("type").asText());
        shape.put("title", problem.get("title").asText());
        shape.put("status", problem.get("status").asInt());
        shape.put("code", problem.get("code").asText());
        shape.put("detail", problem.get("detail").asText());
        return shape;
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
                platform.toLowerCase(java.util.Locale.ROOT) + "-" + build + "." + extension,
                "fixture/" + artifactId + "/artifact",
                "a1".repeat(32),
                1024L,
                build,
                owner.get("organization_id"),
                owner.get("tenant_id"));
        return artifactId;
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
                        "Gate " + role))
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
        return exchange(uri, bearer, "POST", body, null).expect(expectedStatus);
    }

    private JsonNode put(String bearer, String uri, Map<String, Object> body, int expectedStatus) {
        return exchange(uri, bearer, "PUT", body, null).expect(expectedStatus);
    }

    private JsonNode get(String bearer, String uri, int expectedStatus) {
        return exchange(uri, bearer, "GET", null, null).expect(expectedStatus);
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
