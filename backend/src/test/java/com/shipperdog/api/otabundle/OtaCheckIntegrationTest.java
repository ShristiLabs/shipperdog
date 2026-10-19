package com.shipperdog.api.otabundle;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.analytics.TelemetryTokenCodec;
import com.shipperdog.api.config.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
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
 * The S16-07 acceptance flow (issue #169, ADR-0010 workstream 4): the
 * device check's whole chain end to end — the {@code sdc1.} app token
 * authenticating the route, the resolution (application standing, the
 * channel's live release, ring eligibility, rollout standing, the
 * deterministic bucket, the version/content-hash comparison), the
 * served manifest (the download URL that streams the bundle's bytes
 * through the existing proxy, the check-time {@code sdt1.} mint bound
 * to the release, the D7 overrides, the server-owned poll cadence),
 * every 204 refusal leg, and the server-side counting (the outcome
 * meters and the one {@code OTA_CHECK} row the update-available
 * outcome inserts, D9).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
class OtaCheckIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";
    private static final String PASSWORD = "a-perfectly-valid-password";

    /** The compose GCS emulator image, in a container (ADR-0003) — the bundle fixture streams bytes through it. */
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
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(
                "shipperdog.artifact.storage.host", () -> "http://" + GCS.getHost() + ":" + GCS.getMappedPort(4443));
        registry.add("shipperdog.ota.app-token-key", () -> "test-ota-app-token-key");
    }

    @LocalServerPort
    int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private TelemetryTokenCodec telemetryTokens;

    @Autowired
    private MeterRegistry meters;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    // -----------------------------------------------------------------------
    // AC1: update available — the manifest, the download, the mint
    // -----------------------------------------------------------------------

    @Test
    void updateAvailableServesManifestDownloadAndIngestToken() throws Exception {
        OtaWorld world = otaWorld("ck-serve");
        byte[] bytes = payload(512);
        JsonNode bundle = authorSignedBundle(world, "1.4.2", "ios", bytes);
        UUID releaseId = activeOtaRelease(world, bundle, otaRing(world), true, "2.1.0");
        attachRollout(world, releaseId, stages(10000));
        String token = issueToken(world, bundle);

        double before = meters.counter(MetricNames.OTA_CHECK_COUNT, "outcome", "update_available")
                .count();
        Recording check = check(token, "device-ck-1", "ios", "2.0.0", null, "ota");
        assertThat(check.status).isEqualTo(200);
        JsonNode manifest = check.body;
        assertThat(manifest.get("bundleVersion").asText()).isEqualTo("1.4.2");
        assertThat(manifest.get("url").asText()).startsWith("/api/v1/downloads/");
        assertThat(manifest.get("sha256").asText()).isEqualTo(sha256Of(bytes)).hasSize(64);
        assertThat(manifest.get("signature").asText()).isNotBlank();
        assertThat(manifest.get("keyId").asText()).isNotBlank();
        assertThat(manifest.get("sizeBytes").asLong()).isEqualTo(512L);
        // The D7 per-target overrides the release carried (forced=true,
        // minAppVersion=2.1.0) beat the bundle's own defaults.
        assertThat(manifest.get("forced").asBoolean()).isTrue();
        assertThat(manifest.get("minAppVersion").asText()).isEqualTo("2.1.0");
        assertThat(manifest.get("pollIntervalSeconds").asLong()).isEqualTo(3600L);

        // The url streams the bundle's bytes through the existing proxy,
        // checksum intact (the check -> download handoff, D5).
        byte[] downloaded = client.get()
                .uri(URI.create(manifest.get("url").asText()))
                .exchange((request, response) -> response.getBody().readAllBytes());
        assertThat(downloaded).isEqualTo(bytes);

        // The ingestToken is a valid sdt1. for exactly this release and
        // the release's target tenant (D4): lifecycle telemetry flows
        // through the existing ingest with zero new auth surface.
        var grant = telemetryTokens.parse(manifest.get("ingestToken").asText());
        assertThat(grant).isPresent();
        assertThat(grant.orElseThrow().releaseId()).isEqualTo(releaseId);
        assertThat(grant.orElseThrow().tenantId()).isEqualTo(world.tenantId);
        assertThat(grant.orElseThrow().deviceId()).isNull();

        // The server-side check counting (D9): the outcome meter and the
        // one OTA_CHECK row — bundle-attributed, release-known, the
        // funnel's tenant grain the grant shares.
        assertThat(meters.counter(MetricNames.OTA_CHECK_COUNT, "outcome", "update_available")
                        .count())
                .isGreaterThan(before);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT tenant_id, application_id, release_id, ota_bundle_id, result, device_id, tester_id "
                        + "FROM analytics_events WHERE release_id = ? AND event_type = 'OTA_CHECK'",
                releaseId);
        assertThat(row.get("tenant_id")).isEqualTo(world.tenantId);
        assertThat(row.get("application_id")).isEqualTo(world.app);
        assertThat(row.get("ota_bundle_id"))
                .isEqualTo(UUID.fromString(bundle.get("id").asText()));
        assertThat(row.get("result")).isEqualTo("update_available");
        assertThat(row.get("device_id")).isNull();
        assertThat(row.get("tester_id")).isNull();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM audit_events WHERE event_type = 'DOWNLOAD_ISSUED' "
                                + "AND artifact_id = ? AND detail ->> 'channel' = 'ota'",
                        Integer.class,
                        UUID.fromString(bundle.get("id").asText())))
                .isEqualTo(1);

        // The same device asking again gets the same answer: the check is
        // stable per device and release (nothing consumed by serving).
        Recording again = check(token, "device-ck-1", "ios", "2.0.0", null, "ota");
        assertThat(again.status).isEqualTo(200);
        assertThat(again.body.get("bundleVersion").asText()).isEqualTo("1.4.2");
    }

    // -----------------------------------------------------------------------
    // AC2: the 204 paths — up-to-date (label and content hash)
    // -----------------------------------------------------------------------

    @Test
    void upToDateByLabelAndByContentHashAnswersNoContent() throws Exception {
        OtaWorld world = otaWorld("ck-current");
        byte[] bytes = payload(512);
        JsonNode old = authorSignedBundle(world, "1.4.2", "ios", bytes);
        JsonNode current = authorSignedBundle(world, "1.4.3", "ios", bytes);
        UUID releaseId = activeOtaRelease(world, current, otaRing(world), null, null);
        attachRollout(world, releaseId, stages(10000));
        String token = issueToken(world, current);

        // The exact label the release serves.
        double before = meters.counter(MetricNames.OTA_CHECK_COUNT, "outcome", "up_to_date")
                .count();
        assertThat(check(token, "device-ck-2", "ios", "2.0.0", "1.4.3", "ota").status)
                .isEqualTo(204);
        // A different label of the SAME bytes (the old label names a
        // bundle whose content hash equals the candidate's).
        assertThat(check(token, "device-ck-2", "ios", "2.0.0", "1.4.2", "ota").status)
                .isEqualTo(204);
        // Both legs counted up_to_date (D9's funnel split — the wire
        // carries no reason, the meters do).
        assertThat(meters.counter(MetricNames.OTA_CHECK_COUNT, "outcome", "up_to_date")
                        .count())
                .isGreaterThanOrEqualTo(before + 2);
        // No update row landed for either: the no-update check inserts
        // nothing (D9 — no release to attribute beyond the meters).
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM analytics_events WHERE release_id = ? AND event_type = 'OTA_CHECK'",
                        Integer.class,
                        releaseId))
                .isEqualTo(0);
        assertThat(old.get("sha256").asText()).isEqualTo(current.get("sha256").asText());
    }

    // -----------------------------------------------------------------------
    // AC3: deterministic bucketing — below the cut, then served
    // -----------------------------------------------------------------------

    @Test
    void bucketingBelowTheCutAnswersNoContentUntilTheStageWidens() throws Exception {
        OtaWorld world = otaWorld("ck-bucket");
        JsonNode bundle = authorSignedBundle(world, "1.4.2", "ios", payload(512));
        UUID releaseId = activeOtaRelease(world, bundle, otaRing(world), null, null);
        JsonNode plan = attachRollout(world, releaseId, stages(1, 10000));
        String salt = plan.get("planSalt").asString();
        String token = issueToken(world, bundle);

        // A device whose bucket the first stage (1 basis point) does not
        // reach — deterministic, searched not seeded, stable by D6.
        String deviceId = deviceOutsideOf(salt, 1);
        assertThat(OtaDeviceBucketing.bucketOf(deviceId, salt)).isGreaterThanOrEqualTo(1);
        double before = meters.counter(MetricNames.OTA_CHECK_COUNT, "outcome", "below_stage")
                .count();
        assertThat(check(token, deviceId, "ios", "2.0.0", null, "ota").status).isEqualTo(204);
        // Stability: the same device asks twice, the same standing holds.
        assertThat(check(token, deviceId, "ios", "2.0.0", null, "ota").status).isEqualTo(204);
        // Both waits counted below_stage (D9 — the stage the fleet sits
        // behind is the operator's live widening signal).
        assertThat(meters.counter(MetricNames.OTA_CHECK_COUNT, "outcome", "below_stage")
                        .count())
                .isGreaterThanOrEqualTo(before + 2);
        // A device the first stage DOES reach is served now.
        String inside = deviceInsideOf(salt, 1);
        assertThat(check(token, inside, "ios", "2.0.0", null, "ota").status).isEqualTo(200);

        // Widening to the full space serves the waiting device — and
        // never removes the one already served (cumulative stages).
        post(
                world.managerToken,
                "/api/v1/ota/rollouts/" + releaseId + "/advance",
                Map.of("applicationId", world.app.toString()),
                200);
        assertThat(check(token, deviceId, "ios", "2.0.0", null, "ota").status).isEqualTo(200);
        assertThat(check(token, inside, "ios", "2.0.0", null, "ota").status).isEqualTo(200);
    }

    // -----------------------------------------------------------------------
    // AC2/AC4: halted and paused standing; the missing plan
    // -----------------------------------------------------------------------

    @Test
    void haltedRolloutAnswersNoContentUntilResumed() throws Exception {
        OtaWorld world = otaWorld("ck-halt");
        JsonNode bundle = authorSignedBundle(world, "1.4.2", "ios", payload(512));
        UUID releaseId = activeOtaRelease(world, bundle, otaRing(world), null, null);
        // A multi-stage plan: a single full-space stage commences straight
        // to COMPLETE, and a completed plan has nothing to halt. The probe
        // device is searched inside the first stage's cut — deterministic,
        // never seeded.
        String salt = attachRollout(world, releaseId, stages(5000, 10000))
                .get("planSalt")
                .asString();
        String token = issueToken(world, bundle);
        String deviceId = deviceInsideOf(salt, 5000);
        assertThat(check(token, deviceId, "ios", "2.0.0", null, "ota").status).isEqualTo(200);

        post(
                world.managerToken,
                "/api/v1/ota/rollouts/" + releaseId + "/halt",
                Map.of("applicationId", world.app.toString(), "reason", "a spike in OTA_FAILED reports"),
                200);
        assertThat(check(token, deviceId, "ios", "2.0.0", null, "ota").status).isEqualTo(204);

        post(
                world.managerToken,
                "/api/v1/ota/rollouts/" + releaseId + "/resume",
                Map.of("applicationId", world.app.toString()),
                200);
        assertThat(check(token, deviceId, "ios", "2.0.0", null, "ota").status).isEqualTo(200);

        post(
                world.managerToken,
                "/api/v1/ota/rollouts/" + releaseId + "/pause",
                Map.of("applicationId", world.app.toString(), "note", "hold during the halt"),
                200);
        assertThat(check(token, deviceId, "ios", "2.0.0", null, "ota").status).isEqualTo(204);

        post(
                world.managerToken,
                "/api/v1/ota/rollouts/" + releaseId + "/resume",
                Map.of("applicationId", world.app.toString()),
                200);
        assertThat(check(token, deviceId, "ios", "2.0.0", null, "ota").status).isEqualTo(200);
    }

    @Test
    void liveReleaseWithoutItsPlanAnswersNoContent() throws Exception {
        OtaWorld world = otaWorld("ck-noplan");
        JsonNode bundle = authorSignedBundle(world, "1.4.2", "ios", payload(512));
        activeOtaRelease(world, bundle, otaRing(world), null, null);
        String token = issueToken(world, bundle);
        double before = meters.counter(MetricNames.OTA_CHECK_COUNT, "outcome", "no_rollout")
                .count();
        // The plan is the operator's deliberate act that opens the funnel:
        // a live release with no plan serves nothing yet.
        assertThat(check(token, "device-ck-5", "ios", "2.0.0", null, "ota").status)
                .isEqualTo(204);
        // The wire is uniform; the funnel split lives in the meters (D9):
        // this leg counted no_rollout.
        assertThat(meters.counter(MetricNames.OTA_CHECK_COUNT, "outcome", "no_rollout")
                        .count())
                .isGreaterThan(before);
        // Nothing was served, so nothing was minted: no download grant, no
        // audit trail of an issuance that never happened.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM audit_events WHERE event_type = 'DOWNLOAD_ISSUED' "
                                + "AND artifact_id = ?",
                        Integer.class,
                        UUID.fromString(bundle.get("id").asText())))
                .isEqualTo(0);
    }

    // -----------------------------------------------------------------------
    // AC2/AC5: the application's own standing, the channel, eligibility
    // -----------------------------------------------------------------------

    @Test
    void applicationStandingChannelAndEligibilityGateTheAnswer() throws Exception {
        OtaWorld world = otaWorld("ck-gates");
        JsonNode bundle = authorSignedBundle(world, "1.4.2", "ios", payload(512));
        UUID releaseId = activeOtaRelease(world, bundle, otaRing(world), null, null);
        attachRollout(world, releaseId, stages(10000));
        String token = issueToken(world, bundle);

        // OTA disabled after the fleet's token shipped: resolve()
        // authenticates the token by design — the POLICY lives here (the
        // #182 carry-forward), answered as the uniform 204.
        jdbc.update("UPDATE applications SET ota_enabled = false WHERE id = ?", world.app);
        assertThat(check(token, "device-ck-6", "ios", "2.0.0", null, "ota").status)
                .isEqualTo(204);
        jdbc.update("UPDATE applications SET ota_enabled = true WHERE id = ?", world.app);
        assertThat(check(token, "device-ck-6", "ios", "2.0.0", null, "ota").status)
                .isEqualTo(200);

        // The archived application: the same uniform 204.
        jdbc.update("UPDATE applications SET status = 'ARCHIVED' WHERE id = ?", world.app);
        assertThat(check(token, "device-ck-6", "ios", "2.0.0", null, "ota").status)
                .isEqualTo(204);
        jdbc.update("UPDATE applications SET status = 'ACTIVE' WHERE id = ?", world.app);

        // A channel with no live release of the application.
        assertThat(check(token, "device-ck-6", "ios", "2.0.0", null, "production").status)
                .isEqualTo(204);
    }

    @Test
    void ringDeviceAxesExcludeWithNoContent() throws Exception {
        OtaWorld world = otaWorld("ck-axes");
        JsonNode bundle = authorSignedBundle(world, "1.4.2", "ios", payload(512));
        Map<String, Object> axes = new LinkedHashMap<>();
        axes.put("platformTags", List.of("android"));
        UUID releaseId = activeOtaRelease(world, bundle, otaRing(world, "ota-android", axes), null, null);
        attachRollout(world, releaseId, stages(10000));
        String token = issueToken(world, bundle);

        // The ring's platform axis excludes an ios-tagged device.
        assertThat(check(token, "device-ck-7", "ios", "2.0.0", null, "ota-android").status)
                .isEqualTo(204);
        // An android-tagged device of the same fleet passes.
        assertThat(check(token, "device-ck-7", "android", "2.0.0", null, "ota-android").status)
                .isEqualTo(200);
    }

    // -----------------------------------------------------------------------
    // AC5/AC6: the credential shapes and the query validation
    // -----------------------------------------------------------------------

    @Test
    void credentialAndQueryShapesAnswerUniformly() throws Exception {
        OtaWorld world = otaWorld("ck-auth");
        JsonNode bundle = authorSignedBundle(world, "1.4.2", "ios", payload(512));
        UUID releaseId = activeOtaRelease(world, bundle, otaRing(world), null, null);
        attachRollout(world, releaseId, stages(10000));
        String token = issueToken(world, bundle);

        // No credential: the method-security denial's guaranteed envelope
        // (the GlobalExceptionHandler's 403 problem+json — the platform's
        // uniform shape for every @PreAuthorize refusal, anonymous
        // included; it carries no hint of what exists either way).
        assertThat(checkRaw(null, "device-ck-8", "ios", "2.0.0", null, "ota").status)
                .isEqualTo(403);
        // A garbage sdc1.: the filter's uniform 401, never a reason.
        assertThat(checkRaw("sdc1.not-a-token", "device-ck-8", "ios", "2.0.0", null, "ota").status)
                .isEqualTo(401);
        // A console persona's JWT: authenticated, without the one
        // authority the route honors — the same 403 envelope.
        assertThat(checkRaw(world.managerToken, "device-ck-8", "ios", "2.0.0", null, "ota").status)
                .isEqualTo(403);

        // The query's own shape: field-addressed 400s, never a silent 204
        // that would mis-bucket a malformed report.
        Recording missing = checkRaw(token, "  ", "ios", "2.0.0", null, "ota");
        assertThat(missing.status).isEqualTo(400);
        assertThat(missing.body.get("errors").get("deviceId").asText()).contains("required");
        Recording overlong = checkRaw(token, "device-ck-8", "ios", "2.0.0", null, "channel-of-" + "x".repeat(80));
        assertThat(overlong.status).isEqualTo(400);
        assertThat(overlong.body.get("errors").get("channel").asText()).contains("at most");

        // A revoked fleet token stops authenticating immediately.
        UUID tokenId =
                jdbc.queryForObject("SELECT id FROM ota_app_tokens WHERE application_id = ?", UUID.class, world.app);
        post(
                world.developerToken,
                "/api/v1/ota/app-tokens/" + tokenId + "/revoke",
                Map.of("applicationId", world.app.toString()),
                200);
        assertThat(check(token, "device-ck-8", "ios", "2.0.0", null, "ota").status)
                .isEqualTo(401);
    }

    // -----------------------------------------------------------------------
    // AC4: the D7 fuse across targets, and the bundle defaults it falls to
    // -----------------------------------------------------------------------

    @Test
    void forcedFusesTrueForwardAndMinAppVersionFallsBackOnDisagreement() throws Exception {
        OtaWorld world = orgSharedOtaWorld("ck-fuse");
        UUID secondTenant =
                jdbc.queryForObject("SELECT id FROM tenants WHERE external_id = ?", UUID.class, "crm-ck-fuse-2");
        // The bundle's own defaults: NOT forced, floor 1.9.0 — so a true
        // on the wire can only come from the fuse, never the default.
        Map<String, Object> authored = new HashMap<>();
        authored.put("applicationId", world.app.toString());
        authored.put("bundleVersion", "1.4.2");
        authored.put("platformTag", "ios");
        authored.put("fileName", "bundle-fuse.zip");
        authored.put("forced", false);
        authored.put("minAppVersion", "1.9.0");
        JsonNode bundle = authorSignedBundle(world, authored, payload(512));
        otaRing(world, "ota-fuse", null);
        UUID releaseId = activeOtaRelease(
                world,
                bundle,
                "ota-fuse",
                List.of(targetOf(world.tenantId, true, "2.2.0"), targetOf(secondTenant, false, "2.3.0")));
        attachRollout(world, releaseId, stages(10000));
        String token = issueToken(world, bundle);

        // forced: one target says true -> true-forward, even though the
        // other (and the bundle itself) say false. minAppVersion: the
        // targets disagree (2.2.0 vs 2.3.0) -> no consensus -> the
        // bundle's own 1.9.0.
        JsonNode manifest = check(token, "device-ck-9", "ios", "2.0.0", null, "ota-fuse").body;
        assertThat(manifest.get("forced").asBoolean()).isTrue();
        assertThat(manifest.get("minAppVersion").asText()).isEqualTo("1.9.0");

        // The funnel grain with TWO targets on the release: the grant
        // binds the first target tenant — min(UUID) — deterministically.
        UUID expectedTenant = world.tenantId.compareTo(secondTenant) < 0 ? world.tenantId : secondTenant;
        var grant = telemetryTokens.parse(manifest.get("ingestToken").asText());
        assertThat(grant).isPresent();
        assertThat(grant.orElseThrow().tenantId()).isEqualTo(expectedTenant);
        assertThat(grant.orElseThrow().releaseId()).isEqualTo(releaseId);

        // The platform tag and channel arrive as devices send them —
        // uppercase — and the vocabulary normalization still resolves.
        assertThat(check(token, "device-ck-9", "IOS", "2.0.0", null, "OTA-FUSE").status)
                .isEqualTo(200);
    }

    @Test
    void releaseWithoutOverridesServesTheBundleDefaults() throws Exception {
        OtaWorld world = otaWorld("ck-def");
        Map<String, Object> authored = new HashMap<>();
        authored.put("applicationId", world.app.toString());
        authored.put("bundleVersion", "1.4.2");
        authored.put("platformTag", "ios");
        authored.put("fileName", "bundle-def.zip");
        authored.put("forced", true);
        authored.put("minAppVersion", "1.9.0");
        JsonNode bundle = authorSignedBundle(world, authored, payload(512));
        otaRing(world);
        UUID releaseId = activeOtaRelease(world, bundle, "ota", null, null);
        attachRollout(world, releaseId, stages(10000));
        String token = issueToken(world, bundle);

        // No target carries an override: the bundle's own forced flag
        // and floor flow to the manifest untouched.
        JsonNode manifest = check(token, "device-ck-10", "ios", "2.0.0", null, "ota").body;
        assertThat(manifest.get("forced").asBoolean()).isTrue();
        assertThat(manifest.get("minAppVersion").asText()).isEqualTo("1.9.0");
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
            String managerToken,
            String developerToken) {}

    /** The check world: an org, its personas, a tenant app with OTA enabled. */
    private OtaWorld otaWorld(String suffix) {
        UUID orgId = createOrg("Ck " + suffix + " Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "ck-" + suffix + "-admin", "ORG_ADMIN");
        String managerToken = loginOfOrgRoleIn(orgId, "ck-" + suffix + "-manager", "RELEASE_MANAGER");
        String developerToken = loginOfOrgRoleIn(orgId, "ck-" + suffix + "-dev", "DEVELOPER");
        UUID managerId = userIdOf("ck-" + suffix + "-manager@example.com");
        UUID tenantId = createTenant(adminToken, orgId, "Ck " + suffix + " Bank", "crm-ck-" + suffix);
        UUID appId = createTenantApp(
                adminToken,
                tenantId,
                "Ck " + suffix + " App",
                "com.ck." + suffix.replace("-", "") + ".bank",
                List.of("IOS", "ANDROID"));
        jdbc.update("UPDATE applications SET ota_enabled = true WHERE id = ?", appId);
        return new OtaWorld(orgId, tenantId, appId, managerId, adminToken, managerToken, developerToken);
    }

    /** The org's own ota-channel ring under an explicit code (one creation per code per world). */
    private String otaRing(OtaWorld world) {
        return otaRing(world, "ota", null);
    }

    /**
     * The fuse fixture's world: an ORGANIZATION-shared application two
     * tenants hold through ACTIVE authorizations — the only shape a
     * multi-target release (the D7 fuse's input) can address. The
     * world's tenantId is the FIRST tenant; the second is reachable by
     * its external id {@code crm-<suffix>-2}.
     */
    private OtaWorld orgSharedOtaWorld(String suffix) {
        UUID orgId = createOrg("Ck " + suffix + " Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "ck-" + suffix + "-admin", "ORG_ADMIN");
        String managerToken = loginOfOrgRoleIn(orgId, "ck-" + suffix + "-manager", "RELEASE_MANAGER");
        String developerToken = loginOfOrgRoleIn(orgId, "ck-" + suffix + "-dev", "DEVELOPER");
        UUID managerId = userIdOf("ck-" + suffix + "-manager@example.com");
        UUID firstTenant = createTenant(adminToken, orgId, "Ck " + suffix + " Bank", "crm-" + suffix);
        UUID secondTenant = createTenant(adminToken, orgId, "Ck " + suffix + " Bank Two", "crm-" + suffix + "-2");
        Map<String, Object> body = new HashMap<>();
        body.put("ownershipScope", "ORGANIZATION");
        body.put("organizationId", orgId.toString());
        body.put("name", "Ck " + suffix + " App");
        body.put("platforms", List.of("IOS"));
        body.put("bundleId", "com.ck." + suffix.replace("-", "") + ".bank");
        UUID appId = UUID.fromString(
                post(adminToken, "/api/v1/applications", body, 201).get("id").asText());
        for (UUID tenant : List.of(firstTenant, secondTenant)) {
            post(
                    adminToken,
                    "/api/v1/applications/" + appId + "/tenants/" + tenant,
                    Map.of("reason", "the fuse fixture's target"),
                    201);
        }
        jdbc.update("UPDATE applications SET ota_enabled = true WHERE id = ?", appId);
        return new OtaWorld(orgId, firstTenant, appId, managerId, adminToken, managerToken, developerToken);
    }

    private String otaRing(OtaWorld world, String code, Map<String, Object> eligibility) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("organizationId", world.org.toString());
        body.put("code", code);
        body.put("name", "OTA " + code);
        body.put("position", 3);
        if (eligibility != null) {
            body.put("eligibility", eligibility);
        }
        body.put("distributionChannel", "ota");
        post(world.adminToken, "/api/v1/rings", body, 201);
        return code;
    }

    /** One bundle authored, fixed bytes streamed, finalized and signed: the SIGNED bundle view. */
    private JsonNode authorSignedBundle(OtaWorld world, String version, String tag, byte[] bytes) {
        Map<String, Object> body = new HashMap<>();
        body.put("applicationId", world.app.toString());
        body.put("bundleVersion", version);
        body.put("platformTag", tag);
        body.put("fileName", "bundle-" + version + ".zip");
        return authorSignedBundle(world, body, bytes);
    }

    /** The same walk from a full authoring body (the forced/minAppVersion defaults ride it). */
    private JsonNode authorSignedBundle(OtaWorld world, Map<String, Object> body, byte[] bytes) {
        JsonNode draft = post(world.developerToken, "/api/v1/ota/bundles", body, 201);
        client.put()
                .uri(URI.create(draft.get("upload").get("uploadUrl").asText()))
                .header(HttpHeaders.CONTENT_RANGE, "bytes 0-" + (bytes.length - 1) + "/" + bytes.length)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(bytes)
                .exchange((request, response) -> new Recording(
                        response.getStatusCode().value(),
                        response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                        mapper.createObjectNode()));
        return post(
                        world.developerToken,
                        "/api/v1/ota/bundles/" + draft.get("id").asText() + "/finalize",
                        Map.of("versionId", draft.get("versionId").asText()),
                        201)
                .get("bundle");
    }

    /** The publish: the OTA release with optional D7 per-target overrides, born DRAFT with its bundle RELEASED. */
    private UUID createOtaRelease(
            OtaWorld world, JsonNode bundle, String ringCode, Boolean forced, String minAppVersion) {
        return createOtaRelease(world, bundle, ringCode, List.of(targetOf(world.tenantId, forced, minAppVersion)));
    }

    /** One release target's body: the tenant, its empty tester groups, its optional D7 overrides. */
    private static Map<String, Object> targetOf(UUID tenantId, Boolean forced, String minAppVersion) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("tenantId", tenantId.toString());
        target.put("testerGroupIds", List.of());
        if (forced != null) {
            target.put("forced", forced);
        }
        if (minAppVersion != null) {
            target.put("minAppVersion", minAppVersion);
        }
        return target;
    }

    /** The publish with an explicit target list (the D7 fuse's multi-target shape). */
    private UUID createOtaRelease(OtaWorld world, JsonNode bundle, String ringCode, List<Map<String, Object>> targets) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", world.app.toString());
        body.put("versionId", bundle.get("versionId").asText());
        body.put("ring", ringCode);
        body.put(
                "artifacts",
                List.of(Map.of("platform", "IOS", "artifactId", bundle.get("id").asText())));
        body.put("targets", targets);
        return UUID.fromString(post(world.managerToken, "/api/v1/releases", body, 201)
                .get("id")
                .asText());
    }

    /** The full walk to ACTIVE — the check's "live release" vocabulary. */
    private UUID activeOtaRelease(
            OtaWorld world, JsonNode bundle, String ringCode, Boolean forced, String minAppVersion) {
        return activeOtaRelease(world, bundle, ringCode, List.of(targetOf(world.tenantId, forced, minAppVersion)));
    }

    /** The full walk to ACTIVE with an explicit target list (the D7 fuse's multi-target shape). */
    private UUID activeOtaRelease(OtaWorld world, JsonNode bundle, String ringCode, List<Map<String, Object>> targets) {
        UUID releaseId = createOtaRelease(world, bundle, ringCode, targets);
        for (String state : List.of("VALIDATING", "READY", "APPROVAL_PENDING", "APPROVED", "DEPLOYING", "ACTIVE")) {
            transition(world.managerToken, world.app, releaseId, state);
        }
        return releaseId;
    }

    private void transition(String token, UUID applicationId, UUID releaseId, String to) {
        post(
                token,
                "/api/v1/releases/" + releaseId + "/transitions",
                Map.of("applicationId", applicationId.toString(), "to", to),
                200);
    }

    /** Attaches the rollout plan (the commenced ladder) and answers its view. */
    private JsonNode attachRollout(OtaWorld world, UUID releaseId, List<Map<String, Object>> stages) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", world.app.toString());
        body.put("releaseId", releaseId.toString());
        body.put("mode", "CHECKPOINT");
        body.put("stages", stages);
        return post(world.managerToken, "/api/v1/ota/rollouts", body, 201);
    }

    private static List<Map<String, Object>> stages(int... basisPoints) {
        return java.util.Arrays.stream(basisPoints)
                .mapToObj(basis -> Map.<String, Object>of("basisPoints", basis))
                .toList();
    }

    /** Issues the fleet's sdc1. token through the authoring route. */
    private String issueToken(OtaWorld world, JsonNode bundle) {
        return post(
                        world.developerToken,
                        "/api/v1/ota/app-tokens",
                        Map.of("applicationId", world.app.toString(), "name", "fleet token"),
                        201)
                .get("value")
                .asText();
    }

    /** One device check, expecting a manifest or the 204. */
    private Recording check(
            String token, String deviceId, String platform, String appVersion, String bundleVersion, String channel) {
        return checkRaw(token, deviceId, platform, appVersion, bundleVersion, channel);
    }

    private Recording checkRaw(
            String token, String deviceId, String platform, String appVersion, String bundleVersion, String channel) {
        StringBuilder uri = new StringBuilder("/api/v1/ota/check");
        uri.append("?deviceId=").append(deviceId == null ? "" : deviceId.trim().replace(" ", "%20"));
        uri.append("&platform=").append(platform);
        uri.append("&appVersion=").append(appVersion);
        if (bundleVersion != null) {
            uri.append("&bundleVersion=").append(bundleVersion);
        }
        uri.append("&channel=").append(channel);
        RestClient.RequestHeadersSpec<?> spec = client.get().uri(URI.create(uri.toString()));
        if (token != null) {
            spec = spec.headers(headers -> headers.setBearerAuth(token));
        }
        return spec.exchange((request, response) -> {
            byte[] bytes = response.getBody().readAllBytes();
            return new Recording(
                    response.getStatusCode().value(),
                    response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                    bytes.length == 0 ? mapper.createObjectNode() : mapper.readTree(bytes));
        });
    }

    /** A device id whose bucket sits at or beyond the cut (deterministic search — never a seed). */
    private static String deviceOutsideOf(String salt, int basisPoints) {
        for (int i = 0; i < 100000; i++) {
            String candidate = "device-seek-" + i;
            if (OtaDeviceBucketing.bucketOf(candidate, salt) >= basisPoints) {
                return candidate;
            }
        }
        throw new IllegalStateException("no device found outside " + basisPoints + " basis points");
    }

    /** A device id inside the cut (bucket strictly below the basis points). */
    private static String deviceInsideOf(String salt, int basisPoints) {
        for (int i = 0; i < 100000; i++) {
            String candidate = "device-seek-" + i;
            if (OtaDeviceBucketing.bucketOf(candidate, salt) < basisPoints) {
                return candidate;
            }
        }
        throw new IllegalStateException("no device found inside " + basisPoints + " basis points");
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
    private String loginOfOrgRoleIn(UUID orgId, String localPart, String role) {
        String email = localPart + "@example.com";
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
                        "Ck " + role))
                .retrieve()
                .body(String.class);
        return accessToken(login(email, PASSWORD));
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

    private static String sha256Of(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record Recording(int status, String contentType, JsonNode body) {

        Recording expect(int expectedStatus) {
            assertThat(status).as("HTTP status for %s", body.toString()).isEqualTo(expectedStatus);
            return this;
        }
    }
}
