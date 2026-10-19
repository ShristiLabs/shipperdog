package com.shipperdog.api.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The cross-tenant access matrix (S7-05, issue #34 acceptance
 * criterion 1; INV-001; the M1 exit criterion "a second tenant cannot
 * observe any artifact/version/application of the first"): every
 * (actor persona x tenant x endpoint) combination over the ENTIRE M1
 * read surface — enumerated from {@link IsolationEndpointRegistry},
 * never by hand — must answer correctly:
 *
 * <ul>
 *   <li><strong>Own side answers</strong> — the owning org admin and
 *       tenant admin personas receive the route's documented answer on
 *       their own data (200, or the documented own-shape miss/403).</li>
 *   <li><strong>Foreign side conceals</strong> — the same personas
 *       addressing the OTHER tenant's data always receive the concealed
 *       404, and its body is byte-identical to the genuine miss for
 *       the same actor and route (negative space: unauthorized
 *       existence must not leak — response-shape parity between
 *       404-not-exists and 404-not-authorized, PRD 12).</li>
 *   <li><strong>No data in any denial</strong> — no foreign-side
 *       identifier, name, or slug appears in any denied body.</li>
 *   <li><strong>The platform override reads across</strong> — the
 *       platform-administrator wildcard reaches the other tenant's
 *       read surfaces (the sanctioned, audited override; the plain
 *       self-scoped decision API still denies the persona, proving
 *       the portal surface never rides RBAC).</li>
 *   <li><strong>The tester persona conceals too</strong> — side A's
 *       tester person cannot observe side B through the portal-grade
 *       decision API.</li>
 * </ul>
 *
 * <p>The matrix IS the milestone exit criterion's automated half; the
 * manual half is the deployed-environment demonstration recorded in
 * the milestone review.
 */
class CrossTenantAccessMatrixTest extends IsolationScenarioHarness {

    @Test
    void everyActorPersonaEveryEndpointCrossTenantReturnsZeroLeaks() throws Exception {
        World world = world();
        IsolationScenarioHarness.IsolatedTenant sideA = world.sideA();
        IsolationScenarioHarness.IsolatedTenant sideB = world.sideB();

        List<String> violations = new ArrayList<>();
        for (IsolationEndpointRegistry.Entry entry : IsolationEndpointRegistry.readSurfaces()) {
            // Own side: each persona receives the documented answer.
            expectOwn(entry, sideA, sideA.orgAdminToken(), "org-admin", entry.ownOrgAdmin(), violations);
            expectOwn(entry, sideA, sideA.tenantAdminToken(), "tenant-admin", entry.ownTenantAdmin(), violations);

            // Platform wildcard: reads the other side (sanctioned
            // override), except the self-scoped decision surfaces the
            // engine decides per subject (no RBAC persona privileges).
            // The decision API's download is engine-decided per
            // subject: the platform persona passes the engine's own
            // wildcard (ALLOW), the tenant/org personas without
            // grants conceal.
            int platformExpected = switch (entry.template()) {
                case "/api/v1/authorization/decisions/download" -> 200;
                case "/api/v1/authorization/decisions/eligibility" -> 200;
                case "/api/v1/audit" -> 200;
                case "/api/v1/artifacts/{artifactId}" -> 200;
                // The S8-04 issuance route: engine-decided like the
                // decision API (no RBAC gate), so the wildcard's
                // ALLOW mints the URL — while org/tenant personas
                // without a download entitlement conceal (the
                // entry's own-side 404s above).
                case "/api/v1/artifacts/{artifactId}/download" -> 200;
                // The OTA bundle listing (S16-03): an RBAC-gated
                // console read like the release listing — the org and
                // tenant personas lack the artifact family's read
                // permission (the entry's own-side 404s), while the
                // wildcard passes it and receives the honest empty
                // page; the seeded world holds no bundle.
                case "/api/v1/ota/bundles" -> 200;
                // The sdc1. app-token registry's listing (S16-04):
                // the same RBAC-gated console read as the bundle
                // listing — the wildcard passes the artifact family's
                // read permission and receives the honest empty page
                // (the seeded world holds no token; the registry is
                // hash-only, so an empty page is all it could leak).
                case "/api/v1/ota/app-tokens" -> 200;
                // The OTA alert-rule listing (S16-11): the same
                // RBAC-gated console read shape as the bundle listing —
                // the wildcard passes the release family's read
                // permission and receives the honest empty page (the
                // seeded world holds no rule).
                case "/api/v1/ota/alert-rules" -> 200;
                default -> entry.ownOrgAdmin();
            };
            expectOwn(entry, sideB, sideA.platformAdminToken(), "platform-admin", platformExpected, violations);

            // Foreign side: the tenant persona conceals, byte-shaped
            // like the genuine miss for the SAME actor and route.
            concealedLikeGenuineMiss(entry, sideA, sideA.tenantAdminToken(), "tenant-admin", sideB, violations);
            concealedLikeGenuineMiss(entry, sideA, sideA.orgAdminToken(), "org-admin", sideB, violations);

            // The portal persona: side A's tester cannot observe side B
            // through the decision surfaces (the matrix covers every
            // entry; the engine self-scope makes the two decision
            // routes the meaningful ones, the rest deny by RBAC).
            concealedLikeGenuineMiss(entry, sideA, sideA.testerToken(), "tester", sideB, violations);
        }

        assertThat(violations)
                .as("the full actor x tenant x endpoint matrix returned zero violations (issue #34 AC1)")
                .isEmpty();
    }

    /** The own-side call must land on the documented status. */
    private void expectOwn(
            IsolationEndpointRegistry.Entry entry,
            IsolationScenarioHarness.IsolatedTenant side,
            String token,
            String persona,
            int expected,
            List<String> violations) {
        Recording answer = getRaw(token, entry.uri(side));
        if (answer.status() != expected) {
            violations.add("own-side " + entry.name() + " as " + persona + ": expected " + expected + " but got "
                    + answer.status() + " (" + answer.body() + ")");
        }
    }

    /**
     * The foreign attempt must be the concealed 404, byte-identical to
     * the genuine miss for the same actor and route (modulo trace id),
     * and must not mention any foreign-side identifier anywhere.
     */
    private void concealedLikeGenuineMiss(
            IsolationEndpointRegistry.Entry entry,
            IsolationScenarioHarness.IsolatedTenant actorSide,
            String token,
            String actor,
            IsolationScenarioHarness.IsolatedTenant targetSide,
            List<String> violations) {
        Recording denied = getRaw(token, entry.uri(targetSide));
        if (denied.status() != entry.concealStatus()) {
            violations.add("foreign " + entry.name() + " as " + actor + ": expected the concealed "
                    + entry.concealStatus() + " but got " + denied.status() + " (" + denied.body() + ")");
            return;
        }
        Recording genuineMiss = getRaw(token, entry.missUri(actorSide));
        String deniedBody = withoutTraceId(denied.body().toString());
        String missBody = withoutTraceId(genuineMiss.body().toString());
        if (genuineMiss.status() != entry.concealStatus()) {
            violations.add("foreign " + entry.name() + " as " + actor + ": the genuine miss must render "
                    + entry.concealStatus() + " too, but got " + genuineMiss.status());
        }
        if (!deniedBody.equals(missBody)) {
            violations.add("foreign " + entry.name() + " as " + actor
                    + ": the concealed denial must be byte-identical to the genuine miss, but was:\n  denied: "
                    + deniedBody + "\n  miss:    " + missBody);
        }
        for (String leak : leaksOf(targetSide)) {
            if (deniedBody.contains(leak)) {
                violations.add("foreign " + entry.name() + " as " + actor + ": denied body leaks '" + leak + "'");
            }
        }
    }

    /** Every identifier and name of the target side a body must never carry. */
    private static List<String> leaksOf(IsolationScenarioHarness.IsolatedTenant side) {
        return List.of(
                side.slug(),
                side.tenantId().toString(),
                side.organizationId().toString(),
                side.applicationId().toString(),
                side.versionId().toString(),
                side.artifactId().toString(),
                side.groupId().toString(),
                side.testerId().toString(),
                side.tenantAdminUserId().toString(),
                side.testerEmail());
    }
}
