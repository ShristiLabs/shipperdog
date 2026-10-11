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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
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
 * End-to-end acceptance flow for the promotion engine (issue #44,
 * S10-05, FR-018, PRD 8.10/17/18/33, INV-008): the governed
 * ring-to-ring move. The happy hop that widens distribution and
 * records the timeline (AC2), the complete-blocker-list 409 with
 * recorded evidence for unmet gates, approvals (the round the attempt
 * opens), and eligibility (AC1), the selective per-tenant promotion
 * and the staggered split refusal (AC3), the INV-008 pins — artifact
 * SHA-256s and the frozen manifest digest identical across every hop
 * (AC4), the append-only history with every actor (AC5), and the
 * skip/terminal posture (AC6): one ring at a time, the final ring
 * crossing ACTIVE to PROMOTED with the state machine's own ceremony.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
@Import({
    AuditCapture.class,
    PromotionIntegrationTest.StaleLegPolicy.class,
    PromotionIntegrationTest.RollbackRacePolicy.class
})
class PromotionIntegrationTest {

    /**
     * A promotion-policy registrant that permits exactly the FIRST
     * evaluation of the armed release and refuses every later one: the
     * deterministic stand-in for a precheck leg whose answer goes stale
     * between the promote act's outer evaluation and the hop's
     * in-transaction re-run (the TOCTOU window) — the engine's second,
     * serialized evaluation must catch it and answer the same 409 with
     * the evidence recorded. Inert for every other release.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class StaleLegPolicy implements ReleasePromotionPolicy {

        static volatile UUID armedRelease = null;
        static final java.util.concurrent.atomic.AtomicInteger EVALUATIONS =
                new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public Optional<String> denial(PromotionCheck check) {
            if (!check.releaseId().equals(armedRelease)) {
                return Optional.empty();
            }
            if (EVALUATIONS.incrementAndGet() == 1) {
                return Optional.empty();
            }
            return Optional.of("the policy's answer went stale after the precheck read (stale-leg fixture)");
        }
    }

    /**
     * A promotion-policy registrant that performs the armed side
     * effect exactly once, on the SECOND evaluation of the armed
     * release — the hop's in-transaction revalidation: the
     * deterministic stand-in for the operator's rollback committing
     * between the promote act's read and the hop's compare-and-set
     * (the race the hop's release-status condition closes — a hop
     * must never land after a rollback). Inert for every other
     * release.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class RollbackRacePolicy implements ReleasePromotionPolicy {

        static volatile UUID armedRelease = null;
        static volatile Runnable onRevalidation = null;
        static final java.util.concurrent.atomic.AtomicInteger EVALUATIONS =
                new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public Optional<String> denial(PromotionCheck check) {
            if (!check.releaseId().equals(armedRelease)) {
                return Optional.empty();
            }
            if (EVALUATIONS.incrementAndGet() == 2 && onRevalidation != null) {
                onRevalidation.run();
            }
            return Optional.empty();
        }
    }

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
        versionCounter = 200 + (int) (Math.random() * 100);
    }

    // -----------------------------------------------------------------------
    // AC2: the happy hop widens distribution and records the timeline
    // -----------------------------------------------------------------------

    @Test
    void happyHopMovesTargetsRingRecordsTimelineAndAudits() {
        World world = world("promo-happy");
        Release release = activeReleaseAt(world, "qa", "promo-happy");

        JsonNode result = promote(world.managerToken, world.app, release.id, null, null, 200);
        assertThat(result.get("fromRing").asText()).isEqualTo("qa");
        assertThat(result.get("toRing").asText()).isEqualTo("tenant-pilot");
        assertThat(result.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(result.get("crossedToPromoted").asBoolean()).isFalse();
        assertThat(result.get("targets").get(0).get("ring").asText()).isEqualTo("tenant-pilot");
        assertThat(result.get("promotionStatus").asText()).isEqualTo("COMPLETED");

        // The detail renders the target's OWN ring now (per-target rings).
        JsonNode detail = get(world.viewerToken, "/api/v1/releases/" + release.id + "?applicationId=" + world.app, 200);
        assertThat(detail.get("targets").get(0).get("ring").asText()).isEqualTo("tenant-pilot");

        // The timeline row: one COMPLETED hop with both rings and the actor.
        JsonNode history = get(
                world.viewerToken, "/api/v1/releases/" + release.id + "/promotions?applicationId=" + world.app, 200);
        assertThat(history.get("promotions")).hasSize(1);
        JsonNode row = history.get("promotions").get(0);
        assertThat(row.get("fromRing").asText()).isEqualTo("qa");
        assertThat(row.get("toRing").asText()).isEqualTo("tenant-pilot");
        assertThat(row.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(row.get("actorId").asText()).isEqualTo(userIdOf(world.managerToken));

        // Audited: RELEASE_PROMOTED with the hop's facts.
        assertThat(audit.eventsOfType(AuditEventType.RELEASE_PROMOTED))
                .anyMatch(event -> "tenant-pilot".equals(event.detail().get("toRing")));

        // The precheck now answers the NEXT hop (uat), promotable.
        JsonNode precheck = precheck(world.viewerToken, world.app, release.id, 200);
        assertThat(precheck.get("promotable").asBoolean()).isTrue();
        assertThat(precheck.get("blockers")).isEmpty();
    }

    // -----------------------------------------------------------------------
    // AC1: the complete blocker list, with recorded evidence
    // -----------------------------------------------------------------------

    @Test
    void failedGateBlocksPromotionWithRecordedEvidence() {
        World world = world("promo-gate");
        Release release = activeReleaseAt(world, "qa", "promo-gate");
        createDefinition(world.adminToken, world.org, "smoke-test", "tenant-pilot", true, null, 201);
        recordGateResult(
                world.managerToken,
                world.app,
                release.id,
                "smoke-test",
                "FAILED",
                "https://ci.internal/run/77",
                null,
                200);

        JsonNode refused = promote(world.managerToken, world.app, release.id, null, null, 409);
        assertThat(refused.get("code").asText()).isEqualTo("RELEASE_PROMOTION_BLOCKED");
        assertThat(refused.get("errors").get("gate:smoke-test").asText())
                .contains("failed")
                .contains("https://ci.internal/run/77");

        // The precheck renders the SAME list (one evaluation, three surfaces).
        JsonNode precheck = precheck(world.viewerToken, world.app, release.id, 200);
        assertThat(precheck.get("promotable").asBoolean()).isFalse();
        assertThat(precheck.get("blockers").get("gate:smoke-test").asText())
                .isEqualTo(refused.get("errors").get("gate:smoke-test").asText());

        // The refused attempt is recorded evidence (PRD 33).
        JsonNode history = promotions(world.viewerToken, world.app, release.id);
        assertThat(history.get("promotions")).hasSize(1);
        assertThat(history.get("promotions").get(0).get("status").asText()).isEqualTo("BLOCKED");
        assertThat(history.get("promotions")
                        .get(0)
                        .get("blockers")
                        .get("gate:smoke-test")
                        .asText())
                .contains("https://ci.internal/run/77");
        assertThat(audit.eventsOfType(AuditEventType.RELEASE_PROMOTION_BLOCKED)).hasSize(1);

        // The override governs: an overridden FAILED gate no longer blocks.
        post(
                world.adminToken,
                "/api/v1/releases/" + release.id + "/gates/smoke-test/override",
                Map.of("applicationId", world.app.toString(), "reason", "known flake, retried green"),
                200);
        promote(world.managerToken, world.app, release.id, null, null, 200);
    }

    @Test
    void approvalRoundOpensOnFirstAttemptThenPassesWhenSatisfied() {
        World world = world("promo-appr");
        createWorkflow(world.adminToken, world.org, "pilot-promo", null, "QA_ENGINEER");
        patchRing(world.adminToken, world.org, "tenant-pilot", Map.of("approvalPolicy", "pilot-promo"));
        Release release = activeReleaseAt(world, "qa", "promo-appr");

        // The first attempt OPENS the round and answers blocked on it.
        JsonNode first = promote(world.managerToken, world.app, release.id, null, null, 409);
        assertThat(first.get("errors").get("approval").asText())
                .contains("pilot-promo")
                .contains("pending")
                .contains("QA");
        JsonNode rounds =
                get(world.viewerToken, "/api/v1/releases/" + release.id + "/approvals?applicationId=" + world.app, 200);
        assertThat(rounds.get("rounds")).hasSize(1);
        assertThat(rounds.get("rounds").get(0).get("roundNumber").asInt()).isEqualTo(1);

        // A read-only precheck reports the open round without opening anything.
        JsonNode precheck = precheck(world.viewerToken, world.app, release.id, 200);
        assertThat(precheck.get("approval").get("requirement").asText()).isEqualTo("PENDING");

        // The QA role-holder satisfies the round (never the creator).
        post(
                world.qaToken,
                "/api/v1/releases/" + release.id + "/approvals",
                Map.of("applicationId", world.app.toString(), "decision", "APPROVE"),
                200);
        JsonNode done = promote(world.managerToken, world.app, release.id, null, null, 200);
        assertThat(done.get("toRing").asText()).isEqualTo("tenant-pilot");

        // A second release opens its OWN round (rounds are per release and
        // ring): the first refusal, then the satisfied retry.
        Release other = activeReleaseAt(world, "qa", "promo-appr2");
        promote(world.managerToken, world.app, other.id, null, null, 409);
        post(
                world.qaToken,
                "/api/v1/releases/" + other.id + "/approvals",
                Map.of("applicationId", world.app.toString(), "decision", "APPROVE"),
                200);
        JsonNode direct = promote(world.managerToken, world.app, other.id, null, null, 200);
        assertThat(direct.get("toRing").asText()).isEqualTo("tenant-pilot");
    }

    @Test
    void rejectedPromotionRoundReopensOnTheNextAttempt() {
        World world = world("promo-rej");
        createWorkflow(world.adminToken, world.org, "pilot-promo", null, "QA_ENGINEER");
        patchRing(world.adminToken, world.org, "tenant-pilot", Map.of("approvalPolicy", "pilot-promo"));
        Release release = activeReleaseAt(world, "qa", "promo-rej");

        promote(world.managerToken, world.app, release.id, null, null, 409);
        post(
                world.qaToken,
                "/api/v1/releases/" + release.id + "/approvals",
                Map.of("applicationId", world.app.toString(), "decision", "REJECT", "reason", "crash on iPad"),
                200);

        // The rework re-open IS the retry: a rejected round's next attempt
        // opens a fresh round of the same frozen definition (the restart
        // discipline, ring-scoped) and answers blocked on the round it
        // opened — round 2 pending.
        JsonNode reopened = promote(world.managerToken, world.app, release.id, null, null, 409);
        assertThat(reopened.get("errors").get("approval").asText())
                .contains("round 2")
                .contains("pending")
                .contains("QA");
        JsonNode rounds =
                get(world.viewerToken, "/api/v1/releases/" + release.id + "/approvals?applicationId=" + world.app, 200);
        assertThat(rounds.get("rounds")).hasSize(2);
        assertThat(rounds.get("rounds").get(0).get("status").asText()).isEqualTo("REJECTED");
        assertThat(rounds.get("rounds").get(1).get("roundNumber").asInt()).isEqualTo(2);
        assertThat(rounds.get("rounds").get(1).get("status").asText()).isEqualTo("PENDING");
    }

    @Test
    void unresolvedApprovalPolicyFailsClosed() {
        World world = world("promo-unres");
        patchRing(world.adminToken, world.org, "tenant-pilot", Map.of("approvalPolicy", "missing-policy"));
        Release release = activeReleaseAt(world, "qa", "promo-unres");

        JsonNode refused = promote(world.managerToken, world.app, release.id, null, null, 409);
        assertThat(refused.get("errors").get("approval").asText())
                .contains("missing-policy")
                .contains("does not resolve");
        JsonNode precheck = precheck(world.viewerToken, world.app, release.id, 200);
        assertThat(precheck.get("approval").get("requirement").asText()).isEqualTo("UNRESOLVED");
    }

    @Test
    void eligibilityExclusionBlocksPromotionWithReasons() {
        World world = world("promo-elig");
        world.tenantB = createTenant(world.adminToken, world.org, "Promo Elig Other Bank", "peb-promo-elig");
        UUID sharedApp = createSharedApp(
                world.adminToken,
                world.org,
                world.tenantA,
                world.tenantB,
                "Promo Elig Banking",
                "com.promoeligshared.banking");
        Release release = activeReleaseWithTargets(
                world,
                sharedApp,
                "qa",
                List.of(
                        Map.of("tenantId", world.tenantA.toString(), "testerGroupIds", List.of()),
                        Map.of("tenantId", world.tenantB.toString(), "testerGroupIds", List.of())),
                "promo-elig");
        // The target ring admits only tenantB: tenantA is the blocker.
        patchRing(
                world.adminToken,
                world.org,
                "tenant-pilot",
                Map.of("eligibility", Map.of("tenants", List.of(world.tenantB.toString()))));

        JsonNode refused = promote(world.managerToken, sharedApp, release.id, null, null, 409);
        assertThat(refused.get("errors").get("eligibility:" + world.tenantA).asText())
                .contains("not in the ring's eligibility");
        // tenantB is not named: one blocker per excluded tenant, complete list.
        assertThat(refused.get("errors").size()).isEqualTo(1);

        // Selective promotion of the admitted tenant passes (AC3's seam).
        JsonNode selective = promote(world.managerToken, sharedApp, release.id, List.of(world.tenantB), null, 200);
        assertThat(selective.get("tenants")).hasSize(1);
        JsonNode detail = get(world.viewerToken, "/api/v1/releases/" + release.id + "?applicationId=" + sharedApp, 200);
        Map<String, String> ringsByTenant = new HashMap<>();
        detail.get("targets")
                .forEach(t -> ringsByTenant.put(
                        t.get("tenantId").asText(), t.get("ring").asText()));
        assertThat(ringsByTenant.get(world.tenantA.toString())).isEqualTo("qa");
        assertThat(ringsByTenant.get(world.tenantB.toString())).isEqualTo("tenant-pilot");

        // The staggered set refuses as one hop and names every ring.
        JsonNode split = promote(world.managerToken, sharedApp, release.id, null, null, 409);
        assertThat(split.get("code").asText()).isEqualTo("RELEASE_PROMOTION_RING_SPLIT");
        assertThat(split.get("errors").get(world.tenantA.toString()).asText()).isEqualTo("qa");
        assertThat(split.get("errors").get(world.tenantB.toString()).asText()).isEqualTo("tenant-pilot");
    }

    // -----------------------------------------------------------------------
    // AC3: selective tenant promotion
    // -----------------------------------------------------------------------

    @Test
    void selectiveTenantPromotionMovesOnlyTheNamedTenants() {
        World world = world("promo-select");
        world.tenantB = createTenant(world.adminToken, world.org, "Promo Select Other Bank", "psb-promo-select");
        UUID sharedApp = createSharedApp(
                world.adminToken,
                world.org,
                world.tenantA,
                world.tenantB,
                "Promo Select Banking",
                "com.promoselectshared.banking");
        Release release = activeReleaseWithTargets(
                world,
                sharedApp,
                "tenant-pilot",
                List.of(
                        Map.of("tenantId", world.tenantA.toString(), "testerGroupIds", List.of()),
                        Map.of("tenantId", world.tenantB.toString(), "testerGroupIds", List.of())),
                "promo-select");

        JsonNode acme = promote(world.managerToken, sharedApp, release.id, List.of(world.tenantA), null, 200);
        assertThat(acme.get("tenants")).hasSize(1);
        assertThat(acme.get("status").asText()).isEqualTo("ACTIVE");

        // Acme moved; Contoso stays; the release stays ACTIVE.
        JsonNode detail = get(world.viewerToken, "/api/v1/releases/" + release.id + "?applicationId=" + sharedApp, 200);
        Map<String, String> ringsByTenant = new HashMap<>();
        detail.get("targets")
                .forEach(t -> ringsByTenant.put(
                        t.get("tenantId").asText(), t.get("ring").asText()));
        assertThat(ringsByTenant.get(world.tenantA.toString())).isEqualTo("uat");
        assertThat(ringsByTenant.get(world.tenantB.toString())).isEqualTo("tenant-pilot");

        // A tenant that is not a target is the 422, not a silent skip.
        UUID stranger = createTenant(world.adminToken, world.org, "Promo Stranger Bank", "psb-stranger");
        JsonNode refused = promote(world.managerToken, sharedApp, release.id, List.of(stranger), null, 422);
        assertThat(refused.get("code").asText()).isEqualTo("RELEASE_TARGETS_INVALID");

        // Contoso follows later: the second selective hop lands the whole set
        // on uat (not the final ring: the release stays ACTIVE).
        JsonNode contoso = promote(world.managerToken, sharedApp, release.id, List.of(world.tenantB), null, 200);
        assertThat(contoso.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(contoso.get("crossedToPromoted").asBoolean()).isFalse();
    }

    // -----------------------------------------------------------------------
    // AC4: INV-008 — artifacts and manifest never mutate
    // -----------------------------------------------------------------------

    @Test
    void inv008ArtifactDigestsAndManifestSurviveEveryHopUnchanged() {
        World world = world("promo-inv008");
        Release release = activeReleaseAt(world, "qa", "promo-inv008");

        // The frozen manifest (the APPROVED crossing froze it on the walk).
        JsonNode manifestBefore =
                get(world.viewerToken, "/api/v1/releases/" + release.id + "/manifest?applicationId=" + world.app, 200);
        String digestBefore = manifestBefore.get("sha256").asText();
        List<String> shasBefore = artifactShasOf(release.id);

        promote(world.managerToken, world.app, release.id, null, null, 200);
        promote(world.managerToken, world.app, release.id, null, null, 200);

        JsonNode manifestAfter =
                get(world.viewerToken, "/api/v1/releases/" + release.id + "/manifest?applicationId=" + world.app, 200);
        assertThat(manifestAfter.get("sha256").asText()).isEqualTo(digestBefore);
        assertThat(manifestAfter.get("manifest").toString())
                .isEqualTo(manifestBefore.get("manifest").toString());
        assertThat(artifactShasOf(release.id)).isEqualTo(shasBefore);
        // Two hops, zero artifact writes: the count itself is pinned.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM release_artifacts WHERE release_id = ?", Integer.class, release.id))
                .isEqualTo(shasBefore.size());
    }

    // -----------------------------------------------------------------------
    // AC5: the history renders every hop with its actor
    // -----------------------------------------------------------------------

    @Test
    void promotionHistoryRecordsEveryHopAndEveryRefusalWithActors() {
        World world = world("promo-hist");
        createDefinition(world.adminToken, world.org, "smoke-test", "tenant-pilot", true, null, 201);
        Release release = activeReleaseAt(world, "qa", "promo-hist");

        promote(world.managerToken, world.app, release.id, null, null, 409);
        recordGateResult(
                world.managerToken,
                world.app,
                release.id,
                "smoke-test",
                "PASSED",
                "https://ci.internal/run/78",
                null,
                200);
        promote(world.managerToken, world.app, release.id, null, null, 200);

        JsonNode history = promotions(world.viewerToken, world.app, release.id);
        assertThat(history.get("promotions")).hasSize(2);
        assertThat(history.get("promotions").get(0).get("status").asText()).isEqualTo("BLOCKED");
        assertThat(history.get("promotions").get(0).get("toRing").asText()).isEqualTo("tenant-pilot");
        assertThat(history.get("promotions").get(1).get("status").asText()).isEqualTo("COMPLETED");
        assertThat(history.get("promotions").get(1).get("fromRing").asText()).isEqualTo("qa");
        for (JsonNode row : history.get("promotions")) {
            assertThat(row.get("actorId").asText()).isEqualTo(userIdOf(world.managerToken));
        }
    }

    // -----------------------------------------------------------------------
    // AC6 + PRD 17: one ring at a time; the final ring crossing
    // -----------------------------------------------------------------------

    @Test
    void finalRingHopCrossesActiveToPromotedWithTheMachinesCeremony() {
        World world = world("promo-final");
        Release release = activeReleaseAt(world, "uat", "promo-final");

        JsonNode result = promote(world.managerToken, world.app, release.id, null, "production", 200);
        assertThat(result.get("toRing").asText()).isEqualTo("production");
        assertThat(result.get("status").asText()).isEqualTo("PROMOTED");
        assertThat(result.get("crossedToPromoted").asBoolean()).isTrue();

        // The status timeline carries the crossing with its reason.
        JsonNode timeline =
                get(world.viewerToken, "/api/v1/releases/" + release.id + "/history?applicationId=" + world.app, 200);
        JsonNode last =
                timeline.get("transitions").get(timeline.get("transitions").size() - 1);
        assertThat(last.get("from").asText()).isEqualTo("ACTIVE");
        assertThat(last.get("to").asText()).isEqualTo("PROMOTED");
        assertThat(last.get("reason").asText()).contains("production");
        assertThat(audit.eventsOfType(AuditEventType.RELEASE_TRANSITIONED))
                .anyMatch(event -> "PROMOTED".equals(event.detail().get("toStatus")));
        assertThat(audit.eventsOfType(AuditEventType.RELEASE_PROMOTED))
                .anyMatch(event -> "true".equals(event.detail().get("crossedToPromoted")));

        // At the final ring there is nothing left to promote.
        JsonNode done = promote(world.managerToken, world.app, release.id, null, null, 409);
        assertThat(done.get("code").asText()).isEqualTo("RELEASE_PROMOTION_RING_FINAL");
    }

    @Test
    void skippingRingsIsDisallowedAndUnknownRingCodesAreThe400() {
        World world = world("promo-skip");
        Release release = activeReleaseAt(world, "qa", "promo-skip");

        JsonNode skip = promote(world.managerToken, world.app, release.id, null, "uat", 409);
        assertThat(skip.get("code").asText()).isEqualTo("RELEASE_PROMOTION_RING_SKIP");
        assertThat(skip.get("errors").get("next").asText()).isEqualTo("tenant-pilot");
        assertThat(skip.get("errors").get("named").asText()).isEqualTo("uat");

        JsonNode unknown = promote(world.managerToken, world.app, release.id, null, "no-such-ring", 400);
        assertThat(unknown.get("code").asText()).isEqualTo("INVALID_REQUEST");

        // The adjacent ring by name is the SAME hop: naming it succeeds.
        JsonNode adjacent = promote(world.managerToken, world.app, release.id, null, "tenant-pilot", 200);
        assertThat(adjacent.get("toRing").asText()).isEqualTo("tenant-pilot");
    }

    @Test
    void promotionServesActiveDistributionOnly() {
        World world = world("promo-state");
        Release release = createReleaseAt(world, "qa", "promo-state");
        JsonNode draft = promote(world.managerToken, world.app, release.id, null, null, 409);
        assertThat(draft.get("code").asText()).isEqualTo("RELEASE_PROMOTION_INVALID_STATE");
        assertThat(draft.get("errors").get("current").asText()).isEqualTo("DRAFT");
    }

    // -----------------------------------------------------------------------
    // Adversarial: the ROLLED_BACK cutoff, the in-tx re-run (TOCTOU),
    // and the ring CAS under concurrent hoppers
    // -----------------------------------------------------------------------

    @Test
    void rolledBackReleaseRefusesPromotionWithoutRecordingEvidence() {
        World world = world("promo-cutoff");
        Release release = activeReleaseAt(world, "qa", "promo-cutoff");

        // The operator pulls the release (PRD 17's ACTIVE -> ROLLED_BACK
        // edge demands its reason); live distribution is over.
        transition(world.managerToken, world.app, release.id, "ROLLED_BACK", "crash spike in the pilot", 200);

        JsonNode refused = promote(world.managerToken, world.app, release.id, null, null, 409);
        assertThat(refused.get("code").asText()).isEqualTo("RELEASE_PROMOTION_INVALID_STATE");
        assertThat(refused.get("errors").get("current").asText()).isEqualTo("ROLLED_BACK");

        // A pre-ring refusal is a state conflict, not governed evidence:
        // no timeline row, and the targets never moved.
        assertThat(promotions(world.viewerToken, world.app, release.id).get("promotions"))
                .isEmpty();
        assertThat(ringCodeOfTarget(release.id)).isEqualTo("qa");
    }

    @Test
    void stalePrecheckAnswerIsCaughtByTheInTransactionReRun() {
        World world = world("promo-toctou");
        Release release = activeReleaseAt(world, "qa", "promo-toctou");
        StaleLegPolicy.armedRelease = release.id;
        try {
            // The outer evaluation passes (the fixture's first answer);
            // the hop's in-transaction re-run sees the leg flip and must
            // answer the same 409 — never commit a hop a stale precheck
            // permitted.
            JsonNode refused = promote(world.managerToken, world.app, release.id, null, null, 409);
            assertThat(refused.get("code").asText()).isEqualTo("RELEASE_PROMOTION_BLOCKED");
            assertThat(refused.get("errors").get("StaleLegPolicy").asText()).contains("stale");

            // The evidence row survived the rollback (its own REQUIRES_NEW
            // transaction): the refusal is a governed fact with the stale
            // leg's blocker recorded.
            JsonNode history = promotions(world.viewerToken, world.app, release.id);
            assertThat(history.get("promotions")).hasSize(1);
            assertThat(history.get("promotions").get(0).get("status").asText()).isEqualTo("BLOCKED");
            assertThat(history.get("promotions")
                            .get(0)
                            .get("blockers")
                            .get("StaleLegPolicy")
                            .asText())
                    .contains("stale");

            // Nothing moved: the targets still sit on qa and the release
            // is still ACTIVE.
            assertThat(ringCodeOfTarget(release.id)).isEqualTo("qa");
            assertThat(history.get("status").asText()).isEqualTo("ACTIVE");

            // The read surface renders the same leg (every evaluation
            // after the first refuses): one evaluation, three surfaces.
            JsonNode precheck = precheck(world.viewerToken, world.app, release.id, 200);
            assertThat(precheck.get("promotable").asBoolean()).isFalse();
            assertThat(precheck.get("blockers").get("StaleLegPolicy").asText()).contains("stale");
        } finally {
            StaleLegPolicy.armedRelease = null;
            StaleLegPolicy.EVALUATIONS.set(0);
        }
    }

    @Test
    void concurrentWholeReleasePromotionsRaceTheRingCasConsistently() throws Exception {
        World world = world("promo-race");
        Release release = activeReleaseAt(world, "qa", "promo-race");

        // Two operators race the same whole-release hop off a common
        // barrier. Legal outcomes: exactly one hop commits and the loser
        // answers the precise CAS conflict, or the second attempt
        // re-reads the moved targets and commits the NEXT governed hop
        // (serialization, not duplication). Never: a partial move, a
        // duplicate hop, a 5xx, or a blocked row (no leg refuses).
        int hoppers = 2;
        CyclicBarrier start = new CyclicBarrier(hoppers);
        ExecutorService pool = Executors.newFixedThreadPool(hoppers);
        List<Recording> answers;
        try {
            List<Future<Recording>> attempts = new ArrayList<>();
            for (int i = 0; i < hoppers; i++) {
                attempts.add(pool.submit(() -> {
                    start.await(30, TimeUnit.SECONDS);
                    return promoteRaw(world.managerToken, world.app, release.id, null, null);
                }));
            }
            answers = new ArrayList<>();
            for (Future<Recording> attempt : attempts) {
                answers.add(attempt.get(60, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        int committed = 0;
        for (Recording answer : answers) {
            if (answer.status() == 200) {
                committed++;
            } else {
                // The loser's precise conflict: the CAS count guard, not
                // a generic failure and never a partial hop.
                assertThat(answer.status()).as("body %s", answer.body()).isEqualTo(409);
                assertThat(answer.body().get("code").asText()).isEqualTo("RELEASE_PROMOTION_CONFLICT");
            }
        }
        assertThat(committed).isGreaterThanOrEqualTo(1);

        // The timeline is consistent with the commits: one row per
        // committed hop, adjacent rings only, no blocked attempts.
        JsonNode history = promotions(world.viewerToken, world.app, release.id);
        List<String> hops = new ArrayList<>();
        for (JsonNode row : history.get("promotions")) {
            assertThat(row.get("status").asText()).isEqualTo("COMPLETED");
            hops.add(row.get("fromRing").asText() + ">" + row.get("toRing").asText());
        }
        assertThat(hops).hasSize(committed);
        assertThat(hops.get(0)).isEqualTo("qa>tenant-pilot");
        if (committed == 2) {
            assertThat(hops.get(1)).isEqualTo("tenant-pilot>uat");
        }

        // Every target sits on ONE ring — the last committed hop's — and
        // the release is still ACTIVE (neither hop is the final ring).
        assertThat(ringCodeOfTarget(release.id)).isEqualTo(committed == 1 ? "tenant-pilot" : "uat");
        assertThat(history.get("status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void concurrentFirstPromotesOnAPolicyGatedRingAnswerBlockedNeverA500() throws Exception {
        World world = world("promo-open-race");
        createWorkflow(world.adminToken, world.org, "pilot-promo", null, "QA_ENGINEER");
        patchRing(world.adminToken, world.org, "tenant-pilot", Map.of("approvalPolicy", "pilot-promo"));
        Release release = activeReleaseAt(world, "qa", "promo-open-race");

        // Two operators race the FIRST promote against the same
        // policy-gated ring off a common barrier: both consults see no
        // usable round, both try to open round 1, and the unique guard
        // admits exactly one insert. Legal outcomes: the loser of the
        // insert race answers from the committed state — the same
        // blocked 409 on the winner's PENDING round the sequential
        // second attempt gets — or the barrier interleaves loosely and
        // both read the one committed round. Never: a duplicate round,
        // a 5xx, or a moved target.
        int hoppers = 2;
        CyclicBarrier start = new CyclicBarrier(hoppers);
        ExecutorService pool = Executors.newFixedThreadPool(hoppers);
        List<Recording> answers;
        try {
            List<Future<Recording>> attempts = new ArrayList<>();
            for (int i = 0; i < hoppers; i++) {
                attempts.add(pool.submit(() -> {
                    start.await(30, TimeUnit.SECONDS);
                    return promoteRaw(world.managerToken, world.app, release.id, null, null);
                }));
            }
            answers = new ArrayList<>();
            for (Future<Recording> attempt : attempts) {
                answers.add(attempt.get(60, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        for (Recording answer : answers) {
            assertThat(answer.status()).as("body %s", answer.body()).isEqualTo(409);
            assertThat(answer.body().get("code").asText()).isEqualTo("RELEASE_PROMOTION_BLOCKED");
            // The blocked message's ROUND NUMBER is cosmetic under the
            // race: the loser answers from the committed state it
            // re-reads after losing the insert, and on a loaded runner
            // that read can number the pending round past the winner's
            // (#62/#63's documented promotion-round CI flake — observed
            // again on #137's first run). The strong invariants are the
            // ones below: the governed refusal and the round-set
            // invariants under every legal interleaving.
            assertThat(answer.body().get("errors").get("approval").asText())
                    .contains("pilot-promo")
                    .contains("round ")
                    .contains("pending");
        }

        // The round set under every legal interleaving: the common case
        // is the winner's single PENDING round. The loser's consult
        // reads the ring-marked round and the next number in two
        // separate statements — when the winner's commit lands BETWEEN
        // them the loser opens a DISTINCT next round before blocking on
        // it (#62/#63's promotion-round flake, observed twice on #137:
        // first as the loser's re-numbered message, then as the second
        // round row). The invariants that hold in both worlds: every
        // round number is distinct, the latest round is PENDING (the
        // winner's demanded step), no duplicate or partial round ever
        // landed, and a serialized retry would answer from it.
        JsonNode rounds =
                get(world.viewerToken, "/api/v1/releases/" + release.id + "/approvals?applicationId=" + world.app, 200);
        assertThat(rounds.get("rounds").size()).isBetween(1, 2);
        java.util.Set<Integer> numbers = new java.util.LinkedHashSet<>();
        for (JsonNode round : rounds.get("rounds")) {
            numbers.add(round.get("roundNumber").asInt());
        }
        assertThat(numbers)
                .as("no duplicate round number ever lands")
                .hasSize(rounds.get("rounds").size());
        assertThat(numbers).contains(1);
        JsonNode latest = rounds.get("rounds").get(rounds.get("rounds").size() - 1);
        assertThat(latest.get("roundNumber").asInt())
                .isEqualTo(numbers.stream().max(Integer::compare).orElseThrow());
        assertThat(latest.get("status").asText()).isEqualTo("PENDING");

        // Both refusals are governed evidence; nothing moved.
        JsonNode history = promotions(world.viewerToken, world.app, release.id);
        assertThat(history.get("promotions")).hasSize(2);
        for (JsonNode row : history.get("promotions")) {
            assertThat(row.get("status").asText()).isEqualTo("BLOCKED");
        }
        assertThat(ringCodeOfTarget(release.id)).isEqualTo("qa");
        assertThat(history.get("status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void rollbackCommittingInsideTheHopWindowRefusesInvalidStateAndMovesNothing() {
        World world = world("promo-rb-race");
        Release release = activeReleaseAt(world, "qa", "promo-rb-race");
        RollbackRacePolicy.armedRelease = release.id;
        RollbackRacePolicy.onRevalidation =
                () -> transition(world.managerToken, world.app, release.id, "ROLLED_BACK", "crash spike mid-hop", 200);
        try {
            // The outer evaluation passes (the fixture's first answer);
            // the operator's rollback commits between that read and the
            // hop's compare-and-set (the fixture drives it from inside
            // the in-transaction revalidation): the hop must refuse on
            // the committed status — never move rings, record a hop,
            // or audit a promotion after a rollback.
            JsonNode refused = promote(world.managerToken, world.app, release.id, null, null, 409);
            assertThat(refused.get("code").asText()).isEqualTo("RELEASE_PROMOTION_INVALID_STATE");
            assertThat(refused.get("errors").get("current").asText()).isEqualTo("ROLLED_BACK");
        } finally {
            RollbackRacePolicy.armedRelease = null;
            RollbackRacePolicy.onRevalidation = null;
            RollbackRacePolicy.EVALUATIONS.set(0);
        }

        // Nothing the rollback voided survived: the targets never moved,
        // no timeline row exists (a state refusal is not governed
        // evidence — the pre-ring doctrine), and no promotion was
        // audited; the release stands ROLLED_BACK.
        assertThat(ringCodeOfTarget(release.id)).isEqualTo("qa");
        assertThat(promotions(world.viewerToken, world.app, release.id).get("promotions"))
                .isEmpty();
        assertThat(audit.eventsOfType(AuditEventType.RELEASE_PROMOTED)).isEmpty();
        JsonNode detail = get(world.viewerToken, "/api/v1/releases/" + release.id + "?applicationId=" + world.app, 200);
        assertThat(detail.get("status").asText()).isEqualTo("ROLLED_BACK");
    }

    // -----------------------------------------------------------------------
    // Confinement: the concealed miss, and the permission split
    // -----------------------------------------------------------------------

    @Test
    void foreignReleaseConcealsExactlyLikeAnUnknownId() {
        World world = world("promo-hide");
        World other = world("promo-hide-other");
        Release release = activeReleaseAt(other, "qa", "promo-hide-other");

        String foreignPromote = promoteRaw(world.managerToken, other.app, release.id, null, null)
                .body()
                .toString();
        String missPromote = promoteRaw(world.managerToken, other.app, UUID.randomUUID(), null, null)
                .body()
                .toString();
        assertThat(fixedTraceOf(foreignPromote)).isEqualTo(fixedTraceOf(missPromote));

        String foreignPrecheck = getRaw(
                        world.viewerToken,
                        "/api/v1/releases/" + release.id + "/promotion-precheck?applicationId=" + other.app)
                .body()
                .toString();
        String missPrecheck = getRaw(
                        world.viewerToken,
                        "/api/v1/releases/" + UUID.randomUUID() + "/promotion-precheck?applicationId=" + other.app)
                .body()
                .toString();
        assertThat(fixedTraceOf(foreignPrecheck)).isEqualTo(fixedTraceOf(missPrecheck));

        String foreignHistory =
                promotionsRaw(world.viewerToken, other.app, release.id).body().toString();
        String missHistory = promotionsRaw(world.viewerToken, other.app, UUID.randomUUID())
                .body()
                .toString();
        assertThat(fixedTraceOf(foreignHistory)).isEqualTo(fixedTraceOf(missHistory));
    }

    @Test
    void readsAnswerReadersAndThePromoteActDemandsManage() {
        World world = world("promo-rbac");
        Release release = activeReleaseAt(world, "qa", "promo-rbac");

        precheck(world.viewerToken, world.app, release.id, 200);
        promotions(world.viewerToken, world.app, release.id);
        JsonNode denied = promote(world.viewerToken, world.app, release.id, null, null, 404);
        assertThat(denied.get("code").asText()).isEqualTo("APPLICATION_NOT_FOUND");
        promote(world.managerToken, world.app, release.id, null, null, 200);
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    /** One organization's fixture: the actors, the first tenant, and the application under test. */
    private static final class World {
        UUID org;
        UUID tenantA;
        UUID tenantB;
        UUID app;
        String adminToken;
        String ownerToken;
        String managerToken;
        String qaToken;
        String viewerToken;
    }

