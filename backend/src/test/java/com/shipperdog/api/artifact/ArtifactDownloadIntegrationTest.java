package com.shipperdog.api.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.config.MetricNames;
import com.shipperdog.api.identity.AuditCapture;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end acceptance flow for secure tester downloads (issue #38,
 * S8-04, FR-014, PRD 24/26/42, INV-005): the engine's DOWNLOAD
 * decision as the issuance route's entire authority (the #37
 * follow-up's RBAC relaxation), the short-lived signed URL as the
 * binary's only egress (never the storage key), the URL's expiry
 * (verified by a second fetch after the TTL), concealment parity for
 * every denial, the direct channel's AAB refusal, the audit trail and
 * per-artifact counter, and the PRD 42 URL-generation meter. The suite
 * runs the REAL store against the compose GCS emulator image, so the
 * emulator-mode mechanism is exactly what local and CI run: the
 * backend-minted HMAC proxy URL of ADR-0003's download-tier amendment.
 * The URL TTL is dialed to 2 seconds for the expiry leg (well inside
 * both startup bounds — the cache TTL stays at its 15-minute default).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef",
            "shipperdog.artifact.download.url-ttl=PT2S",
            "shipperdog.authorization-cache.enabled=false"
        })
@Testcontainers
@Import(AuditCapture.class)
class ArtifactDownloadIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";
    private static final String PASSWORD = "a-perfectly-valid-password";

    /** The compose GCS emulator image, in a container (ADR-0003). */
    @Container
    static final GenericContainer<?> GCS = new GenericContainer<>(
                    DockerImageName.parse("fsouza/fake-gcs-server:1.56.1"))
            .withCommand("-scheme", "http", "-host", "0.0.0.0", "-port", "4443")
            .withExposedPorts(4443);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @DynamicPropertySource
    static void artifactStorage(DynamicPropertyRegistry registry) {
        registry.add(
                "shipperdog.artifact.storage.host", () -> "http://" + GCS.getHost() + ":" + GCS.getMappedPort(4443));
    }

    @LocalServerPort
    int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AuditCapture audit;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private ObjectMapper mapper;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        audit.reset();
    }

    // -----------------------------------------------------------------------
    // AC1: an allowed tester gets a signed URL; fetching it streams the
    // artifact; the issuance is audited, counted, and timed.
    // -----------------------------------------------------------------------

    @Test
    void anAllowedAndroidDownloadIssuesAUrlThatStreamsTheFixtureBytes() throws Exception {
        World world = world("s804-allow");
        byte[] apk = AndroidFixtures.signedApk(AndroidFixtures.VERSION_NAME, AndroidFixtures.VERSION_CODE);
        UUID artifactId = readyArtifact(world, world.versionId, "banking.apk", "ANDROID", apk);
        TesterPerson john = testerOf(world, "s804-allow@example.com", "Allow Downloader");
        grantExplicitVersion(world, john);

        JsonNode issued =
                get(john.token(), "/api/v1/artifacts/" + artifactId + "/download?version_id=" + world.versionId, 200);

        // The issuance answer: the routed DIRECT channel and Android
        // behavior (the consent-gated URL open), the URL, its expiry,
        // and the download facts — and NEVER the storage key.
        assertThat(issued.get("artifactId").asText()).isEqualTo(artifactId.toString());
        assertThat(issued.get("versionId").asText()).isEqualTo(world.versionId.toString());
        assertThat(issued.get("platform").asString()).isEqualTo("ANDROID");
        assertThat(issued.get("channel").asString()).isEqualTo("DIRECT");
        assertThat(issued.get("behavior").asString()).isEqualTo("OPEN_SIGNED_URL");
        assertThat(issued.get("fileName").asString()).isEqualTo("banking.apk");
        assertThat(issued.get("sizeBytes").asLong()).isEqualTo(apk.length);
        assertThat(issued.has("storageKey"))
                .as("the storage key is never client-visible (FR-014 note, PRD 24)")
                .isFalse();
        String url = issued.get("url").asString();
        assertThat(url)
                .as("the emulator-mode mechanism is the backend proxy URL")
                .startsWith("/api/v1/downloads/");
        long expiresInSeconds = java.time.Duration.between(
                        java.time.Instant.now(),
                        java.time.Instant.parse(issued.get("expiresAt").asString()))
                .toSeconds();
        assertThat(expiresInSeconds)
                .as("the URL expires within the configured TTL")
                .isBetween(0L, 3L);

        // Fetching the URL — with NO authentication: the URL is the
        // credential — streams exactly the stored bytes as an
        // attachment.
        Fetched fetched = fetch(url);
        assertThat(fetched.status()).isEqualTo(200);
        assertThat(fetched.body()).isEqualTo(apk);
        assertThat(fetched.headers().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment")
                .contains("banking.apk");
        assertThat(fetched.headers().getFirst(HttpHeaders.CONTENT_TYPE))
                .isEqualTo(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        assertThat(fetched.headers().getFirst(HttpHeaders.CONTENT_LENGTH)).isEqualTo(String.valueOf(apk.length));

        // The counter (V23) and the audit trail (AC3).
        assertThat(downloadCountOf(artifactId)).isEqualTo(1);
        assertThat(audit.eventsOfType(AuditEventType.DOWNLOAD_ISSUED).stream()
                        .anyMatch(event -> artifactId.equals(event.artifactId())
                                && event.success()
                                && "ANDROID".equals(event.detail().get("platform"))
                                && "DIRECT".equals(event.detail().get("channel"))
                                && "OPEN_SIGNED_URL".equals(event.detail().get("behavior"))))
                .as("every issuance is audited with who/what/artifact/result")
                .isTrue();

        // The PRD 42 URL-generation meter (the issued leg) and the
        // reserved download counter, recorded per platform.
        assertThat(meters.get(MetricNames.DOWNLOAD_URL_GENERATION_DURATION)
                        .timer()
                        .getId()
                        .getTags())
                .anySatisfy(tag -> assertThat(tag.getValue()).isEqualTo("issued"));
        assertThat(meters.get(MetricNames.DOWNLOAD_COUNT).counter().getId().getTags())
                .anySatisfy(tag -> assertThat(tag.getValue()).isEqualTo("ANDROID"));

        // A second issuance counts again: one increment per issuance.
        get(john.token(), "/api/v1/artifacts/" + artifactId + "/download?version_id=" + world.versionId, 200);
        assertThat(downloadCountOf(artifactId)).isEqualTo(2);
    }

    // -----------------------------------------------------------------------
    // AC1's expiry half: the URL dies at its TTL; every invalid fetch
    // leg answers the same opaque 404.
    // -----------------------------------------------------------------------

    @Test
    void theIssuedUrlExpiresAfterItsTtlAndInvalidFetchesAnswerTheUniform404() throws Exception {
        World world = world("s804-expire");
        byte[] apk = AndroidFixtures.signedApk(AndroidFixtures.VERSION_NAME, AndroidFixtures.VERSION_CODE);
        UUID artifactId = readyArtifact(world, world.versionId, "banking.apk", "ANDROID", apk);
        TesterPerson john = testerOf(world, "s804-expire@example.com", "Expiry Downloader");
        grantExplicitVersion(world, john);

        JsonNode issued =
                get(john.token(), "/api/v1/artifacts/" + artifactId + "/download?version_id=" + world.versionId, 200);
        String url = issued.get("url").asString();
        assertThat(fetch(url).status()).isEqualTo(200);

        // The second fetch, after the 2-second TTL: the uniform miss.
        Thread.sleep(2_500);
        Fetched expired = fetch(url);
        assertThat(expired.status()).isEqualTo(404);
        assertThat(problemCodeOf(expired)).isEqualTo("DOWNLOAD_NOT_FOUND");

        // Tampering with the token, and a never-minted token, answer
        // the SAME uniform 404 — no leg is distinguishable.
        Fetched tampered = fetch("/api/v1/downloads/" + tamperToken(url.substring(url.lastIndexOf('/') + 1)));
        assertThat(tampered.status()).isEqualTo(404);
        assertThat(problemCodeOf(tampered)).isEqualTo("DOWNLOAD_NOT_FOUND");
        Fetched unknown = fetch("/api/v1/downloads/" + UUID.randomUUID());
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(problemCodeOf(unknown)).isEqualTo("DOWNLOAD_NOT_FOUND");
        // Byte-parity of the three failure legs, modulo the per-request
        // trace id (the repo's concealment convention, PRD 12): the
        // fetcher cannot tell expired from tampered from never-minted.
        assertThat(withoutTraceId(expired.body())).isEqualTo(withoutTraceId(tampered.body()));
        assertThat(withoutTraceId(expired.body())).isEqualTo(withoutTraceId(unknown.body()));
    }

    // -----------------------------------------------------------------------
    // AC2 + INV-005: denials conceal, byte-identically, and never
    // issue a URL, a count, or an audit event.
    // -----------------------------------------------------------------------

    @Test
    void denialsConcealAtTheTwoGrainsAndNeverIssueUrlsOrCounts() throws Exception {
        World world = world("s804-conceal");
        byte[] apk = AndroidFixtures.signedApk(AndroidFixtures.VERSION_NAME, AndroidFixtures.VERSION_CODE);
        UUID artifactId = readyArtifact(world, world.versionId, "banking.apk", "ANDROID", apk);
        TesterPerson john = testerOf(world, "s804-conceal@example.com", "Conceal Downloader");
        grantExplicitVersion(world, john);

        // No grant persona: the version-grain miss, byte-identical to
        // the genuine unknown-version miss (modulo the trace id).
        TesterPerson grantless = testerOf(world, "s804-grantless@example.com", "Grantless Viewer");
        String uri = "/api/v1/artifacts/" + artifactId + "/download?version_id=" + world.versionId;
        Map<String, Object> denied = problemOf(getRaw(grantless.token(), uri));
        Map<String, Object> genuine = problemOf(getRaw(
                grantless.token(), "/api/v1/artifacts/" + artifactId + "/download?version_id=" + UUID.randomUUID()));
        assertThat(denied.get("status")).isEqualTo(404);
        assertThat(denied.get("code")).isEqualTo("VERSION_NOT_FOUND");
        assertThat(denied).containsAllEntriesOf(genuine);

        // A granted tester addressing a foreign artifact id under the
        // granted version: the artifact-grain miss, byte-identical to
        // the genuine artifact miss.
        Map<String, Object> foreign = problemOf(getRaw(
                john.token(), "/api/v1/artifacts/" + UUID.randomUUID() + "/download?version_id=" + world.versionId));
        assertThat(foreign.get("code")).isEqualTo("ARTIFACT_NOT_FOUND");

        // A REVOKED version blocks new downloads (INV-007) as the same
        // version miss.
        post(world.platformAdmin(), revokeUri(world), Map.of("reason", "concealment fixture"), 200);
        assertThat(problemOf(getRaw(john.token(), uri)).get("code")).isEqualTo("VERSION_NOT_FOUND");

        // INV-005's no-issuance half: nothing was counted, nothing
        // audited, no URL exists for any denial above.
        assertThat(downloadCountOf(artifactId)).isZero();
        assertThat(audit.eventsOfType(AuditEventType.DOWNLOAD_ISSUED)).isEmpty();
    }

    // -----------------------------------------------------------------------
    // INV-007 immediacy: revoking the grant blocks the NEXT issuance.
    // -----------------------------------------------------------------------

    @Test
    void revokingTheGrantBlocksTheNextIssuanceImmediately() throws Exception {
        World world = world("s804-revoke");
        byte[] apk = AndroidFixtures.signedApk(AndroidFixtures.VERSION_NAME, AndroidFixtures.VERSION_CODE);
        UUID artifactId = readyArtifact(world, world.versionId, "banking.apk", "ANDROID", apk);
        TesterPerson john = testerOf(world, "s804-revoke@example.com", "Revoke Downloader");
        UUID grantId = grantExplicitVersion(world, john);

        String uri = "/api/v1/artifacts/" + artifactId + "/download?version_id=" + world.versionId;
        get(john.token(), uri, 200);

        delete(
                world.platformAdmin(),
                "/api/v1/tenants/" + world.tenantId + "/authorization-grants/" + grantId + "?reason=s804-immediacy",
                204);
        assertThat(problemOf(getRaw(john.token(), uri)).get("code")).isEqualTo("VERSION_NOT_FOUND");
    }

    // -----------------------------------------------------------------------
    // The direct channel's format rule (S5-05): an AAB never serves,
    // and never becomes an installable slot or platform row.
    // -----------------------------------------------------------------------

    @Test
    void aabArtifactsNeverServeDirectlyAndNeverBecomeInstallableSlots() throws Exception {
        World world = world("s804-aab");
        // The AAB fixture carries the default version identity, so it
        // rides the world's own 7.12.0 version (the pipeline's
        // VERSION_MATCH stage demands the agreement).
        byte[] aab = AndroidFixtures.aabVersionCodeInManifest();
        UUID aabArtifactId = readyArtifact(world, world.versionId, "banking.aab", "ANDROID", aab);
        assertThat(artifactFormatOf(aabArtifactId)).isEqualTo("AAB");
        TesterPerson john = testerOf(world, "s804-aab@example.com", "AAB Downloader");
        grantExplicitVersion(world, john);

        // Issuance for the AAB: the artifact-grain miss, byte-identical
        // to a version without any distributable Android artifact.
        Map<String, Object> refused = problemOf(
                getRaw(john.token(), "/api/v1/artifacts/" + aabArtifactId + "/download?version_id=" + world.versionId));
        assertThat(refused.get("status")).isEqualTo(404);
        assertThat(refused.get("code")).isEqualTo("ARTIFACT_NOT_FOUND");

        // The eligibility listing keeps the version but renders NO
        // platform rows for it (AC-adjacent: APK rows only on the
        // direct channel — the slot the S8-03 page renders).
        JsonNode eligibility = get(
                john.token(),
                "/api/v1/authorization/decisions/eligibility?tenantId=" + world.tenantId + "&applicationId="
                        + world.applicationId,
                200);
        JsonNode entry = eligibility.get("entries").get(0);
        assertThat(entry.get("versionId").asText()).isEqualTo(world.versionId.toString());
        assertThat(entry.get("platforms").size())
                .as("an AAB is never an installable slot on the direct channel")
                .isZero();
        assertThat(downloadCountOf(aabArtifactId)).isZero();
    }

    // -----------------------------------------------------------------------
    // The iOS direct row: the ad-hoc/enterprise note behavior.
    // -----------------------------------------------------------------------

    @Test
    void iosDirectIssuanceCarriesTheAdhocNoteBehavior() throws Exception {
        World world = worldIos("s804-ios");
        byte[] ipa = IpaFixtures.releaseIpa();
        UUID artifactId = readyArtifact(world, world.versionId, "bank.ipa", "IOS", ipa);
        TesterPerson john = testerOf(world, "s804-ios@example.com", "iOS Downloader");
        grantExplicitVersion(world, john);

        JsonNode issued =
                get(john.token(), "/api/v1/artifacts/" + artifactId + "/download?version_id=" + world.versionId, 200);
        assertThat(issued.get("platform").asString()).isEqualTo("IOS");
        assertThat(issued.get("channel").asString()).isEqualTo("DIRECT");
        assertThat(issued.get("behavior").asString())
                .as("iOS direct is ad-hoc/enterprise signed only — the note rides the behavior")
                .isEqualTo("OPEN_SIGNED_URL_ADHOC");

        Fetched fetched = fetch(issued.get("url").asString());
        assertThat(fetched.status()).isEqualTo(200);
        assertThat(fetched.body()).isEqualTo(ipa);
    }

    // -----------------------------------------------------------------------
    // The decision API's ALLOW no longer carries the storage key: the
    // issuance route is the only consumer of that reference.
    // -----------------------------------------------------------------------

    @Test
    void theDecisionApiAllowNoLongerExposesTheStorageKey() throws Exception {
        World world = world("s804-key");
        byte[] apk = AndroidFixtures.signedApk(AndroidFixtures.VERSION_NAME, AndroidFixtures.VERSION_CODE);
        readyArtifact(world, world.versionId, "banking.apk", "ANDROID", apk);
        TesterPerson john = testerOf(world, "s804-key@example.com", "Key Viewer");
        grantExplicitVersion(world, john);

        JsonNode decision = get(
                john.token(),
                "/api/v1/authorization/decisions/download?tenantId=" + world.tenantId + "&versionId=" + world.versionId
                        + "&platform=ANDROID",
                200);
        assertThat(decision.has("storageKey"))
                .as("S8-04 closed the S7-03 stopgap: signed URLs are the only egress")
                .isFalse();
        assertThat(decision.get("artifactId").asText()).isNotBlank();
    }

    // -----------------------------------------------------------------------
    // The mid-flow revocation bound (the dispatch's TTL pin): a revoked
    // tester's ALREADY-MINTED URL is a stateless capability — the proxy
    // does no database read — so it serves until its TTL and no longer,
    // while a NEW issuance is refused immediately. The TTL bound is the
    // security contract: revocation semantics live at issuance, the URL
    // lifetime is the backstop (S7-04's rule, enforced at startup).
    // -----------------------------------------------------------------------

    @Test
    void aRevokedTestersAlreadyMintedUrlServesUntilItsTtlAndNoLonger() throws Exception {
        World world = world("s804-midrevoke");
        byte[] apk = AndroidFixtures.signedApk(AndroidFixtures.VERSION_NAME, AndroidFixtures.VERSION_CODE);
        UUID artifactId = readyArtifact(world, world.versionId, "banking.apk", "ANDROID", apk);
        TesterPerson john = testerOf(world, "s804-midrevoke@example.com", "Midflow Downloader");
        UUID grantId = grantExplicitVersion(world, john);

        String uri = "/api/v1/artifacts/" + artifactId + "/download?version_id=" + world.versionId;
        String url = get(john.token(), uri, 200).get("url").asString();

        delete(
                world.platformAdmin(),
                "/api/v1/tenants/" + world.tenantId + "/authorization-grants/" + grantId + "?reason=s804-midflow",
                204);

        // The next issuance is refused, concealed, immediately.
        assertThat(problemOf(getRaw(john.token(), uri)).get("code")).isEqualTo("VERSION_NOT_FOUND");

        // The already-minted URL — the stateless capability — still
        // serves the exact bytes: the bound is the TTL, nothing sooner.
        Fetched stillValid = fetch(url);
        assertThat(stillValid.status()).isEqualTo(200);
        assertThat(stillValid.body()).isEqualTo(apk);

        // And it dies at its TTL (the 2-second suite TTL).
        Thread.sleep(2_500);
        assertThat(fetch(url).status()).isEqualTo(404);
        assertThat(downloadCountOf(artifactId)).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // Suspension at both switches: neither a suspended membership nor a
    // suspended person ever gets a URL, a count, or an audit event.
    // -----------------------------------------------------------------------

    @Test
    void suspendedTestersAreDeniedNewIssuanceAtBothSwitches() throws Exception {
        World world = world("s804-suspend");
        byte[] apk = AndroidFixtures.signedApk(AndroidFixtures.VERSION_NAME, AndroidFixtures.VERSION_CODE);
        UUID artifactId = readyArtifact(world, world.versionId, "banking.apk", "ANDROID", apk);
        TesterPerson john = testerOf(world, "s804-suspend@example.com", "Suspend Downloader");
        grantExplicitVersion(world, john);

        String uri = "/api/v1/artifacts/" + artifactId + "/download?version_id=" + world.versionId;
        get(john.token(), uri, 200);

        // Membership switch off: the tenant dimension denies, concealed.
        put(
                world.platformAdmin(),
                "/api/v1/tenants/" + world.tenantId + "/testers/" + john.testerId() + "/status",
                Map.of("status", "SUSPENDED"),
                200);
        assertThat(problemOf(getRaw(john.token(), uri)).get("code")).isEqualTo("VERSION_NOT_FOUND");

        // Membership back on, person switch off: same concealed refusal.
        put(
                world.platformAdmin(),
                "/api/v1/tenants/" + world.tenantId + "/testers/" + john.testerId() + "/status",
                Map.of("status", "ACTIVE"),
                200);
        put(
                world.platformAdmin(),
                "/api/v1/testers/" + john.testerId() + "/status",
                Map.of("status", "SUSPENDED"),
                200);
        assertThat(problemOf(getRaw(john.token(), uri)).get("code")).isEqualTo("VERSION_NOT_FOUND");

        // Only the control issuance ever happened.
        assertThat(downloadCountOf(artifactId)).isEqualTo(1);
        assertThat(audit.eventsOfType(AuditEventType.DOWNLOAD_ISSUED)).hasSize(1);
    }

    // -----------------------------------------------------------------------
    // Cross-artifact binding and cross-tenant use: two live tokens in
    // two tenants never cross streams, and a foreign tenant's tester
    // cannot mint a URL for the other side's artifact.
    // -----------------------------------------------------------------------

    @Test
    void liveTokensNeverCrossStreamsAndCrossTenantIssuanceMisses() throws Exception {
        World worldA = world("s804-xstream-a");
        byte[] apkA = AndroidFixtures.signedApk(AndroidFixtures.VERSION_NAME, AndroidFixtures.VERSION_CODE);
        UUID artifactA = readyArtifact(worldA, worldA.versionId, "banking-a.apk", "ANDROID", apkA);
        TesterPerson johnA = testerOf(worldA, "s804-xstream-a@example.com", "Tenant A Downloader");
        grantExplicitVersion(worldA, johnA);

        World worldB = world("s804-xstream-b");
        byte[] apkB = AndroidFixtures.signedPaddedApk(8_192);
        UUID artifactB = readyArtifact(worldB, worldB.versionId, "banking-b.apk", "ANDROID", apkB);
        TesterPerson johnB = testerOf(worldB, "s804-xstream-b@example.com", "Tenant B Downloader");
        grantExplicitVersion(worldB, johnB);

        // Cross-tenant issuance: tenant A's granted tester addressing
        // tenant B's artifact under B's VERSION conceals at the version
        // grain — the caller holds no entitlement for that version, so
        // the miss is byte-identical to an unknown version id (the
        // two-grain rule follows the addressing, never the failing
        // layer; the artifact grain belongs to a foreign artifact under
        // a GRANTED version, pinned above). No URL either way.
        Map<String, Object> foreign = problemOf(
                getRaw(johnA.token(), "/api/v1/artifacts/" + artifactB + "/download?version_id=" + worldB.versionId));
        assertThat(foreign.get("status")).isEqualTo(404);
        assertThat(foreign.get("code")).isEqualTo("VERSION_NOT_FOUND");

        // Both own issuances live: distinct tokens, each streaming its
        // own artifact's bytes — regardless of fetch order.
        String urlA = get(
                        johnA.token(),
                        "/api/v1/artifacts/" + artifactA + "/download?version_id=" + worldA.versionId,
                        200)
                .get("url")
                .asString();
        String urlB = get(
                        johnB.token(),
                        "/api/v1/artifacts/" + artifactB + "/download?version_id=" + worldB.versionId,
                        200)
                .get("url")
                .asString();
        assertThat(urlA).isNotEqualTo(urlB);
        assertThat(fetch(urlB).body()).isEqualTo(apkB);
        assertThat(fetch(urlA).body()).isEqualTo(apkA);
        assertThat(fetch(urlA).body()).isNotEqualTo(apkB);
        assertThat(downloadCountOf(artifactA)).isEqualTo(1);
        assertThat(downloadCountOf(artifactB)).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // Size sanity: a multi-megabyte artifact streams through the proxy
    // with the grant's exact Content-Length and byte-identical bytes.
    // -----------------------------------------------------------------------

    @Test
    void aMultiMegabyteArtifactStreamsWithExactContentLengthAndBytes() throws Exception {
        World world = world("s804-large");
        byte[] apk = AndroidFixtures.signedPaddedApk(4 * 1024 * 1024);
        UUID artifactId = readyArtifact(world, world.versionId, "large.apk", "ANDROID", apk);
        TesterPerson john = testerOf(world, "s804-large@example.com", "Large Downloader");
        grantExplicitVersion(world, john);

        JsonNode issued =
                get(john.token(), "/api/v1/artifacts/" + artifactId + "/download?version_id=" + world.versionId, 200);
        Fetched fetched = fetch(issued.get("url").asString());
        assertThat(fetched.status()).isEqualTo(200);
        assertThat(fetched.headers().getFirst(HttpHeaders.CONTENT_LENGTH)).isEqualTo(String.valueOf(apk.length));
        assertThat(fetched.body())
                .as("the 4 MiB object streams through the proxy byte-identically")
                .isEqualTo(apk);
    }

    // -----------------------------------------------------------------------
    // The anonymous surface's shape discipline: only GET on the exact
    // single-segment path is open. A POST is refused before routing
    // (anonymous: the chain's 401; authenticated: no mapping, 405), and
    // a nested path never reaches the proxy matcher.
    // -----------------------------------------------------------------------

    @Test
    void theProxyRefusesNonGetTrafficOnItsOpenPath() throws Exception {
        World world = world("s804-shape");
        byte[] apk = AndroidFixtures.signedApk(AndroidFixtures.VERSION_NAME, AndroidFixtures.VERSION_CODE);
        UUID artifactId = readyArtifact(world, world.versionId, "banking.apk", "ANDROID", apk);
        TesterPerson john = testerOf(world, "s804-shape@example.com", "Shape Downloader");
        grantExplicitVersion(world, john);
        String url = get(
                        john.token(),
                        "/api/v1/artifacts/" + artifactId + "/download?version_id=" + world.versionId,
                        200)
                .get("url")
                .asString();

        // Anonymous POST on the open path shape: refused by the chain.
        int anonymousPost = client.post()
                .uri("/api/v1/downloads/" + UUID.randomUUID())
                .exchange((request, response) -> response.getStatusCode().value());
        assertThat(anonymousPost).isEqualTo(401);

        // Authenticated POST (even with a live tester token): no POST
        // mapping exists — the method is refused, never 2xx.
        int authenticatedPost = client.post()
                .uri(url)
                .headers(headers -> headers.setBearerAuth(john.token()))
                .exchange((request, response) -> response.getStatusCode().value());
        assertThat(authenticatedPost).isEqualTo(405);

        // A nested path is not the proxy's shape: anonymous reaches the
        // default authenticated rule, not the proxy.
        int nested = client.get()
                .uri("/api/v1/downloads/" + UUID.randomUUID() + "/nested")
                .exchange((request, response) -> response.getStatusCode().value());
        assertThat(nested).isEqualTo(401);
    }

    // -----------------------------------------------------------------------
    // World building and plumbing
    // -----------------------------------------------------------------------

    private record World(String platformAdmin, UUID orgId, UUID tenantId, UUID applicationId, UUID versionId) {}

    private record TesterPerson(UUID testerId, String token) {}

    private World world(String suffix) {
        return world(suffix, "ANDROID", AndroidFixtures.PACKAGE, "packageName");
    }

    private World worldIos(String suffix) {
        return world(suffix, "IOS", IpaFixtures.BUNDLE_ID, "bundleId");
    }

    private World world(String suffix, String platform, String identifier, String identifierField) {
        String platformAdmin = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        UUID orgId = UUID.fromString(
                post(platformAdmin, "/api/v1/organizations", Map.of("name", "S804 " + suffix + " Corp"), 201)
                        .get("id")
                        .asText());
        UUID tenantId = UUID.fromString(post(
                        platformAdmin,
                        "/api/v1/tenants",
                        Map.of(
                                "organizationId", orgId.toString(),
                                "name", "Banking " + suffix,
                                "externalId", "ext-" + suffix),
                        201)
                .get("id")
                .asText());
        Map<String, Object> application = new LinkedHashMap<>();
        application.put("tenantId", tenantId.toString());
        application.put("name", "Banking");
        application.put("platforms", List.of(platform));
        application.put(identifierField, identifier);
        UUID applicationId = UUID.fromString(post(platformAdmin, "/api/v1/applications", application, 201)
                .get("id")
                .asText());
        UUID versionId = createVersion(platformAdmin, applicationId, AndroidFixtures.VERSION_NAME);
        return new World(platformAdmin, orgId, tenantId, applicationId, versionId);
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

    /** Uploads the artifact and polls the pipeline to READY (the fixture must settle distributable). */
    private UUID readyArtifact(World world, UUID versionId, String fileName, String platform, byte[] bytes)
            throws Exception {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("version_id", versionId.toString());
        parts.add("platform", platform);
        parts.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        Recording uploaded = exchange("/api/v1/artifacts", world.platformAdmin(), HttpMethod.POST, parts);
        assertThat(uploaded.status).as("artifact upload: %s", uploaded.body).isEqualTo(201);
        UUID artifactId = UUID.fromString(uploaded.body.get("id").asText());
        long deadline = System.currentTimeMillis() + 20_000;
        String status = "";
        while (System.currentTimeMillis() < deadline) {
            status = get(world.platformAdmin(), "/api/v1/artifacts/" + artifactId + "?version_id=" + versionId, 200)
                    .get("status")
                    .asString();
            if ("READY".equals(status)) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(status).as("the fixture must settle READY").isEqualTo("READY");
        return artifactId;
    }

    private UUID grantExplicitVersion(World world, TesterPerson tester) {
        return grantExplicitVersion(world, tester, world.versionId);
    }

    private UUID grantExplicitVersion(World world, TesterPerson tester, UUID versionId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("testerId", tester.testerId().toString());
        body.put("applicationId", world.applicationId.toString());
        body.put("accessType", "EXPLICIT_VERSION");
        body.put("versionId", versionId.toString());
        body.put("reason", "one specific version");
        return UUID.fromString(
                post(world.platformAdmin, "/api/v1/tenants/" + world.tenantId + "/authorization-grants", body, 201)
                        .get("grant")
                        .get("id")
                        .asText());
    }

    private TesterPerson testerOf(World world, String email, String fullName) {
        JsonNode invited = post(
                world.platformAdmin(),
                "/api/v1/testers",
                Map.of("organizationId", world.orgId.toString(), "email", email, "fullName", fullName),
                201);
        client.post()
                .uri("/api/v1/auth/register")
                .body(Map.of(
                        "invitationToken",
                        invited.get("invitation").get("invitationToken").asString(),
                        "email",
                        email,
                        "password",
                        PASSWORD,
                        "fullName",
                        fullName))
                .retrieve()
                .body(String.class);
        post(
                world.platformAdmin(),
                "/api/v1/tenants/" + world.tenantId + "/testers",
                Map.of("testerId", invited.get("id").asText()),
                201);
        return new TesterPerson(UUID.fromString(invited.get("id").asText()), accessToken(login(email, PASSWORD)));
    }

    /** Fetches a download URL as a browser does: unauthenticated, bytes not JSON. */
    private Fetched fetch(String path) {
        return client.get()
                .uri(URI.create(path))
                .exchange((request, response) -> new Fetched(
                        response.getStatusCode().value(),
                        response.getBody().readAllBytes(),
                        response.getHeaders(),
                        response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)));
    }

    private static String problemCodeOf(Fetched fetched) {
        try {
            return new ObjectMapper().readTree(fetched.body()).get("code").asString();
        } catch (Exception e) {
            return "<no problem body: " + new String(fetched.body()) + ">";
        }
    }

    /** The problem body minus the per-request trace id (the parity convention). */
    private static String withoutTraceId(byte[] problemBody) {
        try {
            tools.jackson.databind.node.ObjectNode node =
                    (tools.jackson.databind.node.ObjectNode) new ObjectMapper().readTree(problemBody);
            node.remove("trace_id");
            node.remove("instance");
            return node.toString();
        } catch (Exception e) {
            return "<unparseable: " + new String(problemBody) + ">";
        }
    }

    /** Flips one base64url character of the payload — a MAC-verifiable tamper. */
    private static String tamperToken(String token) {
        return ("A".equals(token.substring(0, 1)) ? "B" : "A") + token.substring(1);
    }

    private long downloadCountOf(UUID artifactId) {
        return jdbc.queryForObject("select download_count from artifacts where id = ?", Long.class, artifactId);
    }

    private String artifactFormatOf(UUID artifactId) {
        return jdbc.queryForObject("select format from artifacts where id = ?", String.class, artifactId);
    }

    private String revokeUri(World world) {
        return "/api/v1/applications/" + world.applicationId + "/versions/" + world.versionId + "/revoke-distribution";
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

    private JsonNode post(String token, String uri, Map<String, Object> body, int expectedStatus) {
        Recording response = exchange(uri, token, HttpMethod.POST, body);
        assertThat(response.status).as("POST %s: %s", uri, response.body).isEqualTo(expectedStatus);
        return response.body;
    }

    private JsonNode get(String token, String uri, int expectedStatus) {
        Recording response = getRaw(token, uri);
        assertThat(response.status).as("GET %s: %s", uri, response.body).isEqualTo(expectedStatus);
        return response.body;
    }

    private Recording getRaw(String token, String uri) {
        return exchange(uri, token, HttpMethod.GET, null);
    }

    private void delete(String token, String uri, int expectedStatus) {
        Recording response = exchange(uri, token, HttpMethod.DELETE, null);
        assertThat(response.status).as("DELETE %s: %s", uri, response.body).isEqualTo(expectedStatus);
    }

    private void put(String token, String uri, Map<String, Object> body, int expectedStatus) {
        Recording response = exchange(uri, token, HttpMethod.PUT, body);
        assertThat(response.status).as("PUT %s: %s", uri, response.body).isEqualTo(expectedStatus);
    }

    private static Map<String, Object> problemOf(Recording recording) {
        Map<String, Object> shape = new LinkedHashMap<>();
        shape.put("status", recording.status());
        shape.put("code", recording.body().get("code").asString());
        shape.put("title", recording.body().get("title").asString());
        shape.put("detail", recording.body().get("detail").asString());
        return shape;
    }

    private Recording exchange(String uri, String token, HttpMethod method, Object body) {
        RestClient.RequestBodySpec spec = client.method(method).uri(uri);
        if (token != null) {
            spec.headers(headers -> headers.setBearerAuth(token));
        }
        if (body != null) {
            spec.contentType(
                    body instanceof MultiValueMap<?, ?> ? MediaType.MULTIPART_FORM_DATA : MediaType.APPLICATION_JSON);
            spec.body(body);
        }
        return spec.exchange((request, response) -> {
            byte[] raw = response.getBody().readAllBytes();
            return new Recording(
                    response.getStatusCode().value(),
                    response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                    raw.length == 0 ? mapper.createObjectNode() : mapper.readTree(raw));
        });
    }

    private record Recording(int status, String contentType, JsonNode body) {}

    private record Fetched(int status, byte[] body, HttpHeaders headers, String contentType) {}
}
