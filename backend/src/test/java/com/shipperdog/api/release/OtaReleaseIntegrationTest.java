package com.shipperdog.api.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The S16-05 acceptance flow (issue #167, ADR-0010 workstream 3): the
 * OTA release linkage end to end — the exactly-one-SIGNED-bundle rule
 * of an {@code ota}-channel ring (zero bundles and a bundle's bytes on
 * a store ring both refused; exactly one flips the bundle to RELEASED
 * with the {@code release_id} backfill, and a rollback returns it to
 * the SIGNED pool), the D7 per-target {@code forced}/{@code
 * min_app_version} overrides persisted on the release target, the ring
 * rule's device axes ({@code bundleIds}/{@code platformTags}) honored
 * by the eligibility seam's application-scoped read, and the
 * {@code bundle-crash-free-threshold} gate evaluating from the
 * analytics SESSION standing — NO_DATA fail-closed until data exists,
 * FAILED below the threshold, PASSED above it, blocking the VALIDATING
 * to READY crossing the promotion engine shares. The store-release
 * behavior on the seeded rings stays untouched beside it all.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
class OtaReleaseIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";
    private static final String PASSWORD = "a-perfectly-valid-password";

    /** The compose GCS emulator image (ADR-0003) — the bundle upload session's target. */
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
    private ObjectMapper mapper;

    @Autowired
    private RingEligibility eligibility;

    @Autowired
    private com.shipperdog.api.tenant.TenantScopeExecutor scopeExecutor;

    private RestClient client;

    private int versionCounter;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    // -----------------------------------------------------------------------
    // AC1: exactly one SIGNED bundle — the linkage and its refusals
    // -----------------------------------------------------------------------

    @Test
    void otaReleaseRequiresExactlyOneSignedBundleAndPublishesIt() throws Exception {
        OtaWorld world = otaWorld("or-one");
        JsonNode bundle = authorSignedBundle(world, "1.4.2", "ios");
        UUID bundleId = UUID.fromString(bundle.get("id").asText());
        String ring = otaRing(world);

        // Zero bundles: a store artifact selection on the ota-channel
        // ring is the rule's own 422 (not the concealed artifact miss —
        // the version and the artifact are coherent store facts).
        UUID storeVersion = createVersion(world.ownerToken, world.app, "2.0." + versionCounter++);
        UUID storeArtifact = insertArtifact(storeVersion, "IOS", 200 + versionCounter);
        Map<String, Object> zeroBundles = releaseBody(world, storeVersion, ring, storeArtifact, "IOS");
        Recording refused = postRaw(world.managerToken, "/api/v1/releases", zeroBundles);
        assertThat(refused.status).isEqualTo(422);
        assertThat(refused.body.get("code").asText()).isEqualTo("RELEASE_OTA_BUNDLE_INVALID");
        assertThat(refused.body.get("errors").get("artifacts").asText()).contains("not an OTA bundle");

        // A bundle's bytes on a store ring: refused just as precisely.
        Map<String, Object> onStoreRing =
                releaseBody(world, UUID.fromString(bundle.get("versionId").asText()), "qa", bundleId, "IOS");
        Recording storeRefused = postRaw(world.managerToken, "/api/v1/releases", onStoreRing);
        assertThat(storeRefused.status).isEqualTo(422);
        assertThat(storeRefused.body.get("code").asText()).isEqualTo("RELEASE_OTA_BUNDLE_INVALID");

        // Exactly one: the publish lands — the bundle RELEASED with the
        // release_id backfill, atomically with the release row.
        UUID releaseId = createOtaRelease(world, bundle, ring, null, null);
        Map<String, Object> bundleRow =
                jdbc.queryForMap("SELECT status, release_id FROM ota_bundles WHERE id = ?", bundleId);
        assertThat(bundleRow.get("status")).isEqualTo("RELEASED");
        assertThat(bundleRow.get("release_id")).isEqualTo(releaseId);

        // The published bundle is not republishable: a second release of
        // it is the rule's refusal (its version row is held — V55's
        // schema floor — and the row itself is no longer SIGNED).
        Map<String, Object> again =
                releaseBody(world, UUID.fromString(bundle.get("versionId").asText()), ring, bundleId, "IOS");
        Recording second = postRaw(world.managerToken, "/api/v1/releases", again);
        assertThat(second.status).isIn(409, 422);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM ota_bundles WHERE release_id = ?", Integer.class, releaseId))
                .isEqualTo(1);

        // The store-release behavior beside it: unchanged (a seeded
        // ring, a store version and artifact — the pre-S16-05 flow).
        UUID anotherStore = createVersion(world.ownerToken, world.app, "3.0." + versionCounter++);
        Map<String, Object> store =
                releaseBody(world, anotherStore, "qa", insertArtifact(anotherStore, "IOS", 300), "IOS");
        assertThat(postRaw(world.managerToken, "/api/v1/releases", store).status)
                .isEqualTo(201);
    }

    // -----------------------------------------------------------------------
    // AC1 (rollback half) + AC3 (D7 overrides): the walk to ACTIVE, the
    // persisted overrides, and the rollback's SIGNED return
    // -----------------------------------------------------------------------

    @Test
    void rollbackReturnsTheBundleToTheSignedPoolAndOverridesPersist() throws Exception {
        OtaWorld world = otaWorld("or-roll");
        JsonNode bundle = authorSignedBundle(world, "1.4.0", "ios");
        UUID bundleId = UUID.fromString(bundle.get("id").asText());

        // D7: the target carries both overrides; the persisted row keeps
        // the tri-state (an override of FALSE stays a real fact).
        String ring = otaRing(world);
        UUID releaseId = createOtaRelease(world, bundle, ring, false, "2.1.0");
        Map<String, Object> targetRow =
                jdbc.queryForMap("SELECT forced, min_app_version FROM release_targets WHERE release_id = ?", releaseId);
        assertThat(targetRow.get("forced")).isEqualTo(false);
        assertThat(targetRow.get("min_app_version")).isEqualTo("2.1.0");

        // The detail renders the same pair (the console's copy surface).
        JsonNode detail = get(world.managerToken, "/api/v1/releases/" + releaseId + "?applicationId=" + world.app, 200);
        assertThat(detail.get("targets").get(0).get("forced").asBoolean()).isFalse();
        assertThat(detail.get("targets").get(0).get("minAppVersion").asText()).isEqualTo("2.1.0");

        // Overrides are OTA facts: a store release refuses either one.
        UUID storeVersion = createVersion(world.ownerToken, world.app, "2.2." + versionCounter++);
        Map<String, Object> overridden =
                releaseBody(world, storeVersion, "qa", insertArtifact(storeVersion, "IOS", 400), "IOS");
        ((List<Map<String, Object>>) overridden.get("targets")).get(0).put("forced", true);
        Recording storeOverride = postRaw(world.managerToken, "/api/v1/releases", overridden);
        assertThat(storeOverride.status).isEqualTo(422);
        assertThat(storeOverride.body.get("code").asText()).isEqualTo("RELEASE_TARGETS_INVALID");

        // The full walk to ACTIVE (the machine's happy path), then the
        // rollback: the bundle returns to SIGNED, the live link cleared.
        for (String state : List.of("VALIDATING", "READY", "APPROVAL_PENDING", "APPROVED", "DEPLOYING", "ACTIVE")) {
            transition(world.managerToken, world.app, releaseId, state, null, 200);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM ota_bundles WHERE id = ?", String.class, bundleId))
                .isEqualTo("RELEASED");
        transition(world.managerToken, world.app, releaseId, "ROLLED_BACK", "crash regression in ring ota-alpha", 200);
        Map<String, Object> returned =
                jdbc.queryForMap("SELECT status, release_id FROM ota_bundles WHERE id = ?", bundleId);
        assertThat(returned.get("status")).isEqualTo("SIGNED");
        assertThat(returned.get("release_id")).isNull();

        // The returned bundle is publishable again — on a NEW version
        // row? No: the same version row is still held by the rolled-back
        // release (V1's one-release-per-version), so a re-publish of
        // this bundle waits on the authoring pool the rollback restored.
        // The coherent assertion is the pool fact itself.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM ota_bundles WHERE id = ? AND status = 'SIGNED' AND release_id IS NULL",
                        Integer.class,
                        bundleId))
                .isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // AC1 edge: the one-slot rule — two artifacts on an OTA ring is the
    // same refusal as zero bundles
    // -----------------------------------------------------------------------

    @Test
    void otaReleaseCarriesExactlyOneArtifactSlot() throws Exception {
        OtaWorld world = otaWorld("or-two");
        String ring = otaRing(world);
        UUID storeVersion = createVersion(world.ownerToken, world.app, "4.0." + versionCounter++);
        UUID ios = insertArtifact(storeVersion, "IOS", 600 + versionCounter);
        UUID android = insertArtifact(storeVersion, "ANDROID", 700 + versionCounter);
        Map<String, Object> two = releaseBody(world, storeVersion, ring, ios, "IOS");
        two.put(
                "artifacts",
                List.of(
                        Map.of("platform", "IOS", "artifactId", ios.toString()),
                        Map.of("platform", "ANDROID", "artifactId", android.toString())));
        Recording refused = postRaw(world.managerToken, "/api/v1/releases", two);
        assertThat(refused.status).isEqualTo(422);
        assertThat(refused.body.get("code").asText()).isEqualTo("RELEASE_OTA_BUNDLE_INVALID");
        assertThat(refused.body.get("errors").get("artifacts").asText()).contains("exactly one artifact");
    }

    // -----------------------------------------------------------------------
    // D7 edges: the minAppVersion override's width cap and blank
    // normalization (blank is no override, a stored fact of NULL)
    // -----------------------------------------------------------------------

    @Test
    void minAppVersionOverrideRefusesWidthAndNormalizesBlank() throws Exception {
        OtaWorld world = otaWorld("or-d7");
        JsonNode bundle = authorSignedBundle(world, "1.5.0", "ios");
        String ring = otaRing(world);

        // Over the 100-character floor (V59's column width): one target
        // offense, the store of the refusal keyed by tenant.
        Map<String, Object> wide = releaseBody(
                world,
                UUID.fromString(bundle.get("versionId").asText()),
                ring,
                UUID.fromString(bundle.get("id").asText()),
                "IOS");
        ((List<Map<String, Object>>) wide.get("targets")).get(0).put("minAppVersion", "v".repeat(101));
        Recording refused = postRaw(world.managerToken, "/api/v1/releases", wide);
        assertThat(refused.status).isEqualTo(422);
        assertThat(refused.body.get("code").asText()).isEqualTo("RELEASE_TARGETS_INVALID");
        assertThat(refused.body.get("errors").get(world.tenantId.toString()).asText())
                .contains("at most 100");

        // Blank trims to nothing: the override is absent — NULL in the
        // row, the bundle's own default applying — not an empty string.
        UUID releaseId = createOtaRelease(world, bundle, ring, null, "   ");
        Map<String, Object> targetRow =
                jdbc.queryForMap("SELECT forced, min_app_version FROM release_targets WHERE release_id = ?", releaseId);
        assertThat(targetRow.get("forced")).isNull();
        assertThat(targetRow.get("min_app_version")).isNull();
    }

    // -----------------------------------------------------------------------
    // #181: the concurrent finalize of one bundle — the loser replays,
    // never a 500 (the flush-inside-guard fix's regression test)
    // -----------------------------------------------------------------------

    @Test
    void concurrentFinalizeOfOneBundleReplaysInsteadOfErroring() throws Exception {
        OtaWorld world = otaWorld("or-race");
        // The app's ACTIVE signing key must exist before the race: the
        // key-generation race (a different, documented behavior — lost
        // key insert aborts the whole attempt for a retry) is not under
        // test here; the bundle-row race is.
        authorSignedBundle(world, "1.0.0", "ios");
        JsonNode draft = createDraft(world, "2.0.0", "ios");
        uploadBundleBytes(draft);

        int callers = 2;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Recording>> calls = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                calls.add(pool.submit(() -> {
                    start.await();
                    return postRaw(
                            world.developerToken,
                            "/api/v1/ota/bundles/" + draft.get("id").asText() + "/finalize",
                            Map.of("versionId", draft.get("versionId").asText()));
                }));
            }
            start.countDown();
            List<Recording> answers = new ArrayList<>();
            for (Future<Recording> call : calls) {
                answers.add(call.get(60, TimeUnit.SECONDS));
            }
            // Whichever way the interleaving lands, the #181 contract:
            // the registrar answers 201, the loser the endpoint's own
            // 200 replay (created=false, the same SIGNED bundle view) —
            // never a deferred-violation 500 — and exactly one row
            // lands.
            assertThat(answers).extracting(Recording::status).containsExactlyInAnyOrder(201, 200);
            assertThat(answers.stream()
                            .filter(a -> a.body.path("created").asBoolean(false))
                            .count())
                    .isEqualTo(1);
            assertThat(answers).allSatisfy(a -> {
                assertThat(a.body.path("bundle").path("id").asText())
                        .isEqualTo(draft.get("id").asText());
                assertThat(a.body.path("bundle").path("status").asText()).isEqualTo("SIGNED");
            });
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM ota_bundles WHERE id = ? AND status = 'SIGNED'",
                            Integer.class,
                            UUID.fromString(draft.get("id").asText())))
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // -----------------------------------------------------------------------
    // AC2: the device axes of ring eligibility, application-scoped
    // -----------------------------------------------------------------------

    @Test
    void ringEligibilityHonorsBundleIdAndPlatformAxes() throws Exception {
        OtaWorld world = otaWorld("or-device");
        JsonNode bundle = authorSignedBundle(world, "1.0.0", "ios");
        Map<String, Object> axes = new LinkedHashMap<>();
        axes.put("bundleIds", List.of("com.or-device.bank"));
        axes.put("platformTags", List.of("ios", "android"));
        String ringCode = otaRing(world, "ota-axes", axes);
        UUID releaseId = createOtaRelease(world, bundle, ringCode, null, null);

        // The seam's device read, inside the application's owning scope:
        // the reported pair against the rule, absent axes unconstrained.
        UUID actor = world.managerId;
        assertThat(inTenantScope(world, actor, () -> eligibility.admitsDevice(releaseId, "com.or-device.bank", "ios")))
                .contains(true);
        assertThat(inTenantScope(world, actor, () -> eligibility.admitsDevice(releaseId, "com.or-device.bank", "IOS")))
                .contains(true);
        // Same bundle identifier, another tenant's app: the rule refuses
        // (the companion-spike ambiguity resolves through the rule, not
        // a global identifier registry).
        assertThat(inTenantScope(world, actor, () -> eligibility.admitsDevice(releaseId, "com.other.bank", "ios")))
                .contains(false);
        assertThat(inTenantScope(world, actor, () -> eligibility.admitsDevice(releaseId, "com.or-device.bank", "web")))
                .contains(false);
        // The bundle identifier axis is exact-match (identifiers are
        // case-sensitive strings — NOT normalized like the platform-tag
        // axis above, whose "IOS" answered true against the lowercase
        // rule).
        assertThat(inTenantScope(world, actor, () -> eligibility.admitsDevice(releaseId, "COM.OR-DEVICE.BANK", "ios")))
                .contains(false);
        // A rule-less ring admits every device (the seeded standing).
        JsonNode plain = authorSignedBundle(world, "1.0.1", "ios");
        UUID plainRelease = createOtaRelease(world, plain, otaRing(world, "ota-plain", null), null, null);
        assertThat(inTenantScope(world, actor, () -> eligibility.admitsDevice(plainRelease, "anything.at.all", "web")))
                .contains(true);
        // The concealed miss: a foreign release answers empty.
        assertThat(inTenantScope(
                        world, actor, () -> eligibility.admitsDevice(UUID.randomUUID(), "com.or-device.bank", "ios")))
                .isEmpty();

        // The write surface validates the new axes' shape: unknown axis,
        // empty array, and an oversized platform tag each refuse by
        // field (the ring body otherwise well-formed, so the eligibility
        // validation is the refusal that answers).
        assertThat(postRaw(
                                world.adminToken,
                                "/api/v1/rings",
                                ringBody(world, "dev-a", Map.of("devices", List.of("com.x"))))
                        .body
                        .get("errors")
                        .get("eligibility.devices"))
                .isNotNull();
        assertThat(postRaw(
                                world.adminToken,
                                "/api/v1/rings",
                                ringBody(world, "dev-b", Map.of("platformTags", List.of())))
                        .body
                        .get("errors")
                        .get("eligibility.platformTags"))
                .isNotNull();
        assertThat(postRaw(
                                world.adminToken,
                                "/api/v1/rings",
                                ringBody(world, "dev-c", Map.of("platformTags", List.of("t".repeat(33)))))
                        .body
                        .get("errors")
                        .get("eligibility.platformTags[t" + "t".repeat(32) + "]"))
                .isNotNull();
    }

    /** A well-formed ring body carrying the eligibility under test. */
    private Map<String, Object> ringBody(OtaWorld world, String code, Map<String, Object> eligibility) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("organizationId", world.org.toString());
        body.put("code", code);
        body.put("name", "Shape " + code);
        body.put("position", 2);
        body.put("eligibility", eligibility);
        return body;
    }

    // -----------------------------------------------------------------------
    // AC4: the bundle crash-free gate — NO_DATA, FAILED, PASSED, and the
    // crossing it blocks (the promotion engine's own consult)
    // -----------------------------------------------------------------------

    @Test
    void bundleCrashFreeGateEvaluatesFromSessionsAndBlocksBelowThreshold() throws Exception {
        OtaWorld world = otaWorld("or-gate");
        String ring = otaRing(world);
        // The organization-wide standing (the store releases' leg below)
        // beside the OTA ring's own attachment (the precedence rule:
        // ring-attached overrides org-wide for the same type).
        createGateDefinition(
                world.managerToken, world.org, "bundle-crash-free-threshold", null, true, Map.of("threshold", 99.0));
        createGateDefinition(
                world.managerToken, world.org, "bundle-crash-free-threshold", ring, true, Map.of("threshold", 99.0));
        JsonNode bundle = authorSignedBundle(world, "1.1.0", "ios");
        UUID releaseId = createOtaRelease(world, bundle, ring, null, null);
        transition(world.managerToken, world.app, releaseId, "VALIDATING", null, 200);

        // The manual surface refuses the metric-backed kind, and the
        // VALIDATING instantiation recorded the fail-closed NO_DATA.
        Map<String, Object> manual = new HashMap<>();
        manual.put("applicationId", world.app.toString());
        manual.put("status", "pass");
        manual.put("evidenceUrl", "https://ci.internal/run/1");
        manual.put("observedValue", 100.0);
        assertThat(postRaw(
                                world.managerToken,
                                "/api/v1/releases/" + releaseId + "/gates/bundle-crash-free-threshold/result",
                                manual)
                        .status)
                .isEqualTo(422);
        JsonNode gates =
                get(world.managerToken, "/api/v1/releases/" + releaseId + "/gates?applicationId=" + world.app, 200);
        JsonNode gate = gateOf(gates, "bundle-crash-free-threshold");
        assertThat(gate.get("status").asText()).isEqualTo("NO_DATA");
        assertThat(gate.get("metricBacked").asBoolean()).isTrue();
        assertThat(gate.get("schema").asText()).isEqualTo("NUMERIC_MIN");

        // The crossing the promotion engine shares is blocked while the
        // data is missing (fail-closed, PRD 33's ethos).
        transition(world.managerToken, world.app, releaseId, "READY", null, 422);

        // SESSION reports land against the release (the SDK's standing
        // attribution; the funnel's bundle-keyed attribution is S16-08):
        // 95% is below the 99 threshold — the crossing stays blocked,
        // and the refusal names the gate (a refused crossing rolls its
        // evaluation records back with it, so the denial — not a
        // committed row — is the below-threshold observable).
        insertSession(releaseId, world, 100, 5);
        JsonNode blocked = transition(world.managerToken, world.app, releaseId, "READY", null, 422);
        assertThat(blocked.get("errors").get("ReleaseGatePolicy").asText())
                .contains("bundle-crash-free-threshold")
                .contains("failed");

        // 900 more clean sessions: 995/1000 = 99.5% — PASSED, and the
        // crossing lands (the committed evaluation records the value).
        insertSession(releaseId, world, 900, 0);
        transition(world.managerToken, world.app, releaseId, "READY", null, 200);
        gates = get(world.managerToken, "/api/v1/releases/" + releaseId + "/gates?applicationId=" + world.app, 200);
        gate = gateOf(gates, "bundle-crash-free-threshold");
        assertThat(gate.get("status").asText()).isEqualTo("PASSED");
        assertThat(gate.get("observedValue").asDouble()).isEqualTo(99.5);

        // A store release under the same gate type: the metric has no
        // bundle to answer for — the honest NO_DATA, never another
        // release's numbers.
        UUID storeVersion = createVersion(world.ownerToken, world.app, "9.0." + versionCounter++);
        UUID storeRelease = createStoreRelease(world, storeVersion);
        transition(world.managerToken, world.app, storeRelease, "VALIDATING", null, 200);
        JsonNode storeGates =
                get(world.managerToken, "/api/v1/releases/" + storeRelease + "/gates?applicationId=" + world.app, 200);
        assertThat(gateOf(storeGates, "bundle-crash-free-threshold")
                        .get("status")
                        .asText())
                .isEqualTo("NO_DATA");
    }

    // -----------------------------------------------------------------------
    // The world and its helpers
    // -----------------------------------------------------------------------

    private record OtaWorld(
            UUID org,
            UUID tenantId,
            UUID app,
            UUID managerId,
            String adminToken,
            String ownerToken,
            String managerToken,
            String developerToken) {}

    /** The OTA world: an org, its personas, a tenant app with OTA enabled. */
    private OtaWorld otaWorld(String suffix) {
        UUID orgId = createOrg("Or " + suffix + " Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "or-" + suffix + "-admin", "ORG_ADMIN");
        String ownerToken = loginOfOrgRoleIn(orgId, "or-" + suffix + "-owner", "APP_OWNER");
        String managerToken = loginOfOrgRoleIn(orgId, "or-" + suffix + "-manager", "RELEASE_MANAGER");
        String developerToken = loginOfOrgRoleIn(orgId, "or-" + suffix + "-dev", "DEVELOPER");
        UUID managerId = userIdOf("or-" + suffix + "-manager@example.com");
        UUID tenantId = createTenant(adminToken, orgId, "Or " + suffix + " Bank", "crm-or-" + suffix);
        UUID appId = createTenantApp(
                ownerToken,
                tenantId,
                "Or " + suffix + " App",
                "com.or." + suffix.replace("-", "") + ".bank",
                List.of("IOS", "ANDROID"));
        jdbc.update("UPDATE applications SET ota_enabled = true WHERE id = ?", appId);
        return new OtaWorld(orgId, tenantId, appId, managerId, adminToken, ownerToken, managerToken, developerToken);
    }

    /** The org's own ota-channel ring under an explicit code (one creation per code per world). */
    private String otaRing(OtaWorld world, String code, Map<String, Object> eligibility) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("organizationId", world.org.toString());
        body.put("code", code);
        body.put("name", "OTA " + code);
        // Before the production ring (position 4 stays last); a shared
        // position interleaves by code — the documented rule.
        body.put("position", 3);
        if (eligibility != null) {
            body.put("eligibility", eligibility);
        }
        body.put("distributionChannel", "ota");
        post(world.adminToken, "/api/v1/rings", body, 201);
        return code;
    }

    private String otaRing(OtaWorld world) {
        return otaRing(world, "ota", null);
    }

    /** Creates one bundle, streams its bytes, finalizes+signs, and answers the SIGNED bundle view. */
    private JsonNode authorSignedBundle(OtaWorld world, String version, String tag) throws Exception {
        JsonNode draft = createDraft(world, version, tag);
        uploadBundleBytes(draft);
        return post(
                        world.developerToken,
                        "/api/v1/ota/bundles/" + draft.get("id").asText() + "/finalize",
                        Map.of("versionId", draft.get("versionId").asText()),
                        201)
                .get("bundle");
    }

    /** The draft leg of the authoring flow: the bundle row and its upload grant, no bytes yet. */
    private JsonNode createDraft(OtaWorld world, String version, String tag) {
        Map<String, Object> body = new HashMap<>();
        body.put("applicationId", world.app.toString());
        body.put("bundleVersion", version);
        body.put("platformTag", tag);
        body.put("fileName", "bundle-" + version + ".zip");
        return post(world.developerToken, "/api/v1/ota/bundles", body, 201);
    }

    /** Streams the fixture payload into the draft's upload session (the artifact session's one chunk). */
    private void uploadBundleBytes(JsonNode draft) {
        byte[] bytes = payload(512);
        client.put()
                .uri(URI.create(draft.get("upload").get("uploadUrl").asText()))
                .header("Content-Range", "bytes 0-" + (bytes.length - 1) + "/" + bytes.length)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(bytes)
                .exchange((request, response) -> new Recording(
                        response.getStatusCode().value(),
                        response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                        mapper.createObjectNode()));
    }

    /** The publish: the OTA release on the named ring, carrying the optional D7 overrides. */
    @SuppressWarnings("unchecked")
    private UUID createOtaRelease(
            OtaWorld world, JsonNode bundle, String ringCode, Boolean forced, String minAppVersion) {
        Map<String, Object> body = releaseBody(
                world,
                UUID.fromString(bundle.get("versionId").asText()),
                ringCode,
                UUID.fromString(bundle.get("id").asText()),
                "IOS");
        Map<String, Object> target = ((List<Map<String, Object>>) body.get("targets")).get(0);
        if (forced != null) {
            target.put("forced", forced);
        }
        if (minAppVersion != null) {
            target.put("minAppVersion", minAppVersion);
        }
        return UUID.fromString(post(world.managerToken, "/api/v1/releases", body, 201)
                .get("id")
                .asText());
    }

    private UUID createStoreRelease(OtaWorld world, UUID versionId) {
        return UUID.fromString(post(
                        world.managerToken,
                        "/api/v1/releases",
                        releaseBody(
                                world, versionId, "qa", insertArtifact(versionId, "IOS", 500 + versionCounter), "IOS"),
                        201)
                .get("id")
                .asText());
    }

    private Map<String, Object> releaseBody(
            OtaWorld world, UUID versionId, String ring, UUID artifactId, String platform) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", world.app.toString());
        body.put("versionId", versionId.toString());
        body.put("ring", ring);
        body.put("artifacts", List.of(Map.of("platform", platform, "artifactId", artifactId.toString())));
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("tenantId", world.tenantId.toString());
        target.put("testerGroupIds", List.of());
        body.put("targets", List.of(target));
        return body;
    }

    private JsonNode transition(
            String token, UUID applicationId, UUID releaseId, String to, String reason, int expected) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", applicationId.toString());
        body.put("to", to);
        if (reason != null) {
            body.put("reason", reason);
        }
        return post(token, "/api/v1/releases/" + releaseId + "/transitions", body, expected);
    }

    private JsonNode createGateDefinition(
            String token,
            UUID organizationId,
            String gateType,
            String ring,
            boolean required,
            Map<String, Object> config) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("organizationId", organizationId.toString());
        body.put("gateType", gateType);
        body.put("required", required);
        body.put("config", config);
        body.put("ring", ring);
        return post(token, "/api/v1/gate-definitions", body, 201);
    }

    private static JsonNode gateOf(JsonNode gates, String gateType) {
        for (JsonNode gate : gates.get("gates")) {
            if (gateType.equals(gate.get("gateType").asText())) {
                return gate;
            }
        }
        throw new IllegalStateException("gate '" + gateType + "' not in view");
    }

    /** One SESSION report row (the SDK's release-keyed attribution the gate falls back to). */
    private void insertSession(UUID releaseId, OtaWorld world, int sessions, int crashes) {
        jdbc.update(
                "INSERT INTO analytics_events (id, organization_id, tenant_id, application_id, version_id, "
                        + "release_id, event_type, sessions, crash_sessions, anr_sessions, occurred_at, received_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?::uuid, 'SESSION', ?, ?, 0, now(), now())",
                UUID.randomUUID(),
                world.org,
                world.tenantId,
                world.app,
                jdbc.queryForObject("SELECT version_id FROM releases WHERE id = ?", UUID.class, releaseId),
                releaseId,
                sessions,
                crashes);
    }

    /** Runs the seam read inside the application's owning scope (the harness the service routes establish). */
    private <T> T inTenantScope(OtaWorld world, UUID actorId, java.util.function.Supplier<T> work) {
        return scopeExecutor.inScope(world.tenantId, actorId, work);
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

    private UUID createTenantApp(String token, UUID tenantId, String name, String bundleId, List<String> platforms) {
        Map<String, Object> body = new HashMap<>();
        body.put("tenantId", tenantId.toString());
        body.put("name", name);
        body.put("platforms", platforms);
        body.put("bundleId", platforms.contains("IOS") ? bundleId : null);
        body.put("packageName", platforms.contains("ANDROID") ? bundleId : null);
        return UUID.fromString(
                post(token, "/api/v1/applications", body, 201).get("id").asText());
    }

    private UUID createTenant(String token, UUID organizationId, String name, String externalId) {
        JsonNode created = post(
                token,
                "/api/v1/tenants",
                Map.of("organizationId", organizationId.toString(), "name", name, "externalId", externalId),
                201);
        return UUID.fromString(created.get("id").asText());
    }

    /** Creates one organization per test; repeated calls for the same name are a fixture bug. */
    private UUID createOrg(String orgName) {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        return UUID.fromString(post(devToken, "/api/v1/organizations", Map.of("name", orgName), 201)
                .get("id")
                .asText());
    }

    /** Registers a user with the given role into an EXISTING organization; returns their token. */
    private String loginOfOrgRoleIn(UUID orgId, String email, String role) {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        JsonNode invitation = post(
                devToken,
                "/api/v1/auth/invitations",
                Map.of("email", email + "@example.com", "organizationId", orgId.toString(), "role", role),
                201);
        client.post()
                .uri("/api/v1/auth/register")
                .body(Map.of(
                        "invitationToken",
                        invitation.get("invitationToken").asText(),
                        "email",
                        email + "@example.com",
                        "password",
                        PASSWORD,
                        "fullName",
                        "Or " + role))
                .retrieve()
                .body(String.class);
        return accessToken(login(email + "@example.com", PASSWORD));
    }

    private UUID userIdOf(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
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

    private JsonNode post(String bearer, String uri, Map<String, Object> body, Integer expectedStatus) {
        Recording recording = exchange(uri, bearer, "POST", body);
        if (expectedStatus != null) {
            assertThat(recording.status)
                    .as("POST %s -> %s", uri, recording.body)
                    .isEqualTo(expectedStatus);
        }
        return recording.body;
    }

    private Recording postRaw(String bearer, String uri, Map<String, Object> body) {
        return exchange(uri, bearer, "POST", body);
    }

    private JsonNode get(String bearer, String uri, int expectedStatus) {
        return exchange(uri, bearer, "GET", null).expect(expectedStatus);
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
                    response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                    bytes.length == 0 ? mapper.createObjectNode() : mapper.readTree(bytes));
        });
    }

    private static byte[] payload(int size) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) ('a' + (i % 26));
        }
        return bytes;
    }

    private static String sha256Of(byte[] bytes) throws Exception {
        return java.util.HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private record Recording(int status, String contentType, JsonNode body) {

        JsonNode expect(int expectedStatus) {
            assertThat(status).as("HTTP status for %s", body.toString()).isEqualTo(expectedStatus);
            return body;
        }
    }
}