    private record Release(UUID id, String ref) {}

    private World world(String slug) {
        World world = new World();
        world.org = createOrg("Promotion " + slug + " Corp");
        world.adminToken = loginOfOrgRoleIn(world.org, slug + "-admin", "ORG_ADMIN");
        world.ownerToken = loginOfOrgRoleIn(world.org, slug + "-owner", "APP_OWNER");
        world.managerToken = loginOfOrgRoleIn(world.org, slug + "-manager", "RELEASE_MANAGER");
        world.qaToken = loginOfOrgRoleIn(world.org, slug + "-qa", "QA_ENGINEER");
        world.viewerToken = loginOfOrgRoleIn(world.org, slug + "-viewer", "VIEWER");
        world.tenantA = createTenant(world.adminToken, world.org, "Promotion " + slug + " Bank", "pmo-" + slug);
        world.app = createTenantApp(
                world.adminToken,
                world.tenantA,
                "Promotion " + slug + " Banking",
                "com." + slug.replace("-", "") + ".banking");
        return world;
    }

    /** A release walked to ACTIVE through the ungated machine (the promotion runway). */
    private Release activeReleaseAt(World world, String ring, String slug) {
        return activeReleaseWithTargets(
                world,
                world.app,
                ring,
                List.of(Map.of("tenantId", world.tenantA.toString(), "testerGroupIds", List.of())),
                slug);
    }

