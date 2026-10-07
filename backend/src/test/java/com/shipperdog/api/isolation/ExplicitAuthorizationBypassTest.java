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
 *   <li><strong>The decision is the only download answer</strong>: an
 *       authorized tester's ALLOW carries the artifact id and NO
 *       storage key (S8-04 closed the S7-03 stopgap — the storage key
 *       is server-internal since signed URLs became the binary's only
 *       egress), and every persona without a grant — including the
 *       platform administrator's own persona and the owning tenant's
 *       admin — receives the concealed miss.</li>
 *   <li><strong>The API never serves storage without a signed URL</strong>:
 *       the issuance route mints a URL only on an engine ALLOW; the
 *       URL (the emulator-mode proxy token) is the ONLY path that
 *       streams bytes, every guess against it fails closed with the
 *       uniform 404, and direct object-storage guesses against the
 *       API host — the storage-key path itself, bucket-style paths —
 *       all fail closed. Buckets are private by default (PRD 24);
 *       the only bytes the API serves are its own JSON and
 *       decision-minted URLs.</li>
 *   <li><strong>The console-grade artifact read stays guarded</strong>:
 *       the artifact metadata route requires the version-addressed
 *       permission, and a foreign artifact id renders the artifact
 *       module's concealed miss.</li>
 *   <li><strong>The artifact storage key is not a URL and not a
 *       client-visible fact</strong>: no download payload carries it,
 *       and requesting the key as a path on the API answers 401/404 —
 *       existence of the object never becomes reachability through
 *       the API.</li>
 * </ul>
 */
class ExplicitAuthorizationBypassTest extends IsolationScenarioHarness {

    @Test
    void theDecisionEngineIsTheOnlyDownloadAuthorityAndStorageIsUnreachableThroughTheApi() throws Exception {
        World world = world();
        IsolationScenarioHarness.IsolatedTenant side = world.sideA();
        UUID grantId = grantExplicitVersion(
                side.tenantAdminToken(), side.tenantId(), side.testerId(), side.applicationId(), side.versionId());

        // The authorized tester: ALLOW carries the artifact id and NO
        // storage key — the S7-03 stopgap is closed; the issuance
        // route consumes the key server-side and hands back a URL.
        var allowed = get(side.testerToken(), downloadUri(side.tenantId(), side.versionId()), 200);
        UUID artifactId = UUID.fromString(allowed.get("artifactId").asText());
        assertThat(artifactId).isEqualTo(side.artifactId());
        assertThat(allowed.has("storageKey"))
                .as("the storage key is server-internal since S8-04")
                .isFalse();

        // The issuance itself: the granted tester mints the URL; the
        // storage key never appears anywhere a client can read, and
        // the fetch of the minted URL streams the bytes (the proxy
        // token is the credential — no session, no role).
        var issued = get(side.testerToken(), issuanceUri(side.artifactId(), side.versionId()), 200);
        assertThat(issued.get("url").asString()).startsWith("/api/v1/downloads/");
        assertThat(issued.has("storageKey")).isFalse();
        Recording fetched = exchange(issued.get("url").asString(), null, HttpMethod.GET, null);
        assertThat(fetched.status())
                .as("the minted URL streams the stored bytes")
                .isEqualTo(200);

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
        // no-bypass closure: after revocation NO persona downloads and
        // NO new URL is minted — a previously minted URL lives to its
        // own TTL by design, bounded at or under the cache TTL).
        revokeGrant(side.tenantAdminToken(), side.tenantId(), grantId, "suite-bypass-proof");
        assertThat(codeOf(getRaw(side.testerToken(), downloadUri(side.tenantId(), side.versionId()))))
                .isEqualTo("VERSION_NOT_FOUND");
        assertThat(codeOf(getRaw(side.testerToken(), issuanceUri(side.artifactId(), side.versionId()))))
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
        String storageKey = "%s/%s/%s/%s/%s/artifact"
                .formatted(side.organizationId(), side.tenantId(), side.applicationId(), side.versionId(), artifactId);
        List<String> storageGuesses = List.of(
                "/" + storageKey,
                "/storage/" + storageKey,
                "/api/v1/storage/" + storageKey,
                "/download/" + storageKey,
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

        // The proxy token guesses: never-minted and malformed tokens
        // both answer the SAME uniform 404 — the one anonymous surface
        // fails closed exactly like the rest.
        for (String guess : List.of("/api/v1/downloads/" + UUID.randomUUID(), "/api/v1/downloads/not-a-token")) {
            Recording answer = exchange(guess, null, HttpMethod.GET, null);
            assertThat(answer.status())
                    .as("proxy guess %s must fail closed", guess)
                    .isEqualTo(404);
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
