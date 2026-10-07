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
 *   <li><strong>URLs are bounded by TTL</strong>: M1 issues no URLs
 *       (S8-04 signs the issuance inputs); the ALLOW carries a storage
 *       key REFERENCE and the entry's TTL — the bound the signer will
 *       honor. The signed-URL half is the documented S8-04 bound, per
 *       docs/authorization.md's immediacy model.</li>
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
        Instant expiresAt = Instant.now().plusSeconds(3);
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

        // M1 issues no URLs: the ALLOW's issuance input was a storage
        // key reference plus the TTL above — the bound S8-04's signer
        // will honor (the signed-URL TTL half is S8-04's story).
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
