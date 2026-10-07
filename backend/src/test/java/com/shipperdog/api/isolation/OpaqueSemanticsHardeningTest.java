package com.shipperdog.api.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * The opaque-semantics hardening proofs folded into this story (issue
 * #34; the candidates carried from #26-#28 and the #33 review): every
 * read surface — including the platform wildcard's — answers with the
 * concealed 404 shape or the route's real answer, NEVER a 500, and
 * every concealed denial renders its owning module's miss code. These
 * are the four pinned defects:
 *
 * <ol>
 *   <li><strong>Platform wildcard on unknown applications</strong>
 *       (carried from #27): the wildcard passes the permission check
 *       before the route's application interceptor has anything to
 *       bind, and the seam is fail-closed — the routes must render the
 *       concealed application/version miss, not a 500 (the tester
 *       groups' mustFindGroup precedent applied to the application
 *       and version surfaces).</li>
 *   <li><strong>'tester'-grain concealed denials</strong> (carried
 *       from #26/#27): a concealed denial on a tester target renders
 *       the tester module's own miss code TESTER_NOT_FOUND, never the
 *       generic fallback (which would leak WHICH check failed).</li>
 *   <li><strong>The assignRole insert race</strong> (carried from
 *       #26/#27): concurrent role assignments creating the same
 *       membership collapse to the winner — 200s only, never the
 *       dead-catch 500 (the #28 addMember discipline held on the
 *       addMember route; this pins its sibling).</li>
 *   <li><strong>The grant-create triple race</strong> (#33 review):
 *       two concurrent creates of a re-grant racing a revocation —
 *       the loser's constraint-recovery may find no ACTIVE winner
 *       (the winner was revoked in between); the answer is the stable
 *       409 CONFLICT, never a 500.</li>
 * </ol>
 */
class OpaqueSemanticsHardeningTest extends IsolationScenarioHarness {

    @Test
    void thePlatformWildcardReceivesTheConcealedMissOnUnknownApplicationsAndVersions() throws Exception {
        IsolationScenarioHarness.IsolatedTenant side = world().sideA();
        String platform = side.platformAdminToken();
        UUID unknown = UUID.randomUUID();

        List<String> probes = List.of(
                "/api/v1/applications/" + unknown,
                "/api/v1/applications/" + unknown + "/tenants",
                "/api/v1/applications/" + unknown + "/versions",
                "/api/v1/applications/" + unknown + "/versions/" + UUID.randomUUID(),
                "/api/v1/applications/" + unknown + "/versions/" + UUID.randomUUID() + "/history");

        List<String> violations = new ArrayList<>();
        for (String probe : probes) {
            Recording answer = getRaw(platform, probe);
            if (answer.status() != 404) {
                violations.add("platform wildcard on " + probe + ": expected the concealed 404, got " + answer.status()
                        + " (" + answer.body() + ")");
                continue;
            }
            Recording miss = getRaw(platform, "/api/v1/applications/" + UUID.randomUUID());
            // The application-grain probes render the application
            // module's miss; the version-nested probes render the
            // version module's own miss for the unknown APPLICATION
            // (APPLICATION_NOT_FOUND is the version module's
            // application-miss code).
            String code = answer.body().get("code").asString();
            if (!code.equals("APPLICATION_NOT_FOUND")) {
                violations.add("platform wildcard on " + probe + ": expected APPLICATION_NOT_FOUND, got " + code);
            }
            if (!withoutTraceId(answer.body().toString())
                    .equals(withoutTraceId(miss.body().toString()))) {
                violations.add("platform wildcard on " + probe
                        + ": the miss must be byte-identical to the application module's genuine miss");
            }
        }
        assertThat(violations)
                .as("carried hardening from #27: no platform-wildcard 500s")
                .isEmpty();

        // The PATCH write path carries the same guard (the load
        // happens before any change).
        Recording patch =
                exchange("/api/v1/applications/" + unknown, platform, HttpMethod.PATCH, Map.of("name", "Probe"));
        assertThat(patch.status())
                .as("PATCH on an unknown application conceals")
                .isEqualTo(404);
    }

    @Test
    void testerGrainConcealedDenialsRenderTheTesterModulesMissCode() throws Exception {
        World world = world();
        IsolationScenarioHarness.IsolatedTenant sideA = world.sideA();
        IsolationScenarioHarness.IsolatedTenant sideB = world.sideB();

        // A foreign tester (side B's) read by side A's org admin: a
        // concealed denial on a 'tester' target.
        Recording foreign = getRaw(sideA.orgAdminToken(), "/api/v1/testers/" + sideB.testerId());
        Recording miss = getRaw(sideA.orgAdminToken(), "/api/v1/testers/" + UUID.randomUUID());

        assertThat(foreign.status()).isEqualTo(404);
        assertThat(foreign.body().get("code").asString())
                .as("carried hardening from #26/#27: the tester grain renders the tester module's miss code")
                .isEqualTo("TESTER_NOT_FOUND");
        assertThat(withoutTraceId(foreign.body().toString()))
                .isEqualTo(withoutTraceId(miss.body().toString()));

        // The same grain through the org-listing surface: a foreign
        // organization's tester listing conceals at the organization.
        Recording foreignOrg =
                getRaw(sideA.orgAdminToken(), "/api/v1/testers?organizationId=" + sideB.organizationId());
        Recording missOrg = getRaw(sideA.orgAdminToken(), "/api/v1/testers?organizationId=" + UUID.randomUUID());
        assertThat(foreignOrg.status()).isEqualTo(404);
        assertThat(withoutTraceId(foreignOrg.body().toString()))
                .isEqualTo(withoutTraceId(missOrg.body().toString()));
    }

    @Test
    void concurrentAssignRoleCreationsCollapseToTheWinnerNeverA500() throws Exception {
        IsolationScenarioHarness.IsolatedTenant side = world().sideA();
        String email = "suite-hardening-role-" + UUID.randomUUID() + "@example.com";
        UUID userId = userIdByEmail(insertOrglessUser(email));
        String roleUri = "/api/v1/tenants/" + side.tenantId() + "/members/" + userId + "/role";

        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            for (int round = 0; round < 3; round++) {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Recording>> futures = new ArrayList<>();
                for (int i = 0; i < 6; i++) {
                    futures.add(pool.submit(() -> {
                        start.await();
                        return exchange(
                                roleUri, side.tenantAdminToken(), HttpMethod.PUT, Map.of("role", "TENANT_ADMIN"));
                    }));
                }
                start.countDown();
                List<Integer> statuses = new ArrayList<>();
                for (Future<Recording> future : futures) {
                    statuses.add(future.get(30, TimeUnit.SECONDS).status());
                }
                assertThat(statuses)
                        .as("round %d: racing assignRole creations answer 200, never a 500", round)
                        .containsOnly(200);
                assertThat(jdbc.queryForObject(
                                "SELECT count(*) FROM tenant_members WHERE tenant_id = ? AND user_id = ?",
                                Integer.class,
                                side.tenantId(),
                                userId))
                        .as("round %d: exactly one membership row", round)
                        .isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void grantCreateRacingRevocationAnswersStableCodesNeverA500() throws Exception {
        IsolationScenarioHarness.IsolatedTenant side = world().sideA();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (int round = 0; round < 3; round++) {
                // A prior round may have left an ACTIVE grant of the
                // same identity (its revoke hit the round-1 row while a
                // racing create minted a new one): the seed create
                // accepts both the mint and the idempotent replay.
                Recording seeded = postRaw(
                        side.tenantAdminToken(),
                        grantsPath(side.tenantId()),
                        java.util.Map.of(
                                "testerId", side.testerId().toString(),
                                "applicationId", side.applicationId().toString(),
                                "accessType", "EXPLICIT_VERSION",
                                "versionId", side.versionId().toString(),
                                "reason", "race seed"));
                assertThat(seeded.status()).as("round seed: %s", seeded.body()).isIn(200, 201);
                UUID grantId =
                        UUID.fromString(seeded.body().get("grant").get("id").asText());

                CountDownLatch start = new CountDownLatch(1);
                int currentRound = round;
                List<Future<Recording>> futures = new ArrayList<>();
                futures.add(pool.submit(() -> {
                    start.await();
                    return exchange(
                            grantUri(side.tenantId(), grantId) + "?reason=round-" + currentRound,
                            side.tenantAdminToken(),
                            HttpMethod.DELETE,
                            null);
                }));
                for (int i = 0; i < 2; i++) {
                    futures.add(pool.submit(() -> {
                        start.await();
                        return postRaw(
                                side.tenantAdminToken(),
                                grantsPath(side.tenantId()),
                                Map.of(
                                        "testerId", side.testerId().toString(),
                                        "applicationId", side.applicationId().toString(),
                                        "accessType", "EXPLICIT_VERSION",
                                        "versionId", side.versionId().toString(),
                                        "reason", "race round"));
                    }));
                }
                start.countDown();
                for (Future<Recording> future : futures) {
                    Recording answer = future.get(30, TimeUnit.SECONDS);
                    assertThat(answer.status())
                            .as(
                                    "round %d: the create/revocation race answers a stable code (200 replay,"
                                            + " 201 mint, 204 revoke, or 409 concurrent-state), never a 500: %s",
                                    round, answer.body())
                            .isIn(200, 201, 204, 409);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
