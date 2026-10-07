package com.shipperdog.api.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * INV-005's no-bypass proof (S7-05, issue #34 acceptance criterion 2):
 * no path exists that serves an artifact without the decision engine's
 * authorization. The M1 download authority is the decision API
 * ({@code GET /api/v1/authorization/decisions/download}) — S8-04 will
 * turn its issuance inputs into signed URLs — so the proof pins:
 *
 * <ul>
 *   <li><strong>The decision is the only download answer</strong>: an
 *       authorized tester's ALLOW carries exactly the issuance inputs
 *       (artifact id + storage key reference — the INPUTS a signer
 *       consumes, never a usable URL), and every persona without a
 *       grant — including the platform administrator's own persona and
 *       the owning tenant's admin — receives the concealed miss.</li>
 *   <li><strong>The API never proxies storage</strong>: direct
 *       object-storage guesses against the API host — the storage-key
 *       path itself, bucket-style paths, artifact-download path shapes
 *       — all fail closed (401/404, never content). Buckets are
 *       private by default (PRD 24); the only bytes the API serves are
 *       its own JSON.</li>
 *   <li><strong>The console-grade artifact read stays guarded</strong>:
 *       the artifact metadata route requires the version-addressed
 *       permission, and a foreign artifact id renders the artifact
 *       module's concealed miss.</li>
 *   <li><strong>The artifact storage key is not a URL</strong>: the
 *       storage key reference in an ALLOW is an opaque reference, and
 *       requesting it as a path on the API answers 401/404 — existence
 *       of the object never becomes reachability through the API.</li>
 * </ul>
 */
class ExplicitAuthorizationBypassTest extends IsolationScenarioHarness {

