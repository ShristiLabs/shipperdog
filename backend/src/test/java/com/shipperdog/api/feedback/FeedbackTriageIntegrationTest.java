package com.shipperdog.api.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.AuditCapture;
import com.shipperdog.api.notification.EmailSender;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end acceptance flow for the feedback triage surface (issue
 * #47, S11-02, FR-027, PRD 8.13/32/33): the enforced lifecycle with
 * its audit trail (AC1 — OPEN to TRIAGED to RESOLVED or REJECTED, the
 * closing note mandatory, every closed state re-openable), the
 * release-dashboard rollup with the security-issue visibility applied
 * to counts (AC2 — PRD 32's "14 reports . 1 critical" block and the
 * drill-down filters), the open-critical-bugs gate blocking a
 * promotion end to end and unblocking on resolve (AC3), the re-open
 * flipping the metric back on the next attempt (AC4), and the
 * critical-feedback notification reaching exactly the subscribed
 * roles through the S12-01 event (AC5) — plus the policy engine
 * reading the same live metric (the S10-06 seam this story fills).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
@Import({AuditCapture.class, FeedbackTriageIntegrationTest.TriageFixtures.class})
class FeedbackTriageIntegrationTest {

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
    private CriticalEventRecorder critical;

    @Autowired
    private CapturingEmailSender email;

    @Autowired
    private ObjectMapper mapper;

    private RestClient client;

    private int versionCounter;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        audit.reset();
        critical.events.clear();
        email.sent.clear();
        versionCounter = 400 + (int) (Math.random() * 100);
    }

    // -----------------------------------------------------------------------
    // AC1: the enforced lifecycle, the classification, the assignment —
    // each audited, submissions' content immutable throughout
    // -----------------------------------------------------------------------

    @Test
    void triageLifecycleEdgesAreEnforcedAndAudited() throws Exception {
        World world = releasedWorld("ft-life");
        JsonNode submitted = submit(world, "BUG", "Login crashes on cold start");
        String id = submitted.get("id").asText();
        assertThat(submitted.get("status").asText()).isEqualTo("OPEN");
        assertThat(submitted.get("severity").isNull()).isTrue();
        assertThat(submitted.get("resolutionNote").isNull()).isTrue();

        // The illegal edges refuse: OPEN answers TRIAGED only (the
        // issue's NEW->TRIAGED->RESOLVED/REJECTED vocabulary), and a
        // closed report re-opens to TRIAGED, never anywhere else.
        JsonNode skipped = transition(world.qaToken, world, id, "RESOLVED", "fix shipped", 422);
        assertThat(skipped.get("code").asText()).isEqualTo("FEEDBACK_TRANSITION_INVALID");
        assertThat(skipped.get("errors").get("to").asString()).contains("OPEN").contains("TRIAGED");

        // The classification and the assignment work on the open row.
        JsonNode classified = post(
                world.qaToken,
                "/api/v1/releases/" + world.releaseId + "/feedback/" + id + "/severity?applicationId=" + world.app,
                Map.of("severity", "CRITICAL"),
                200);
        assertThat(classified.get("severity").asText()).isEqualTo("CRITICAL");
        JsonNode unknownSeverity = post(
                world.qaToken,
                "/api/v1/releases/" + world.releaseId + "/feedback/" + id + "/severity?applicationId=" + world.app,
                Map.of("severity", "CATASTROPHIC"),
                400);
        assertThat(unknownSeverity.get("errors").get("severity").asString()).contains("CATASTROPHIC");

        JsonNode assigned = post(
                world.qaToken,
                "/api/v1/releases/" + world.releaseId + "/feedback/" + id + "/assignee?applicationId=" + world.app,
                Map.of("assigneeUserId", world.qaUserId.toString()),
                200);
        assertThat(assigned.get("assigneeUserId").asText()).isEqualTo(world.qaUserId.toString());

        // A member of a foreign organization cannot be named.
        World foreign = new World();
        foreign.org = createOrg("Ft Foreign Corp");
        foreign.adminToken = loginOfOrgRoleIn(foreign.org, "ft-foreign-admin", "ORG_ADMIN");
        JsonNode ineligible = post(
                world.qaToken,
                "/api/v1/releases/" + world.releaseId + "/feedback/" + id + "/assignee?applicationId=" + world.app,
                Map.of("assigneeUserId", userIdOf(foreign.adminToken).toString()),
                422);
        assertThat(ineligible.get("code").asText()).isEqualTo("ASSIGNEE_NOT_ELIGIBLE");

        // The legal walk: TRIAGED, the mandatory note refusal, the
        // two answers, the re-opens.
        transition(world.qaToken, world, id, "TRIAGED", null, 200);
        JsonNode unresolved = transition(world.qaToken, world, id, "RESOLVED", null, 422);
        assertThat(unresolved.get("errors").get("note").asString()).contains("required");
        JsonNode resolved =
                transition(world.qaToken, world, id, "RESOLVED", "Fixed in build 422; verified on iOS", 200);
        assertThat(resolved.get("status").asText()).isEqualTo("RESOLVED");
        assertThat(resolved.get("resolutionNote").asString()).contains("build 422");

        // A closed report's classification is history: re-open first.
        JsonNode frozen = post(
                world.qaToken,
                "/api/v1/releases/" + world.releaseId + "/feedback/" + id + "/severity?applicationId=" + world.app,
                Map.of("severity", "MAJOR"),
                422);
        assertThat(frozen.get("code").asText()).isEqualTo("FEEDBACK_TRANSITION_INVALID");

        // Re-open answers TRIAGED only (the AC4 flip's door), and the
        // re-opened row classifies again.
        JsonNode badReopen = transition(world.qaToken, world, id, "OPEN", null, 422);
        assertThat(badReopen.get("errors").get("to").asString()).contains("RESOLVED");
        JsonNode reopened = transition(world.qaToken, world, id, "TRIAGED", "regressed in the pilot", 200);
        assertThat(reopened.get("status").asText()).isEqualTo("TRIAGED");
        post(
                world.qaToken,
                "/api/v1/releases/" + world.releaseId + "/feedback/" + id + "/severity?applicationId=" + world.app,
                Map.of("severity", "MAJOR"),
                200);
        JsonNode rejected = transition(world.qaToken, world, id, "REJECTED", "works as designed", 200);
        assertThat(rejected.get("status").asText()).isEqualTo("REJECTED");
        transition(world.qaToken, world, id, "TRIAGED", null, 200);

        // The note's own bound (V36's column width): a closing note
        // past 4000 characters is refused before any write.
        transition(world.qaToken, world, id, "RESOLVED", "x".repeat(4001), 422);
        transition(world.qaToken, world, id, "RESOLVED", "x".repeat(4000), 200);
        transition(world.qaToken, world, id, "TRIAGED", null, 200);

        // The legacy DISMISSED rows (V1's vocabulary, no incoming edge
        // since S11-02) re-open through the same TRIAGED door.
        jdbc.update("UPDATE feedback SET status = 'DISMISSED' WHERE id = ?", UUID.fromString(id));
        JsonNode legacy = transition(world.qaToken, world, id, "TRIAGED", null, 200);
        assertThat(legacy.get("status").asText()).isEqualTo("TRIAGED");

        // The clearing assignment: a null assignee releases the member.
        Map<String, Object> clear = new LinkedHashMap<>();
        clear.put("assigneeUserId", null);
        JsonNode cleared = post(
                world.qaToken,
                "/api/v1/releases/" + world.releaseId + "/feedback/" + id + "/assignee?applicationId=" + world.app,
                clear,
                200);
        assertThat(cleared.get("assigneeUserId").isNull()).isTrue();

        // The submission's content never moved (the S11-01
        // immutability posture, kept): description and type stand.
        JsonNode detail = get(
                world.qaToken,
                "/api/v1/releases/" + world.releaseId + "/feedback/" + id + "?applicationId=" + world.app,
                200);
        assertThat(detail.get("description").asString()).isEqualTo("Login crashes on cold start");
        assertThat(detail.get("type").asText()).isEqualTo("BUG");

        // Every crossing is audited with its actor and note (AC1):
        // eight real transitions above (the legal walk, the boundary
        // note, the re-opens, REJECTED, and the DISMISSED legacy
        // re-open) — the refusals audit nothing.
        List<com.shipperdog.api.audit.AuditEvent> crossings =
                audit.eventsOfType(AuditEventType.FEEDBACK_STATUS_TRANSITIONED);
        assertThat(crossings.size()).isEqualTo(8);
        assertThat(crossings.getFirst().userId()).isEqualTo(UUID.fromString(world.qaUserId));
        assertThat(crossings.getFirst().detail().get("to")).isEqualTo("TRIAGED");
        assertThat(crossings.stream()
                        .filter(event -> "RESOLVED".equals(event.detail().get("to")))
                        .findFirst()
                        .orElseThrow()
                        .detail()
                        .get("note"))
                .contains("build 422");
        assertThat(audit.eventsOfType(AuditEventType.FEEDBACK_SEVERITY_CHANGED).size())
                .isEqualTo(2);
        assertThat(audit.eventsOfType(AuditEventType.FEEDBACK_ASSIGNEE_CHANGED).size())
                .isEqualTo(2);

        // A persona without the triage authority conceals at the
        // application target (the @PreAuthorize denial's 404 shape).
        transition(world.viewerToken, world, id, "TRIAGED", null, 404);
    }

    // -----------------------------------------------------------------------
    // AC2: the rollup block and the drill-down filters
    // -----------------------------------------------------------------------

    @Test
    void rollupCountsFoldSeverityAndStatusWithRestrictedVisibility() throws Exception {
        World world = releasedWorld("ft-roll");
        String criticalBug =
                submit(world, "BUG", "Payment screen hangs").get("id").asText();
        String majorBug = submit(world, "BUG", "Icon misaligned").get("id").asText();
        submit(world, "CRASH", "Crash on launch");
        submit(world, "SECURITY_ISSUE", "Token in the URL");

        setSeverity(world, world.qaToken, criticalBug, "CRITICAL");
        setSeverity(world, world.qaToken, majorBug, "MAJOR");
        transition(world.qaToken, world, majorBug, "TRIAGED", null, 200);

        // The owning administrator sees every row: four reports, one
        // open critical, the per-axis folds (PRD 32's block).
        JsonNode adminRollup = rollup(world, world.adminToken);
        assertThat(adminRollup.get("total").asLong()).isEqualTo(4);
        assertThat(adminRollup.get("openCritical").asLong()).isEqualTo(1);
        assertThat(adminRollup.get("bySeverity").get("CRITICAL").asLong()).isEqualTo(1);
        assertThat(adminRollup.get("bySeverity").get("MAJOR").asLong()).isEqualTo(1);
        assertThat(adminRollup.get("bySeverity").get("UNTRIAGED").asLong()).isEqualTo(2);
        assertThat(adminRollup.get("byStatus").get("OPEN").asLong()).isEqualTo(3);
        assertThat(adminRollup.get("byStatus").get("TRIAGED").asLong()).isEqualTo(1);

        // A reader below the security-issue bar sees counts of exactly
        // the rows the listing would show them: the security report
        // is absent from the rollup too, never leaked by a count.
        JsonNode qaRollup = rollup(world, world.qaToken);
        assertThat(qaRollup.get("total").asLong()).isEqualTo(3);
        assertThat(qaRollup.get("openCritical").asLong()).isEqualTo(1);
        assertThat(qaRollup.get("bySeverity").get("UNTRIAGED").asLong()).isEqualTo(1);
        assertThat(qaRollup.get("byStatus").get("OPEN").asLong()).isEqualTo(2);

        // The drill-down (AC2): the severity and status filters the
        // block's list rides on.
        JsonNode criticals = get(world.qaToken, listUri(world, "severity=CRITICAL"), 200);
        assertThat(criticals.get("items").size()).isEqualTo(1);
        assertThat(criticals.get("items").get(0).get("id").asText()).isEqualTo(criticalBug);
        assertThat(criticals.get("items").get(0).get("severity").asText()).isEqualTo("CRITICAL");
        JsonNode triaged = get(world.qaToken, listUri(world, "status=TRIAGED"), 200);
        assertThat(triaged.get("items").size()).isEqualTo(1);
        assertThat(triaged.get("items").get(0).get("assigneeUserId").isNull()).isTrue();
        JsonNode bogus = get(world.qaToken, listUri(world, "severity=CATASTROPHIC"), 400);
        assertThat(bogus.get("errors").get("severity").asString()).contains("CATASTROPHIC");
    }

    // -----------------------------------------------------------------------
    // AC3 + AC4: open critical bugs block promotion end to end; the
    // resolve flips the metric; the re-open flips it back
    // -----------------------------------------------------------------------

    @Test
    void openCriticalBugsGateBlocksUnblocksAndReblocksPromotion() throws Exception {
        World world = releasedWorld("ft-gate");
        ActiveRelease release = activeReleaseAt(world, "qa");

        // The gate the issue's example names: open-critical-bugs at
        // max 0 on the ring the next hop enters.
        createGateDefinition(world, "open-critical-bugs", "tenant-pilot", Map.of("max", 0));

        // The definitive zero (the metric's character): a release
        // with no critical feedback is NOT data-less — the gate
        // resolves PASSED at observed 0, never NO_DATA.
        JsonNode clear = precheck(world, release.id());
        assertThat(clear.get("promotable").asBoolean()).isTrue();

        // Critical unresolved feedback: the gate resolves FAILED at
        // observed 1 and the promotion refuses with the blocker named
        // (AC3's end-to-end refusal, recorded on the timeline).
        String bug = submitAgainst(world, release.versionId(), "BUG", "Crash loop on upgrade")
                .get("id")
                .asText();
        setSeverityOn(world, world.qaToken, release.id(), bug, "CRITICAL");
        transitionOn(world, world.qaToken, release.id(), bug, "TRIAGED", null, 200);
        JsonNode blocked = precheck(world, release.id());
        assertThat(blocked.get("promotable").asBoolean()).isFalse();
        assertThat(blocked.get("blockers").has("gate:open-critical-bugs")).isTrue();
        JsonNode refused = post(
                world.managerToken,
                "/api/v1/releases/" + release.id() + "/promote",
                Map.of("applicationId", world.app.toString()),
                409);
        assertThat(refused.get("errors").get("gate:open-critical-bugs").asString())
                .isNotEmpty();
        JsonNode gateRow = gateOf(gates(world, release.id()), "open-critical-bugs");
        assertThat(gateRow.get("status").asText()).isEqualTo("FAILED");
        assertThat(gateRow.get("observedValue").asDouble()).isEqualTo(1.0);
        assertThat(gateRow.get("source").asText()).isEqualTo("METRIC");

        // The resolve flips the metric (AC4's first half): the gate
        // re-resolves PASSED at observed 0 on the next attempt and
        // the hop commits.
        transitionOn(world, world.qaToken, release.id(), bug, "RESOLVED", "fixed in 7.12.1", 200);
        JsonNode unblocked = precheck(world, release.id());
        assertThat(unblocked.get("promotable").asBoolean()).isTrue();
        JsonNode promoted = post(
                world.managerToken,
                "/api/v1/releases/" + release.id() + "/promote",
                Map.of("applicationId", world.app.toString()),
                200);
        assertThat(promoted.get("toRing").asText()).isEqualTo("tenant-pilot");
        assertThat(gateOf(gates(world, release.id()), "open-critical-bugs")
                        .get("observedValue")
                        .asDouble())
                .isEqualTo(0.0);

        // The re-open flips it back (AC4's second half): gates
        // re-evaluate on the next promotion attempt — here the next
        // hop's ring (uat) carries the same gate and refuses again.
        transitionOn(world, world.qaToken, release.id(), bug, "TRIAGED", "regressed on uat", 200);
        createGateDefinition(world, "open-critical-bugs", "uat", Map.of("max", 0));
        JsonNode reblocked = precheck(world, release.id());
        assertThat(reblocked.get("promotable").asBoolean()).isFalse();
        assertThat(reblocked.get("blockers").has("gate:open-critical-bugs")).isTrue();
        post(
                world.managerToken,
                "/api/v1/releases/" + release.id() + "/promote",
                Map.of("applicationId", world.app.toString()),
                409);
    }

    // -----------------------------------------------------------------------
    // The S10-06 seam: the policy engine reads the same live metric
    // -----------------------------------------------------------------------

    @Test
    void policyRuleReadsTheLiveOpenCriticalBugsMetric() throws Exception {
        World world = releasedWorld("ft-policy");
        ActiveRelease release = activeReleaseAt(world, "qa");
        createRule(
                world.adminToken,
                world.org,
                "no-open-critical-bugs",
                "ORGANIZATION",
                "BLOCK",
                10,
                Map.of("metric", "OPEN_CRITICAL_BUGS", "operator", ">", "value", 0),
                201);

        String bug = submitAgainst(world, release.versionId(), "BUG", "Data loss on logout")
                .get("id")
                .asText();
        setSeverityOn(world, world.qaToken, release.id(), bug, "CRITICAL");

        // No armed stub feeds this suite: the production feedback
        // source answers 1.0, the rule fires with the observed value.
        JsonNode blocked = post(
                world.managerToken,
                "/api/v1/releases/" + release.id() + "/promote",
                Map.of("applicationId", world.app.toString()),
                409);
        assertThat(blocked.get("errors").get("ReleasePolicyEngine").asString())
                .contains("no-open-critical-bugs")
                .contains("1.0");

        // Resolved: the rule's condition is not met, the hop commits.
        transitionOn(world, world.qaToken, release.id(), bug, "TRIAGED", null, 200);
        transitionOn(world, world.qaToken, release.id(), bug, "RESOLVED", "fixed", 200);
        post(
                world.managerToken,
                "/api/v1/releases/" + release.id() + "/promote",
                Map.of("applicationId", world.app.toString()),
                200);
    }

    // -----------------------------------------------------------------------
    // AC5: critical feedback notifies exactly the subscribed roles
    // -----------------------------------------------------------------------

    @Test
    void criticalFeedbackNotifiesSubscribedRoles() throws Exception {
        World world = releasedWorld("ft-mail");

        // A critical bug reaches the org's administrators and QA
        // engineers (the triage queue's roles), no one else — and the
        // event (S12-01's relay point) fires beside it.
        String bug = submit(world, "BUG", "Money disappears").get("id").asText();
        setSeverity(world, world.qaToken, bug, "CRITICAL");
        assertThat(critical.events.size()).isEqualTo(1);
        assertThat(critical.events.getFirst().type()).isEqualTo("BUG");
        assertThat(critical.events.getFirst().releaseRef()).isEqualTo(world.releaseRef);
        // The subscribed roles: the org's admins (its persona AND the
        // platform administrator who founded it) and its QA
        // engineers — nobody else (the world's invitation mail is
        // captured too; the notifications are the filtered set).
        assertThat(email.notificationRecipients())
                .containsExactlyInAnyOrder(DEV_ADMIN_EMAIL, world.adminEmail, world.qaEmail);
        assertThat(email.notifications().getFirst().body()).contains(world.releaseRef);
        assertThat(email.notifications().getFirst().body()).doesNotContain("Money disappears");

        // A downgrade is quiet; every becoming-critical fires again.
        setSeverity(world, world.qaToken, bug, "MAJOR");
        assertThat(critical.events.size()).isEqualTo(1);
        setSeverity(world, world.qaToken, bug, "CRITICAL");
        assertThat(critical.events.size()).isEqualTo(2);
        email.sent.clear();

        // A critical security report notifies the restricted
        // visibility's reader set ALONE (the concealment discipline
        // governs the subscription itself), and the general queue's
        // triage action conceals the row as the genuine miss.
        String security =
                submit(world, "SECURITY_ISSUE", "Key logged in clear").get("id").asText();
        post(
                world.qaToken,
                "/api/v1/releases/" + world.releaseId + "/feedback/" + security + "/severity?applicationId="
                        + world.app,
                Map.of("severity", "CRITICAL"),
                404);
        setSeverity(world, world.securityToken, security, "CRITICAL");
        assertThat(critical.events.getLast().type()).isEqualTo("SECURITY_ISSUE");
        assertThat(email.notificationRecipients())
                .containsExactlyInAnyOrder(DEV_ADMIN_EMAIL, world.adminEmail, world.securityEmail);
    }

    // -----------------------------------------------------------------------
    // The world (the S11-01 suite's shapes, plus the release walk the
    // promotion suites use)
    // -----------------------------------------------------------------------

    private static final class World {
        UUID org;
        String adminToken;
        String adminEmail;
        String ownerToken;
        String managerToken;
        String qaToken;
        String qaEmail;
        String qaUserId;
        String securityToken;
        String securityEmail;
        String viewerToken;
        UUID tenantA;
        UUID app;
        UUID version;
        UUID releaseId;
        String releaseRef;
        UUID testerId;
        String testerToken;
    }

    private World releasedWorld(String slug) throws Exception {
        World world = new World();
        world.org = createOrg("Ft " + slug + " Corp");
        world.adminEmail = slug + "-admin@example.com";
        world.qaEmail = slug + "-qa@example.com";
        world.securityEmail = slug + "-sec@example.com";
        world.adminToken = loginOfOrgRoleIn(world.org, slug + "-admin", "ORG_ADMIN");
        world.ownerToken = loginOfOrgRoleIn(world.org, slug + "-owner", "APP_OWNER");
        world.managerToken = loginOfOrgRoleIn(world.org, slug + "-manager", "RELEASE_MANAGER");
        world.qaToken = loginOfOrgRoleIn(world.org, slug + "-qa", "QA_ENGINEER");
        world.securityToken = loginOfOrgRoleIn(world.org, slug + "-sec", "SECURITY_REVIEWER");
        world.viewerToken = loginOfOrgRoleIn(world.org, slug + "-viewer", "VIEWER");
        world.qaUserId = userIdOf(world.qaToken).toString();
        world.tenantA = createTenant(world.adminToken, world.org, "Ft " + slug + " Bank", "ft-" + slug + "-a");
        world.app = createSharedApp(
                world.adminToken, world.org, "Ft " + slug + " Banking", "com." + slug.replace("-", "") + ".banking");
        world.version = createVersion(world.ownerToken, world.app, "2.0.0");
        UUID artifact = insertArtifact(world.version, "IOS", 1);
        authorize(world.ownerToken, world.app, world.tenantA);
        Map<String, Object> release = new LinkedHashMap<>();
        release.put("applicationId", world.app.toString());
        release.put("versionId", world.version.toString());
        release.put("ring", "qa");
        release.put("artifacts", List.of(Map.of("platform", "IOS", "artifactId", artifact.toString())));
        release.put("targets", List.of(Map.of("tenantId", world.tenantA.toString(), "testerGroupIds", List.of())));
        JsonNode created = post(world.managerToken, "/api/v1/releases", release, 201);
        world.releaseId = UUID.fromString(created.get("id").asText());
        world.releaseRef = created.get("releaseRef").asText();

        String testerEmail = slug + "-tester@example.com";
        JsonNode invited = post(
                world.adminToken,
                "/api/v1/testers",
                Map.of("organizationId", world.org.toString(), "email", testerEmail, "fullName", "Ft Tester"),
                201);
        client.post()
                .uri("/api/v1/auth/register")
                .body(Map.of(
                        "invitationToken",
                        invited.get("invitation").get("invitationToken").asString(),
                        "email",
                        testerEmail,
                        "password",
                        PASSWORD,
                        "fullName",
                        "Ft Tester"))
                .retrieve()
                .body(String.class);
        post(
                world.adminToken,
                "/api/v1/tenants/" + world.tenantA + "/testers",
                Map.of("testerId", invited.get("id").asText()),
                201);
        world.testerId = UUID.fromString(invited.get("id").asText());
        world.testerToken = accessToken(login(testerEmail, PASSWORD));
        return world;
    }

    /** A walked release: its id and the version the feedback submissions against it address. */
    record ActiveRelease(UUID id, UUID versionId) {}

    /** A fresh release of the world's application walked to ACTIVE at the named ring. */
    private ActiveRelease activeReleaseAt(World world, String ring) {
        String name = String.format("%d.%d.%d", 5 + versionCounter / 10, versionCounter % 10, 7);
        versionCounter++;
        UUID version = createVersion(world.ownerToken, world.app, name);
        UUID artifact = insertArtifact(version, "IOS", versionCounter);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", world.app.toString());
        body.put("versionId", version.toString());
        body.put("ring", ring);
        body.put("artifacts", List.of(Map.of("platform", "IOS", "artifactId", artifact.toString())));
        body.put("targets", List.of(Map.of("tenantId", world.tenantA.toString(), "testerGroupIds", List.of())));
        JsonNode created = post(world.managerToken, "/api/v1/releases", body, 201);
        UUID releaseId = UUID.fromString(created.get("id").asText());
        for (String to : List.of("VALIDATING", "READY", "APPROVAL_PENDING", "APPROVED", "DEPLOYING", "ACTIVE")) {
            Map<String, Object> transition = new LinkedHashMap<>();
            transition.put("applicationId", world.app.toString());
            transition.put("to", to);
            post(world.managerToken, "/api/v1/releases/" + releaseId + "/transitions", transition, 200);
        }
        return new ActiveRelease(releaseId, version);
    }

    /** Records every becoming-critical (S12-01's feed, observed in-process). */
    static class CriticalEventRecorder {
        final List<FeedbackMarkedCriticalEvent> events = new CopyOnWriteArrayList<>();

        @EventListener
        void on(FeedbackMarkedCriticalEvent event) {
            events.add(event);
        }
    }

    /** One delivered notification (the EmailSender seam, observed). */
    record SentEmail(String to, String subject, String body) {}

    static class CapturingEmailSender implements EmailSender {
        final List<SentEmail> sent = new CopyOnWriteArrayList<>();

        @Override
        public void send(String to, String subject, String body) {
            sent.add(new SentEmail(to, subject, body));
        }

        List<SentEmail> notifications() {
            return sent.stream()
                    .filter(mail -> mail.subject().startsWith("Critical feedback"))
                    .toList();
        }

        List<String> notificationRecipients() {
            return notifications().stream().map(SentEmail::to).toList();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TriageFixtures {
        @Bean
        CriticalEventRecorder criticalEventRecorder() {
            return new CriticalEventRecorder();
        }

        @Bean
        @Primary
        CapturingEmailSender capturingEmailSender() {
            return new CapturingEmailSender();
        }
    }

    // -----------------------------------------------------------------------
    // HTTP helpers (the S11-01 suite's shapes)
    // -----------------------------------------------------------------------

    private JsonNode submit(World world, String type, String description) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("application_id", world.app.toString());
        fields.put("version_id", world.version.toString());
        fields.put("tenant_id", world.tenantA.toString());
        fields.put("type", type);
        fields.put("description", description);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        fields.forEach(body::add);
        return exchange(
                client.post()
                        .uri("/api/v1/feedback")
                        .headers(headers -> headers.setBearerAuth(world.testerToken))
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(body),
                201);
    }

    /** The submission addressing a SPECIFIC release's version (the metric tests' walked releases). */
    private JsonNode submitAgainst(World world, UUID versionId, String type, String description) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("application_id", world.app.toString());
        fields.put("version_id", versionId.toString());
        fields.put("tenant_id", world.tenantA.toString());
        fields.put("type", type);
        fields.put("description", description);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        fields.forEach(body::add);
        return exchange(
                client.post()
                        .uri("/api/v1/feedback")
                        .headers(headers -> headers.setBearerAuth(world.testerToken))
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(body),
                201);
    }

    private JsonNode setSeverityOn(World world, String bearer, UUID releaseId, String feedbackId, String severity) {
        return post(
                bearer,
                "/api/v1/releases/" + releaseId + "/feedback/" + feedbackId + "/severity?applicationId=" + world.app,
                Map.of("severity", severity),
                200);
    }

    private JsonNode transitionOn(
            World world, String bearer, UUID releaseId, String feedbackId, String to, String note, int expected) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("to", to);
        if (note != null) {
            body.put("note", note);
        }
        return post(
                bearer,
                "/api/v1/releases/" + releaseId + "/feedback/" + feedbackId + "/transition?applicationId=" + world.app,
                body,
                expected);
    }

    private JsonNode setSeverity(World world, String bearer, String feedbackId, String severity) {
        return post(
                bearer,
                "/api/v1/releases/" + world.releaseId + "/feedback/" + feedbackId + "/severity?applicationId="
                        + world.app,
                Map.of("severity", severity),
                200);
    }

    private JsonNode transition(
            String bearer, World world, String feedbackId, String to, String note, int expectedStatus) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("to", to);
        if (note != null) {
            body.put("note", note);
        }
        return post(
                bearer,
                "/api/v1/releases/" + world.releaseId + "/feedback/" + feedbackId + "/transition?applicationId="
                        + world.app,
                body,
                expectedStatus);
    }

    private JsonNode rollup(World world, String bearer) {
        return get(bearer, "/api/v1/releases/" + world.releaseId + "/feedback/rollup?applicationId=" + world.app, 200);
    }

    private JsonNode precheck(World world, UUID releaseId) {
        return get(
                world.managerToken,
                "/api/v1/releases/" + releaseId + "/promotion-precheck?applicationId=" + world.app,
                200);
    }

    private JsonNode gates(World world, UUID releaseId) {
        return get(world.managerToken, "/api/v1/releases/" + releaseId + "/gates?applicationId=" + world.app, 200);
    }

    private static JsonNode gateOf(JsonNode gates, String gateType) {
        for (JsonNode gate : gates.get("gates")) {
            if (gateType.equals(gate.get("gateType").asText())) {
                return gate;
            }
        }
        throw new AssertionError("gate " + gateType + " not present in " + gates);
    }

    private void createGateDefinition(World world, String gateType, String ring, Map<String, Object> config) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("organizationId", world.org.toString());
        body.put("gateType", gateType);
        body.put("ring", ring);
        body.put("required", true);
        body.put("config", config);
        post(world.managerToken, "/api/v1/gate-definitions", body, 201);
    }

    private JsonNode createRule(
            String token,
            UUID organizationId,
            String name,
            String scope,
            String action,
            int position,
            Map<String, Object> condition,
            int expected) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("organizationId", organizationId.toString());
        body.put("name", name);
        body.put("scope", scope);
        body.put("action", action);
        body.put("position", position);
        body.put("condition", condition);
        return post(token, "/api/v1/release-policies", body, expected);
    }

    private String listUri(World world, String query) {
        return "/api/v1/releases/" + world.releaseId + "/feedback?applicationId=" + world.app
                + (query == null ? "" : "&" + query);
    }

    private record Exchange(int status, JsonNode body) {}

    private JsonNode exchange(RestClient.RequestHeadersSpec<?> spec, int expectedStatus) {
        Exchange recorded = spec.exchange((request, response) -> {
            byte[] bytes = response.getBody().readAllBytes();
            return new Exchange(
                    response.getStatusCode().value(),
                    bytes.length == 0 ? mapper.createObjectNode() : mapper.readTree(bytes));
        });
        assertThat(recorded.status())
                .as("exchange -> %d, body=%s", expectedStatus, recorded.body())
                .isEqualTo(expectedStatus);
        return recorded.body();
    }

    private JsonNode get(String bearer, String uri, int expectedStatus) {
        return exchange(client.get().uri(uri).headers(headers -> headers.setBearerAuth(bearer)), expectedStatus);
    }

    private JsonNode post(String bearer, String uri, Map<String, Object> body, int expectedStatus) {
        return exchange(
                client.post()
                        .uri(uri)
                        .headers(headers -> headers.setBearerAuth(bearer))
                        .body(body),
                expectedStatus);
    }

    // -----------------------------------------------------------------------
    // Fixture helpers (the S11-01 suite's shapes)
    // -----------------------------------------------------------------------

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

    private UUID createVersion(String token, UUID applicationId, String versionName) {
        return UUID.fromString(post(
                        token,
                        "/api/v1/applications/" + applicationId + "/versions",
                        Map.of("versionName", versionName),
                        201)
                .get("id")
                .asText());
    }

    private UUID createSharedApp(String token, UUID organizationId, String name, String bundleId) {
        Map<String, Object> body = new HashMap<>();
        body.put("ownershipScope", "ORGANIZATION");
        body.put("organizationId", organizationId.toString());
        body.put("name", name);
        body.put("platforms", List.of("IOS", "ANDROID"));
        body.put("bundleId", bundleId);
        body.put("packageName", bundleId);
        return UUID.fromString(
                post(token, "/api/v1/applications", body, 201).get("id").asText());
    }

    private void authorize(String token, UUID applicationId, UUID tenantId) {
        post(token, "/api/v1/applications/" + applicationId + "/tenants/" + tenantId, Map.of(), 201);
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
                        "Ft " + role))
                .retrieve()
                .body(String.class);
        return accessToken(login(email, PASSWORD));
    }

    private UUID userIdOf(String bearer) {
        return UUID.fromString(decode(bearer).get("sub").asText());
    }

    private JsonNode decode(String token) {
        String claims = token.split("\\.")[1];
        return mapper.readTree(Base64.getUrlDecoder().decode(claims));
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
            return mapper.readTree(tokensJson).get("accessToken").asString();
        } catch (Exception e) {
            throw new IllegalStateException("could not parse the token pair", e);
        }
    }
}
