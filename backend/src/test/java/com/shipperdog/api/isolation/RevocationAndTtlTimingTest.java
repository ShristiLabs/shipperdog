package com.shipperdog.api.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * INV-007's timing proof (S7-05, issue #34 acceptance criterion 4):
 * revocation is effective on the very NEXT decision — no TTL window of
 * lingering access — and authorization lifetimes are bounded by the
 * grant's expiry. Pinned shapes:
 *
 * <ul>
 *   <li><strong>Revocation immediacy through a warm cache</strong>:
 *       the grant-set family is cached; an ALLOWED decision warms it;
 *       revoking drops the key write-through so the NEXT download and
 *       eligibility decisions deny immediately.</li>
 *   <li><strong>Expiry bounds the grant</strong>: a short-TTL grant
 *       decides ALLOW while live and flips to the concealed denial at
 *       its expiry — evaluation-time, nothing swept; the revocation
 *       report lists it as EXPIRED with the expiry timestamp.</li>
 *   <li><strong>URLs are bounded by TTL</strong>: since S8-04 the
 *       issuance route mints signed URLs whose TTL sits at or under
 *       the cache TTL (the startup-enforced bound, both 15-minute
 *       defaults) — so a revoked grant blocks the NEXT issuance
 *       immediately (write-through) and no URL outlives the
 *       degraded-mode staleness backstop. The URL half is pinned by
 *       the S8-04 download suite; here the grant-side immediacy is
 *       the proof.</li>
 *   <li><strong>Bulk revocation is immediate for the whole set</strong>
 *       (the folded #32/#33 follow-up): one pass, next decisions deny
 *       for every formerly granted version.</li>
 * </ul>
 */
class RevocationAndTtlTimingTest extends IsolationScenarioHarness {

    @Test
    void revocationBlocksTheVeryNextDecisionThroughAWarmCache() throws Exception {
        IsolationScenarioHarness.IsolatedTenant side = world().sideA();
        UUID grantId = grantExplicitVersion(
                side.tenantAdminToken(), side.tenantId(), side.testerId(), side.applicationId(), side.versionId());

        // Warm the cache: two ALLOWED decisions prove the hit leg.
        double hitsBefore = cacheHits("grants");
        get(side.testerToken(), downloadUri(side.tenantId(), side.versionId()), 200);
        get(side.testerToken(), downloadUri(side.tenantId(), side.versionId()), 200);
        assertThat(cacheHits("grants")).isGreaterThan(hitsBefore);

        revokeGrant(side.tenantAdminToken(), side.tenantId(), grantId, "suite-timing");

        // The NEXT decisions deny — immediacy, not eventual.
        assertThat(codeOf(getRaw(side.testerToken(), downloadUri(side.tenantId(), side.versionId()))))
                .isEqualTo("VERSION_NOT_FOUND");
        assertThat(get(side.testerToken(), eligibilityUri(side.tenantId(), side.applicationId()), 200)
                        .get("entries")
                        .size())
                .isZero();
    }

    @Test
    void aShortTtlGrantFlipsToDeniedAtItsExpiryAndLandsInTheReport() throws Exception {
        IsolationScenarioHarness.IsolatedTenant side = world().sideA();
        // Microsecond-representable on purpose (caught by CI, not by the
        // macOS dev clock): PostgreSQL timestamps carry microsecond
        // precision, so a nanosecond-precision request — what Linux's
        // clock produces — round-trips TRUNCATED and an exact-equality
        // assertion would flake by platform. Requesting a value the
        // store can hold keeps the assertion exact and the test
        // deterministic everywhere.
        Instant expiresAt = Instant.now().plusSeconds(3).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        UUID grantId = grantExplicitVersion(
                side.tenantAdminToken(),
                side.tenantId(),
                side.testerId(),
                side.applicationId(),
                side.versionId(),
                expiresAt.toString());

        // Live: ALLOW, and the eligibility entry carries the TTL.
        get(side.testerToken(), downloadUri(side.tenantId(), side.versionId()), 200);
        var entry = get(side.testerToken(), eligibilityUri(side.tenantId(), side.applicationId()), 200)
                .get("entries")
                .get(0);
        assertThat(Instant.parse(entry.get("expiresAt").asString())).isEqualTo(expiresAt);

        // At expiry the flip is evaluation-time: poll the decision,
        // bounded, until the concealed denial arrives.
        boolean flipped = false;
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (getRaw(side.testerToken(), downloadUri(side.tenantId(), side.versionId()))
                            .status()
                    == 404) {
                flipped = true;
                break;
            }
            Thread.sleep(250);
        }
        assertThat(flipped)
                .as("the expired grant denies at its expiry (evaluation-time)")
                .isTrue();

        // The report lists the loss as EXPIRED with both directions of
        // provenance — nothing was swept.
        var report = get(
                side.tenantAdminToken(),
                grantsPath(side.tenantId()) + "/revocation-report?testerId=" + side.testerId(),
                200);
        var lost = report.get("items").get(0);
        assertThat(lost.get("id").asText()).isEqualTo(grantId.toString());
        assertThat(lost.get("state").asString()).isEqualTo("EXPIRED");
        assertThat(Instant.parse(lost.get("expiresAt").asString())).isEqualTo(expiresAt);

        // Since S8-04 the issuance route mints the URL whose TTL
        // honors exactly this entry TTL shape: bounded at or under the
        // cache TTL (the pinned S8-04 bound; the download suite proves
        // the URL's own expiry and the next-issuance immediacy).
    }

    @Test
    void bulkRevocationDeniesEveryFormerlyGrantedVersionOnTheNextDecision() throws Exception {
        IsolationScenarioHarness.IsolatedTenant side = world().sideA();
        UUID v2 = createVersion(side.platformAdminToken(), side.applicationId(), "2.0.0");
        readyArtifact(side.platformAdminToken(), v2, "2.0.0");
        grantExplicitVersion(
                side.tenantAdminToken(), side.tenantId(), side.testerId(), side.applicationId(), side.versionId());
        grantExplicitVersion(side.tenantAdminToken(), side.tenantId(), side.testerId(), side.applicationId(), v2);
        get(side.testerToken(), downloadUri(side.tenantId(), side.versionId()), 200);
        get(side.testerToken(), downloadUri(side.tenantId(), v2), 200);

        Recording bulk = postRaw(
                side.tenantAdminToken(),
                grantsPath(side.tenantId()) + "/bulk-revoke",
                java.util.Map.of("testerId", side.testerId().toString(), "reason", "suite-bulk"));
        assertThat(bulk.status()).as("bulk revoke: %s", bulk.body()).isEqualTo(200);

        assertThat(codeOf(getRaw(side.testerToken(), downloadUri(side.tenantId(), side.versionId()))))
                .isEqualTo("VERSION_NOT_FOUND");
        assertThat(codeOf(getRaw(side.testerToken(), downloadUri(side.tenantId(), v2))))
                .isEqualTo("VERSION_NOT_FOUND");
        assertThat(get(side.testerToken(), eligibilityUri(side.tenantId(), side.applicationId()), 200)
                        .get("entries")
                        .size())
                .isZero();
    }
}