    @Test
    void theDecisionEngineIsTheOnlyDownloadAuthorityAndStorageIsUnreachableThroughTheApi() throws Exception {
        World world = world();
        IsolationScenarioHarness.IsolatedTenant side = world.sideA();
        UUID grantId = grantExplicitVersion(
                side.tenantAdminToken(), side.tenantId(), side.testerId(), side.applicationId(), side.versionId());

        // The authorized tester: ALLOW carries the issuance inputs only
        // (the view is the decision itself — artifact + storage key).
        var allowed = get(side.testerToken(), downloadUri(side.tenantId(), side.versionId()), 200);
        UUID artifactId = UUID.fromString(allowed.get("artifactId").asText());
        assertThat(artifactId).isEqualTo(side.artifactId());
        String storageKey = allowed.get("storageKey").asText();
        assertThat(storageKey)
                .as("the ALLOW carries the storage key reference for S8-04 to sign")
                .isNotBlank();
        assertThat(storageKey)
                .as("the storage key is an opaque reference, never a resolvable URL")
                .doesNotStartWith("http://")
                .doesNotStartWith("https://");

        // Personas without ENGINE authority receive the concealed miss:
        // the decision API is self-scoped engine territory — a tenant
        // or organization role means nothing here (no entitlement
        // maps to them without a grant). The platform persona,
        // however, passes the engine's own wildcard (the layers never
        // deny platform administrators), so their decision is ALLOW
        // like any authorized subject's — the wildcard IS the
        // authorization, exactly like the inspection API.
        for (String token : List.of(
                side.tenantAdminToken(), side.orgAdminToken(), world.sideB().testerToken())) {
            assertThat(codeOf(getRaw(token, downloadUri(side.tenantId(), side.versionId()))))
                    .as("no grant and no engine authority — regardless of console role")
                    .isEqualTo("VERSION_NOT_FOUND");
        }
        get(side.platformAdminToken(), downloadUri(side.tenantId(), side.versionId()), 200);

        // Revoking the grant removes the only authority (INV-007's
        // immediacy half is pinned in the timing test; here the
        // no-bypass closure: after revocation NO persona downloads).
        revokeGrant(side.tenantAdminToken(), side.tenantId(), grantId, "suite-bypass-proof");
        assertThat(codeOf(getRaw(side.testerToken(), downloadUri(side.tenantId(), side.versionId()))))
                .isEqualTo("VERSION_NOT_FOUND");

        // The console-grade artifact read stays guarded: the platform
        // persona (the upload pipeline's reader) succeeds, every
        // non-platform persona without the console artifact authority
        // receives the concealed version-grain miss, and the parity
        // holds for the foreign read.
        get(side.platformAdminToken(), artifactUri(side.artifactId(), side.versionId()), 200);
        Recording ownDenied = getRaw(side.tenantAdminToken(), artifactUri(side.artifactId(), side.versionId()));
        assertThat(ownDenied.status()).isEqualTo(404);
        Recording foreignDenied =
                getRaw(world.sideB().orgAdminToken(), artifactUri(side.artifactId(), side.versionId()));
        Recording genuineMiss = getRaw(side.tenantAdminToken(), artifactUri(UUID.randomUUID(), side.versionId()));
        assertThat(foreignDenied.status()).isEqualTo(404);
        assertThat(withoutTraceId(foreignDenied.body().toString()))
                .isEqualTo(withoutTraceId(genuineMiss.body().toString()));
        assertThat(withoutTraceId(ownDenied.body().toString()))
                .isEqualTo(withoutTraceId(genuineMiss.body().toString()));

        // Direct object-storage guesses against the API host: the key
        // itself, bucket-style paths, download-shaped paths, and the
        // artifact id as a content suffix — all fail closed. The API
        // serves its own JSON only; object bytes live behind S8-04's
        // signed URLs (buckets private by default, PRD 24).
        List<String> storageGuesses = List.of(
                "/" + storageKey,
                "/storage/" + storageKey,
                "/api/v1/storage/" + storageKey,
                "/download/" + storageKey,
                "/api/v1/artifacts/" + side.artifactId() + "/download",
                "/api/v1/artifacts/" + side.artifactId() + "/content",
                "/api/v1/versions/" + side.versionId() + "/download",
                "/api/v1/download?artifactId=" + side.artifactId(),
                "/gs/shipperdog-artifacts/" + storageKey);
        for (String guess : storageGuesses) {
            for (String token : java.util.Arrays.asList(side.testerToken(), side.tenantAdminToken(), null)) {
                Recording answer = exchange(guess, token, HttpMethod.GET, null);
                assertThat(answer.status())
                        .as("storage guess %s (authenticated=%s) must fail closed", guess, token != null)
                        .isIn(400, 401, 404);
            }
        }

        // And the storage key does not exist as a reachable route even
        // in encoded forms (the key carries slashes; the API's router
        // must not decode it into a handler).
        String encodedKey = storageKey.replace("/", "%2F");
        assertThat(exchange("/" + encodedKey, side.testerToken(), HttpMethod.GET, null)
                        .status())
                .isIn(400, 401, 404);
    }

    @Test
    void anArtifactWithoutAGrantNeverAuthorizesItsOwnVersion() throws Exception {
        // The ARTIFACT layer addresses single artifacts; the harness's
        // world grants nothing here: the linked tester of the OWNING
        // tenant itself cannot download their own tenant's artifact
        // without a grant — ownership is not authorization (PRD P3).
        IsolationScenarioHarness.IsolatedTenant side = world().sideA();
        assertThat(codeOf(getRaw(side.testerToken(), downloadUri(side.tenantId(), side.versionId()))))
                .isEqualTo("VERSION_NOT_FOUND");
        assertThat(codeOf(getRaw(side.tenantAdminToken(), downloadUri(side.tenantId(), side.versionId()))))
                .isEqualTo("VERSION_NOT_FOUND");

        // The platform persona passes the ENGINE's own wildcard (the
        // layers never deny platform administrators) — the engine
        // wildcard IS an authorization, distinct from RBAC role
        // authority, which never reaches this surface.
        get(side.platformAdminToken(), downloadUri(side.tenantId(), side.versionId()), 200);
    }
}