    private Release activeReleaseWithTargets(
            World world, UUID applicationId, String ring, List<Map<String, Object>> targets, String slug) {
        Release release = createReleaseFor(world.ownerToken, world.managerToken, applicationId, ring, targets, slug);
        for (String to : List.of("VALIDATING", "READY", "APPROVAL_PENDING", "APPROVED", "DEPLOYING", "ACTIVE")) {
            transition(world.managerToken, applicationId, release.id, to, 200);
        }
        return release;
    }

    private Release createReleaseAt(World world, String ring, String slug) {
        return createReleaseFor(
                world.ownerToken,
                world.managerToken,
                world.app,
                ring,
                List.of(Map.of("tenantId", world.tenantA.toString(), "testerGroupIds", List.of())),
                slug);
    }

    private Release createReleaseFor(
            String ownerToken,
            String managerToken,
            UUID applicationId,
            String ring,
            List<Map<String, Object>> targets,
            String slug) {
        String name = String.format("%d.%d.%d", 3 + versionCounter / 10, versionCounter % 10, 7);
        versionCounter++;
        UUID version = createVersion(ownerToken, applicationId, name);
        UUID artifact = insertArtifact(version, "IOS", versionCounter);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", applicationId.toString());
        body.put("versionId", version.toString());
        body.put("ring", ring);
        body.put("artifacts", List.of(Map.of("platform", "IOS", "artifactId", artifact.toString())));
        body.put("targets", targets);
        JsonNode created = post(managerToken, "/api/v1/releases", body, 201);
        return new Release(
                UUID.fromString(created.get("id").asText()),
                created.get("releaseRef").asText());
    }

    private JsonNode transition(String bearer, UUID applicationId, UUID releaseId, String to, int expected) {
        return transition(bearer, applicationId, releaseId, to, null, expected);
    }

    private JsonNode transition(
            String bearer, UUID applicationId, UUID releaseId, String to, String reason, int expected) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", applicationId.toString());
        body.put("to", to);
        if (reason != null) {
            body.put("reason", reason);
        }
        return post(bearer, "/api/v1/releases/" + releaseId + "/transitions", body, expected);
    }

    /** The single-target fixture's current ring code (the CAS's ground truth). */
    private String ringCodeOfTarget(UUID releaseId) {
        return jdbc.queryForObject(
                "SELECT r.code FROM release_targets rt JOIN rings r ON r.id = rt.ring_id " + "WHERE rt.release_id = ?",
                String.class,
                releaseId);
    }

    private JsonNode promote(
            String bearer, UUID applicationId, UUID releaseId, List<UUID> tenants, String to, int expected) {
        return promoteRaw(bearer, applicationId, releaseId, tenants, to).expect(expected);
    }

    private Recording promoteRaw(String bearer, UUID applicationId, UUID releaseId, List<UUID> tenants, String to) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", applicationId.toString());
        if (tenants != null) {
            body.put("tenants", tenants.stream().map(UUID::toString).toList());
        }
        if (to != null) {
            body.put("to", to);
        }
        return exchange("/api/v1/releases/" + releaseId + "/promote", bearer, "POST", body);
    }

    private JsonNode precheck(String bearer, UUID applicationId, UUID releaseId, int expected) {
        return getRaw(bearer, "/api/v1/releases/" + releaseId + "/promotion-precheck?applicationId=" + applicationId)
                .expect(expected);
    }

    private JsonNode promotions(String bearer, UUID applicationId, UUID releaseId) {
        return promotionsRaw(bearer, applicationId, releaseId).expect(200);
    }

    private Recording promotionsRaw(String bearer, UUID applicationId, UUID releaseId) {
        return getRaw(bearer, "/api/v1/releases/" + releaseId + "/promotions?applicationId=" + applicationId);
    }

    private void recordGateResult(
            String bearer,
            UUID applicationId,
            UUID releaseId,
            String gateType,
            String status,
            String evidenceUrl,
            Double observed,
            int expected) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", applicationId.toString());
        body.put("status", status);
        body.put("evidenceUrl", evidenceUrl);
        if (observed != null) {
            body.put("observedValue", observed);
        }
        post(bearer, "/api/v1/releases/" + releaseId + "/gates/" + gateType + "/result", body, expected);
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

    /** Edits one of the organization's seeded rings by code (the fixture's ring-handle). */
    private JsonNode patchRing(String token, UUID organizationId, String code, Map<String, Object> fields) {
        UUID ringId = jdbc.queryForObject(
                "SELECT id FROM rings WHERE organization_id = ? AND code = ?", UUID.class, organizationId, code);
        return patch(token, "/api/v1/rings/" + ringId + "?organizationId=" + organizationId, fields, 200);
    }

    private List<String> artifactShasOf(UUID releaseId) {
        return jdbc.queryForList(
                "SELECT a.sha256 FROM artifacts a JOIN release_artifacts ra ON ra.artifact_id = a.id "
                        + "WHERE ra.release_id = ? ORDER BY a.id",
                String.class,
                releaseId);
    }

    private String userIdOf(String token) {
        return String.valueOf(subjectOf(token));
    }

    private String subjectOf(String token) {
        String[] parts = token.split("\\.");
        String payload = parts[1].replace('-', '+').replace('_', '/');
        java.util.Base64.Decoder decoder = java.util.Base64.getDecoder();
        try {
            JsonNode claims =
                    mapper.readTree(new String(decoder.decode(payload), java.nio.charset.StandardCharsets.UTF_8));
            return claims.get("sub").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Strips the per-request trace id and the instance path (which
     * carries the addressed id) so two problem bodies compare
     * field-for-field — the matrix's rule, extended to POST routes.
     */
    private static String fixedTraceOf(String problemJson) {
        return problemJson
                .replaceAll("\"trace_id\"\\s*:\\s*\"[^\"]*\"", "\"trace_id\":\"-\"")
                .replaceAll("\"instance\"\\s*:\\s*\"[^\"]*\"", "\"instance\":\"-\"");
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
                "b2".repeat(32),
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
                        "Promotion " + role))
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

    private JsonNode get(String bearer, String uri, int expectedStatus) {
        return exchange(uri, bearer, "GET", null).expect(expectedStatus);
    }

    private Recording getRaw(String bearer, String uri) {
        return exchange(uri, bearer, "GET", null);
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
