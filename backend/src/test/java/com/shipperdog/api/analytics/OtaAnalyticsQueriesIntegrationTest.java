package com.shipperdog.api.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.shipperdog.api.analytics.internal.service.AnalyticsQueryService;
import com.shipperdog.api.analytics.internal.service.AnalyticsQueryService.OtaAdoptionPointView;
import com.shipperdog.api.analytics.internal.service.AnalyticsQueryService.OtaFunnelView;
import com.shipperdog.api.analytics.internal.service.DashboardQueryService;
import com.shipperdog.api.analytics.internal.service.DashboardQueryService.OtaBundleHealthView;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
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
 * The S16-09 acceptance flow (issue #171): the OTA rollup dimensions
 * and the two service-layer reads the S16-10 dashboard will serve. The
 * fixture walks the real funnel twice over — two released bundles of
 * one application, the fleet's lifecycle reports through the existing
 * ingest, the server-side check rows through the device check — then
 * pins the acceptance criteria: the rollup job's bundle-dimensioned
 * rows (idempotent, the historical NULL-dimension rows untouched by
 * the same pass), the funnel's stage counts, failure breakdown, and
 * adoption curve against the seeded fixture events, the health
 * comparison's current-vs-new crash rates from SESSION events, and
 * the reads' ring and tenant scoping (the concealed miss doctrine).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef",
            "shipperdog.analytics.rollup.enabled=false"
        })
@Testcontainers
class OtaAnalyticsQueriesIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";
    private static final String PASSWORD = "a-perfectly-valid-password";

    /** The compose GCS emulator image, in a container (ADR-0003) — the bundle fixtures stream bytes through it. */
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
    private AnalyticsQueryService queries;

    @Autowired
    private DashboardQueryService dashboards;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    // -----------------------------------------------------------------------
    // AC3: the funnel's stages, failure breakdown, and adoption curve
    // against the seeded fixture events — and the ring filter's seam
    // -----------------------------------------------------------------------

    @Test
    void funnelServesStagesFailureBreakdownAndAdoptionAgainstTheFixture() {
        World world = world("q-funnel");
        seedFunnel(world, "device-f");

        OtaFunnelView funnel = queries.otaFunnel(world.tenantA, world.bundle2, null);
        assertThat(funnel.bundleId()).isEqualTo(world.bundle2);
        assertThat(funnel.bundleVersion()).isEqualTo("1.4.2");
        assertThat(funnel.releaseRef()).startsWith("R-");
        assertThat(funnel.stages().checks()).isEqualTo(5);
        assertThat(funnel.stages().seen()).isEqualTo(5);
        assertThat(funnel.stages().downloads()).isEqualTo(4);
        assertThat(funnel.stages().applied()).isEqualTo(3);
        assertThat(funnel.stages().failed()).isEqualTo(1);
        assertThat(funnel.stages().rolledBack()).isEqualTo(1);

        assertThat(funnel.failures()).hasSize(1);
        assertThat(funnel.failures().get(0).reason()).isEqualTo("checksum_mismatch");
        assertThat(funnel.failures().get(0).count()).isEqualTo(1);

        // The adoption curve over time: day one the fleet's 2-of-4
        // observed devices applied, day two 3-of-5 — cumulative and
        // monotone from the facts.
        assertThat(funnel.adoption()).hasSize(2);
        OtaAdoptionPointView first = funnel.adoption().get(0);
        OtaAdoptionPointView second = funnel.adoption().get(1);
        assertThat(first.seenDevices()).isEqualTo(4);
        assertThat(first.appliedDevices()).isEqualTo(2);
        assertThat(first.adoptionRate()).isCloseTo(0.5, within(1e-9));
        assertThat(second.seenDevices()).isEqualTo(5);
        assertThat(second.appliedDevices()).isEqualTo(3);
        assertThat(second.adoptionRate()).isCloseTo(0.6, within(1e-9));

        // The ring filter narrows to the events that occurred while
        // the release occupied the named ring: the funnel's own ring
        // carries everything, a ring it never occupied nothing.
        OtaFunnelView onOtaRing = queries.otaFunnel(world.tenantA, world.bundle2, "ota");
        assertThat(onOtaRing.stages().seen()).isEqualTo(5);
        assertThat(onOtaRing.adoption()).hasSize(2);
        OtaFunnelView onQaRing = queries.otaFunnel(world.tenantA, world.bundle2, "qa");
        assertThat(onQaRing.stages().seen()).isZero();
        assertThat(onQaRing.stages().checks()).isZero();
        assertThat(onQaRing.adoption()).isEmpty();
        // The breakdown narrows with the rest of the funnel: the
        // fixture's failure never occurred on a ring the release never
        // occupied.
        assertThat(onQaRing.failures()).isEmpty();
        assertThat(onOtaRing.failures()).hasSize(1);

        // An unknown ring code is refused, never silently empty.
        assertThatThrownByCode(
                () -> queries.otaFunnel(world.tenantA, world.bundle2, "not-a-ring"),
                AnalyticsException.Code.INVALID_REQUEST);

        // The rollback crossing detaches the bundle (release_id -> NULL)
        // — attribution is history, so the unfiltered funnel keeps the
        // whole incident evidence, while the ring-filtered read (the
        // rollups' release-anchored grain) answers zero: a detached
        // bundle occupies no ring.
        post(
                world.managerToken,
                "/api/v1/releases/" + world.release2 + "/transitions",
                Map.of(
                        "applicationId",
                        world.app.toString(),
                        "to",
                        "ROLLED_BACK",
                        "reason",
                        "crash loop observed in the fleet"),
                200);
        OtaFunnelView detached = queries.otaFunnel(world.tenantA, world.bundle2, null);
        assertThat(detached.stages().checks()).isEqualTo(5);
        assertThat(detached.stages().seen()).isEqualTo(5);
        assertThat(detached.stages().applied()).isEqualTo(3);
        assertThat(detached.failures()).hasSize(1);
        assertThat(detached.adoption()).hasSize(2);
        assertThat(detached.adoption().get(1).adoptionRate()).isCloseTo(0.6, within(1e-9));
        assertThat(queries.otaFunnel(world.tenantA, world.bundle2, "ota")
                        .stages()
                        .seen())
                .isZero();
    }

    // -----------------------------------------------------------------------
    // The funnel's edge semantics: the failure breakdown's ordering,
    // adoption's 0.0-vs-null doctrine, the daily grain's per-bucket
    // split, and the series vocabulary the new metrics serve
    // -----------------------------------------------------------------------

    @Test
    void funnelEdgesCoverBreakdownOrderingAdoptionSemanticsDailySplitAndSeriesVocabulary() {
        World world = world("q-edges");
        // Midnight-safe anchors: the daily buckets assert EXACTLY two
        // days, so both anchors derive from the UTC day boundary, not
        // from bare now-offsets — a run at 00:01 would otherwise
        // straddle now-2min and now-1min across midnight and grow a
        // third bucket (the CI time bomb this pin removes).
        Instant day2 =
                Instant.now().truncatedTo(java.time.temporal.ChronoUnit.DAYS).minus(Duration.ofMinutes(2));
        Instant day1 = day2.minus(Duration.ofHours(24));
        check(world.appToken, "edge-x");
        check(world.appToken, "edge-w");
        // Day one: three identified observers and one without identity
        // (it counts toward the stage, never toward adoption); two
        // DISTINCT failure reasons in equal numbers — the tie orders
        // by the reason word. Nothing applies on day one.
        ingest(
                world,
                List.of(
                        lifecycle(world, "OTA_SEEN", null, day1.plusSeconds(10), device("edge", "x")),
                        lifecycle(world, "OTA_SEEN", null, day1.plusSeconds(11), device("edge", "y")),
                        lifecycle(world, "OTA_SEEN", null, day1.plusSeconds(12), device("edge", "z")),
                        lifecycle(world, "OTA_SEEN", null, day1.plusSeconds(13), null),
                        lifecycle(world, "OTA_FAILED", "apply_error", day1.plusSeconds(30), device("edge", "x")),
                        lifecycle(
                                world, "OTA_FAILED", "checksum_mismatch", day1.plusSeconds(31), device("edge", "y"))));
        // Day two: one more observer, and it applies.
        ingest(
                world,
                List.of(
                        lifecycle(world, "OTA_SEEN", null, day2, device("edge", "w")),
                        lifecycle(world, "OTA_APPLIED", "SUCCEEDED", day2.plusSeconds(30), device("edge", "w"))));

        OtaFunnelView funnel = queries.otaFunnel(world.tenantA, world.bundle2, null);
        assertThat(funnel.stages().checks()).isEqualTo(2);
        assertThat(funnel.stages().seen()).isEqualTo(5);
        assertThat(funnel.stages().applied()).isEqualTo(1);
        assertThat(funnel.stages().failed()).isEqualTo(2);

        // The tie orders by the reason word: apply_error before
        // checksum_mismatch at one report each.
        assertThat(funnel.failures()).hasSize(2);
        assertThat(funnel.failures().get(0).reason()).isEqualTo("apply_error");
        assertThat(funnel.failures().get(1).reason()).isEqualTo("checksum_mismatch");
        assertThat(funnel.failures().get(0).count()).isEqualTo(1);
        assertThat(funnel.failures().get(1).count()).isEqualTo(1);

        // Adoption: a day with identified observers and NO applies
        // answers the real 0.0 — never null, never missing; the
        // identity-less seen report never enters the curve.
        assertThat(funnel.adoption()).hasSize(2);
        assertThat(funnel.adoption().get(0).seenDevices()).isEqualTo(3);
        assertThat(funnel.adoption().get(0).appliedDevices()).isZero();
        assertThat(funnel.adoption().get(0).adoptionRate()).isCloseTo(0.0, within(1e-9));
        assertThat(funnel.adoption().get(1).seenDevices()).isEqualTo(4);
        assertThat(funnel.adoption().get(1).appliedDevices()).isEqualTo(1);
        assertThat(funnel.adoption().get(1).adoptionRate()).isCloseTo(0.25, within(1e-9));

        // A bundle whose reports carry no device identity at all: the
        // stage counts serve, the curve is honestly empty.
        ingest(
                world,
                List.of(lifecycleOn(world.release1, world.bundle1, "OTA_SEEN", null, day2.plusSeconds(60), null)));
        OtaFunnelView identityless = queries.otaFunnel(world.tenantA, world.bundle1, null);
        assertThat(identityless.stages().seen()).isEqualTo(1);
        assertThat(identityless.adoption()).isEmpty();

        // The daily grain splits per bucket (day one 4 seen, day two
        // 1), and the series endpoint serves the new dictionary
        // vocabulary from the rollups — the release and ring
        // dimensions riding along.
        triggerRollups(world);
        List<Map<String, Object>> dailySeen = jdbc.queryForList(
                "SELECT bucket_start, value FROM metric_daily WHERE application_id = ? AND ota_bundle_id = ? "
                        + "AND metric = 'OTA_SEEN' ORDER BY bucket_start",
                world.app,
                world.bundle2);
        assertThat(dailySeen).hasSize(2);
        assertThat(((Number) dailySeen.get(0).get("value")).doubleValue()).isEqualTo(4.0);
        assertThat(((Number) dailySeen.get(1).get("value")).doubleValue()).isEqualTo(1.0);

        String series = "/api/v1/applications/" + world.app + "/metrics?metric=OTA_SEEN&from="
                + Instant.now().minus(Duration.ofHours(72)) + "&to=" + Instant.now();
        JsonNode points = get(world.managerToken, series, 200);
        // The application grain serves BOTH bundles' rows (the bundle
        // filter is #172's dashboard surface): the five on the new
        // bundle plus the identity-less one on the idle bundle.
        double served = 0;
        for (JsonNode point : points.get("items")) {
            assertThat(point.get("ringCode").asString()).isEqualTo("ota");
            assertThat(point.get("releaseRef").asString()).startsWith("R-");
            served += point.get("value").asDouble();
        }
        assertThat(served).isEqualTo(6.0);
    }

    // -----------------------------------------------------------------------
    // AC5: the tenant seam — foreign and unknown are the concealed miss
    // -----------------------------------------------------------------------

    @Test
    void funnelAndHealthConcealForeignBundlesAndUnknownTenants() {
        World world = world("q-scope");

        // The foreign tenant (no catalog standing on the bundle's
        // application) and the unknown bundle answer the same miss.
        assertThatThrownByCode(
                () -> queries.otaFunnel(world.tenantB, world.bundle2, null), AnalyticsException.Code.BUNDLE_NOT_FOUND);
        assertThatThrownByCode(
                () -> queries.otaFunnel(world.tenantA, UUID.randomUUID(), null),
                AnalyticsException.Code.BUNDLE_NOT_FOUND);
        assertThatThrownByCode(
                () -> queries.otaFunnel(UUID.randomUUID(), world.bundle2, null),
                AnalyticsException.Code.TENANT_NOT_FOUND);

        assertThatThrownByCode(
                () -> dashboards.otaBundleHealth(world.tenantB, world.bundle2),
                AnalyticsException.Code.BUNDLE_NOT_FOUND);
        assertThatThrownByCode(
                () -> dashboards.otaBundleHealth(world.tenantA, UUID.randomUUID()),
                AnalyticsException.Code.BUNDLE_NOT_FOUND);
        assertThatThrownByCode(
                () -> dashboards.otaBundleHealth(UUID.randomUUID(), world.bundle2),
                AnalyticsException.Code.TENANT_NOT_FOUND);
    }

    // -----------------------------------------------------------------------
    // AC4: the health comparison — current vs new bundle crash rates
    // from SESSION events, with the NO_DATA doctrine on the bare side
    // -----------------------------------------------------------------------

    @Test
    void bundleHealthComparesCurrentAndNewCrashRatesFromSessionEvents() {
        World world = world("q-health");
        postSessions(world, world.release2, 100, 1);
        postSessions(world, world.release1, 200, 10);

        OtaBundleHealthView health = dashboards.otaBundleHealth(world.tenantA, world.bundle2);
        assertThat(health.newBundle().bundleId()).isEqualTo(world.bundle2);
        assertThat(health.newBundle().releaseId()).isEqualTo(world.release2);
        assertThat(health.newBundle().releaseRef()).startsWith("R-");
        assertThat(health.newBundle().sessions()).isEqualTo(100);
        assertThat(health.newBundle().crashSessions()).isEqualTo(1);
        assertThat(health.newBundle().crashFreeRate()).isCloseTo(0.99, within(1e-9));

        // The current side: the application's previous released bundle,
        // its crash standing from its own release's SESSION reports.
        assertThat(health.currentBundle().bundleId()).isEqualTo(world.bundle1);
        assertThat(health.currentBundle().sessions()).isEqualTo(200);
        assertThat(health.currentBundle().crashSessions()).isEqualTo(10);
        assertThat(health.currentBundle().crashFreeRate()).isCloseTo(0.95, within(1e-9));
        assertThat(health.crashFreeRateDelta()).isCloseTo(0.04, within(1e-9));

        // A bundle with no sessions yet answers NO_DATA on its side —
        // never a zero — and the comparison stays unevidenced: the
        // current side resolves, the delta does not.
        JsonNode bare = authorSignedBundle(world.developerToken, world.app, "1.5.0", "ios", payload(256));
        OtaBundleHealthView bareHealth = dashboards.otaBundleHealth(
                world.tenantA, UUID.fromString(bare.get("id").asText()));
        assertThat(bareHealth.newBundle().crashFreeRate()).isNull();
        assertThat(bareHealth.newBundle().sessions()).isZero();
        assertThat(bareHealth.newBundle().releaseId()).isNull();
        assertThat(bareHealth.newBundle().releaseRef()).isNull();
        assertThat(bareHealth.currentBundle().bundleId()).isEqualTo(world.bundle2);
        assertThat(bareHealth.crashFreeRateDelta()).isNull();
    }

    // -----------------------------------------------------------------------
    // AC1/AC2: the rollup job's bundle-dimensioned rows, idempotent,
    // with the historical NULL-dimension rows unchanged by the pass
    // -----------------------------------------------------------------------

    @Test
    void rollupsCarryTheBundleDimensionIdempotentlyAndLeaveHistoricalRowsUnchanged() {
        World world = world("q-rollup");
        // The legacy standing first: SESSION reports on the OTA
        // release (the release-keyed family V57 attributes through the
        // release) and the checks' own download issuances (the S8-04
        // trail the manifest mints ride) — rolled up, snapshotted.
        postSessions(world, world.release2, 100, 1);
        checkFleet(world, "device-r");
        triggerRollups(world);
        List<Map<String, Object>> legacyHourly = legacyRows(world, "metric_hourly");
        List<Map<String, Object>> legacyDaily = legacyRows(world, "metric_daily");
        assertThat(legacyHourly).isNotEmpty();

        // The OTA device events land (no legacy fact moves); the same
        // pass recomputes both families.
        ingestLifecycle(world, "device-r");
        triggerRollups(world);

        // AC2: every pre-existing (NULL-dimension) row aggregates
        // exactly as before — byte-identical values, no new legacy rows.
        assertThat(legacyRows(world, "metric_hourly")).isEqualTo(legacyHourly);
        assertThat(legacyRows(world, "metric_daily")).isEqualTo(legacyDaily);

        // AC1: the OTA counts at both grains, bundle-dimensioned,
        // reconciling with the funnel's own stage counts.
        Map<String, Double> hourly = otaSums(world, "metric_hourly");
        assertThat(hourly)
                .containsEntry("OTA_CHECKS", 5.0)
                .containsEntry("OTA_SEEN", 5.0)
                .containsEntry("OTA_DOWNLOADS", 4.0)
                .containsEntry("OTA_APPLIED", 3.0)
                .containsEntry("OTA_FAILURES", 1.0)
                .containsEntry("OTA_ROLLBACKS", 1.0);
        Map<String, Double> daily = otaSums(world, "metric_daily");
        assertThat(daily).containsEntry("OTA_SEEN", 5.0).containsEntry("OTA_APPLIED", 3.0);

        // The ring attribution the legacy rows carry, on the OTA rows
        // too: the release's starting (ota) ring — it never promoted.
        String ringCode = jdbc.queryForObject(
                "SELECT rg.code FROM metric_hourly m JOIN rings rg ON rg.id = m.ring_id "
                        + "WHERE m.application_id = ? AND m.metric = 'OTA_SEEN' LIMIT 1",
                String.class,
                world.app);
        assertThat(ringCode).isEqualTo("ota");

        // The bundle dimension is carried, and the idle bundle (no
        // reports) draws no rows at all.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM metric_hourly WHERE application_id = ? AND ota_bundle_id IS NOT NULL "
                                + "AND ota_bundle_id <> ?",
                        Long.class,
                        world.app,
                        world.bundle2))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM metric_hourly WHERE application_id = ? AND ota_bundle_id = ? "
                                + "AND metric = 'OTA_SEEN' AND value = 4",
                        Long.class,
                        world.app,
                        world.bundle2))
                .isEqualTo(1);

        // Idempotent: the same window recomputes the same numbers —
        // nothing doubles, row for row.
        long otaRowCount = otaRowCount(world);
        triggerRollups(world);
        assertThat(otaSums(world, "metric_hourly")).isEqualTo(hourly);
        assertThat(otaRowCount(world)).isEqualTo(otaRowCount);

        // The cross-release attribution the ingest admits by design (a
        // report may name any bundle of the release's application —
        // attribution is history): a seen report on the NEW release
        // carrying the OLD bundle lands, and the rollup attributes it
        // to the BUNDLE's own release — both bundles' rows co-exist,
        // no upsert-arbiter collision, the trigger stays 200.
        ingest(
                world,
                List.of(lifecycleOn(
                        world.release2,
                        world.bundle1,
                        "OTA_SEEN",
                        null,
                        // The same midnight-safe day-two bucket as the
                        // fixture's events (never a bare now-offset).
                        Instant.now()
                                .truncatedTo(java.time.temporal.ChronoUnit.DAYS)
                                .minus(Duration.ofMinutes(1)),
                        null)));
        triggerRollups(world);
        assertThat(jdbc.queryForMap(
                        "SELECT release_id, value FROM metric_hourly WHERE application_id = ? "
                                + "AND ota_bundle_id = ? AND metric = 'OTA_SEEN'",
                        world.app,
                        world.bundle1))
                .containsEntry("release_id", world.release1)
                .containsEntry("value", 1.0);
        assertThat(otaSums(world, "metric_hourly")).isEqualTo(hourly);
    }

    // -----------------------------------------------------------------------
    // The world and its facts
    // -----------------------------------------------------------------------

    /**
     * One complete OTA world per test: the org, the owning tenant A
     * and the foreign tenant B, the OTA-enabled tenant-owned
     * application, the ota ring, TWO signed bundles — the first
     * released through an older release (the application's CURRENT
     * bundle), the second through the newer ACTIVE release the fleet
     * checks against (the NEW bundle) — and the fleet's app token.
     */
    private World world(String slug) {
        UUID orgId = createOrg("Q " + slug + " Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "q-" + slug + "-admin", "ORG_ADMIN");
        String managerToken = loginOfOrgRoleIn(orgId, "q-" + slug + "-manager", "RELEASE_MANAGER");
        String developerToken = loginOfOrgRoleIn(orgId, "q-" + slug + "-dev", "DEVELOPER");
        UUID tenantA = createTenant(adminToken, orgId, "Q " + slug + " Bank", "crm-q-" + slug);
        UUID tenantB = createTenant(adminToken, orgId, "Q " + slug + " Bank B", "crm-q-" + slug + "-b");
        String tenantAdminToken = loginOfTenantAdminRoleIn(orgId, tenantA, "q-" + slug + "-tenant-admin");
        UUID appId =
                createTenantApp(adminToken, tenantA, "Q " + slug + " App", "com.q." + slug.replace("-", "") + ".bank");
        jdbc.update("UPDATE applications SET ota_enabled = true WHERE id = ?", appId);

        Map<String, Object> ring = new LinkedHashMap<>();
        ring.put("organizationId", orgId.toString());
        ring.put("code", "ota");
        ring.put("name", "Q OTA");
        ring.put("position", 3);
        ring.put("distributionChannel", "ota");
        post(adminToken, "/api/v1/rings", ring, 201);

        // The CURRENT bundle: released through the older release.
        JsonNode first = authorSignedBundle(developerToken, appId, "1.4.0", "ios", payload(384));
        UUID release1 = createOtaRelease(managerToken, appId, first);
        // The NEW bundle: the live release the fleet checks against.
        JsonNode second = authorSignedBundle(developerToken, appId, "1.4.2", "ios", payload(512));
        UUID release2 = createOtaRelease(managerToken, appId, second);
        for (String state : List.of("VALIDATING", "READY", "APPROVAL_PENDING", "APPROVED", "DEPLOYING", "ACTIVE")) {
            post(
                    managerToken,
                    "/api/v1/releases/" + release2 + "/transitions",
                    Map.of("applicationId", appId.toString(), "to", state),
                    200);
        }
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("applicationId", appId.toString());
        plan.put("releaseId", release2.toString());
        plan.put("mode", "CHECKPOINT");
        plan.put("stages", List.of(Map.of("basisPoints", 10000)));
        post(managerToken, "/api/v1/ota/rollouts", plan, 201);

        String appToken = post(
                        developerToken,
                        "/api/v1/ota/app-tokens",
                        Map.of("applicationId", appId.toString(), "name", "fleet token"),
                        201)
                .get("value")
                .asText();
        return new World(
                tenantA,
                tenantB,
                tenantAdminToken,
                appId,
                release1,
                release2,
                UUID.fromString(first.get("id").asText()),
                UUID.fromString(second.get("id").asText()),
                appToken,
                managerToken,
                developerToken);
    }

    private record World(
            UUID tenantA,
            UUID tenantB,
            String tenantAdminToken,
            UUID app,
            UUID release1,
            UUID release2,
            UUID bundle1,
            UUID bundle2,
            String appToken,
            String managerToken,
            String developerToken) {}

    /**
     * The fleet's fixture funnel over the NEW bundle: four devices
     * walk it on the first day (two fully applied, one download
     * failure, one observer), a fifth applies on the second day, and
     * one early adopter rolls back — five checks (server-side rows),
     * five seen, four downloads, three applied, one failure, one
     * rollback.
     */
    private void seedFunnel(World world, String devicePrefix) {
        checkFleet(world, devicePrefix);
        ingestLifecycle(world, devicePrefix);
    }

    /** The fleet's five device checks (the server-side OTA_CHECK rows — and the download issuances they mint). */
    private void checkFleet(World world, String devicePrefix) {
        for (String device : List.of("a", "b", "c", "d", "e")) {
            check(world.appToken, devicePrefix + "-" + device);
        }
    }

    /** The fleet's lifecycle reports across two days. */
    private void ingestLifecycle(World world, String devicePrefix) {
        // Midnight-safe anchors (see the edges test): deterministic
        // UTC days, strictly in the past, never straddling a boundary
        // however the run is timed.
        Instant day2 =
                Instant.now().truncatedTo(java.time.temporal.ChronoUnit.DAYS).minus(Duration.ofMinutes(2));
        Instant day1 = day2.minus(Duration.ofHours(24));
        List<Map<String, Object>> day1Events = List.of(
                lifecycle(world, "OTA_SEEN", null, day1.plusSeconds(60), device(devicePrefix, "a")),
                lifecycle(world, "OTA_SEEN", null, day1.plusSeconds(61), device(devicePrefix, "b")),
                lifecycle(world, "OTA_SEEN", null, day1.plusSeconds(62), device(devicePrefix, "c")),
                lifecycle(world, "OTA_SEEN", null, day1.plusSeconds(63), device(devicePrefix, "d")),
                lifecycle(world, "OTA_DOWNLOAD", "SUCCEEDED", day1.plusSeconds(120), device(devicePrefix, "a")),
                lifecycle(world, "OTA_DOWNLOAD", "SUCCEEDED", day1.plusSeconds(121), device(devicePrefix, "b")),
                lifecycle(world, "OTA_DOWNLOAD", "FAILED", day1.plusSeconds(122), device(devicePrefix, "c")),
                lifecycle(world, "OTA_APPLIED", "SUCCEEDED", day1.plusSeconds(180), device(devicePrefix, "a")),
                lifecycle(world, "OTA_APPLIED", "SUCCEEDED", day1.plusSeconds(181), device(devicePrefix, "b")),
                lifecycle(world, "OTA_FAILED", "checksum_mismatch", day1.plusSeconds(182), device(devicePrefix, "c")));
        List<Map<String, Object>> day2Events = List.of(
                lifecycle(world, "OTA_SEEN", null, day2, device(devicePrefix, "e")),
                lifecycle(world, "OTA_DOWNLOAD", "SUCCEEDED", day2.plusSeconds(30), device(devicePrefix, "e")),
                lifecycle(world, "OTA_APPLIED", "SUCCEEDED", day2.plusSeconds(60), device(devicePrefix, "e")),
                lifecycle(world, "OTA_ROLLED_BACK", null, day2.plusSeconds(90), device(devicePrefix, "b")));
        ingest(world, day1Events);
        ingest(world, day2Events);
    }

    /** One OTA lifecycle report body against the NEW bundle, carrying its device identity. */
    private Map<String, Object> lifecycle(
            World world, String type, String result, Instant occurredAt, Map<String, Object> payload) {
        return lifecycleOn(world.release2, world.bundle2, type, result, occurredAt, payload);
    }

    /** The same report body against an explicit release/bundle pair (the idle bundle's own legs). */
    private Map<String, Object> lifecycleOn(
            UUID releaseId,
            UUID bundleId,
            String type,
            String result,
            Instant occurredAt,
            Map<String, Object> payload) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        if (result != null) {
            event.put("result", result);
        }
        event.put("releaseId", releaseId.toString());
        event.put("eventId", UUID.randomUUID().toString());
        event.put("occurredAt", occurredAt.toString());
        event.put("otaBundleId", bundleId.toString());
        if (payload != null) {
            event.put("otaPayload", payload);
        }
        return event;
    }

    /** The payload's fleet device identity (S16-08's carrier). */
    private static Map<String, Object> device(String prefix, String device) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("device_id", prefix + "-" + device);
        return payload;
    }

    /** Posts one event batch through the reporting tenant's console standing. */
    private void ingest(World world, List<Map<String, Object>> events) {
        assertThat(post(
                                world.tenantAdminToken,
                                "/api/v1/tenants/" + world.tenantA + "/analytics/events",
                                Map.of("events", events),
                                201)
                        .get("ingested")
                        .asInt())
                .isEqualTo(events.size());
    }

    /** One release-keyed SESSION report (the crash/ANR funnel's family). */
    private void postSessions(World world, UUID releaseId, int sessions, int crashes) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "SESSION");
        event.put("releaseId", releaseId.toString());
        event.put("occurredAt", Instant.now().minus(Duration.ofMinutes(5)).toString());
        event.put("sessions", sessions);
        event.put("crashSessions", crashes);
        event.put("anrSessions", 0);
        ingest(world, List.of(event));
    }

    /** The rollup recompute trigger over a window covering both fixture days. */
    private void triggerRollups(World world) {
        post(
                world.managerToken,
                "/api/v1/applications/" + world.app + "/metrics/rollups",
                Map.of(
                        "from",
                        Instant.now().minus(Duration.ofHours(72)).toString(),
                        "to",
                        Instant.now().plus(Duration.ofMinutes(5)).toString()),
                200);
    }

    /** The pre-existing (NULL-dimension) rollup rows of the application, ordered for comparison. */
    private List<Map<String, Object>> legacyRows(World world, String table) {
        return jdbc.queryForList(
                "SELECT metric, bucket_start, release_id, value FROM " + table
                        + " WHERE application_id = ? AND ota_bundle_id IS NULL ORDER BY metric, bucket_start, release_id",
                world.app);
    }

    /** The NEW bundle's OTA metric sums at one grain (the idempotence comparison's own answer). */
    private Map<String, Double> otaSums(World world, String table) {
        Map<String, Double> sums = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT metric, sum(value) AS total FROM " + table
                        + " WHERE application_id = ? AND ota_bundle_id = ? GROUP BY metric ORDER BY metric",
                world.app,
                world.bundle2)) {
            sums.put((String) row.get("metric"), ((Number) row.get("total")).doubleValue());
        }
        return sums;
    }

    /** The NEW bundle's OTA rollup row count (idempotence never adds rows). */
    private long otaRowCount(World world) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM metric_hourly WHERE application_id = ? AND ota_bundle_id = ?",
                Long.class,
                world.app,
                world.bundle2);
        return count == null ? 0 : count;
    }

    /** Asserts the thrown AnalyticsException carries exactly the expected stable code. */
    private static void assertThatThrownByCode(Runnable call, AnalyticsException.Code expected) {
        try {
            call.run();
        } catch (AnalyticsException e) {
            assertThat(e.code()).isEqualTo(expected);
            return;
        }
        throw new AssertionError("expected AnalyticsException " + expected + ", none thrown");
    }

    /** One signed bundle through the authoring walk (draft, bytes, finalize). */
    private JsonNode authorSignedBundle(String developerToken, UUID appId, String version, String tag, byte[] bytes) {
        Map<String, Object> body = new HashMap<>();
        body.put("applicationId", appId.toString());
        body.put("bundleVersion", version);
        body.put("platformTag", tag);
        body.put("fileName", "bundle-" + version + ".zip");
        JsonNode draft = post(developerToken, "/api/v1/ota/bundles", body, 201);
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
                        developerToken,
                        "/api/v1/ota/bundles/" + draft.get("id").asText() + "/finalize",
                        Map.of("versionId", draft.get("versionId").asText()),
                        201)
                .get("bundle");
    }

    /** One OTA-channel release over the bundle, targeting the owning tenant. */
    private UUID createOtaRelease(String managerToken, UUID appId, JsonNode bundle) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", appId.toString());
        body.put("versionId", bundle.get("versionId").asText());
        body.put("ring", "ota");
        body.put(
                "artifacts",
                List.of(Map.of("platform", "IOS", "artifactId", bundle.get("id").asText())));
        UUID tenantId = jdbc.queryForObject("SELECT tenant_id FROM applications WHERE id = ?", UUID.class, appId);
        body.put("targets", List.of(Map.of("tenantId", tenantId.toString(), "testerGroupIds", List.of())));
        return UUID.fromString(
                post(managerToken, "/api/v1/releases", body, 201).get("id").asText());
    }

    /** One device check against the live release (the server-side OTA_CHECK row's writer). */
    private void check(String token, String deviceId) {
        StringBuilder uri = new StringBuilder("/api/v1/ota/check");
        uri.append("?deviceId=").append(deviceId);
        uri.append("&platform=ios&appVersion=2.0.0&channel=ota");
        client.get()
                .uri(URI.create(uri.toString()))
                .headers(headers -> headers.setBearerAuth(token))
                .exchange((request, response) -> new Recording(
                        response.getStatusCode().value(),
                        response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                        mapper.createObjectNode()))
                .expect(200);
    }

    private UUID createTenantApp(String token, UUID tenantId, String name, String bundleId) {
        Map<String, Object> body = new HashMap<>();
        body.put("tenantId", tenantId.toString());
        body.put("name", name);
        body.put("platforms", List.of("IOS"));
        body.put("bundleId", bundleId);
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
                        "Q " + role))
                .retrieve()
                .body(String.class);
        return accessToken(login(email, PASSWORD));
    }

    /** Registers a user, links them as the tenant's TENANT_ADMIN; returns their token. */
    private String loginOfTenantAdminRoleIn(UUID orgId, UUID tenantId, String slug) {
        String email = slug + "@example.com";
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        JsonNode invitation = post(
                devToken, "/api/v1/auth/invitations", Map.of("email", email, "organizationId", orgId.toString()), 201);
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
                        "Q Tenant Admin"))
                .retrieve()
                .body(String.class);
        String userToken = accessToken(login(email, PASSWORD));
        String orgAdminToken = loginOfOrgRoleIn(orgId, slug + "-orgadmin", "ORG_ADMIN");
        post(
                orgAdminToken,
                "/api/v1/tenants/" + tenantId + "/members",
                Map.of("userId", userIdOf(userToken).toString(), "role", "TENANT_ADMIN"),
                201);
        return userToken;
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

    /** The bearer's subject claim — the user id the tenant membership link needs. */
    private UUID userIdOf(String bearer) {
        String claims = bearer.split("\\.")[1];
        try {
            return UUID.fromString(mapper.readTree(Base64.getUrlDecoder().decode(claims))
                    .get("sub")
                    .asText());
        } catch (Exception e) {
            throw new IllegalStateException("could not decode the token", e);
        }
    }

    /** One authenticated GET, asserting the status and returning the body. */
    private JsonNode get(String bearer, String uri, int expectedStatus) {
        Recording recording = client.get()
                .uri(URI.create(uri))
                .headers(headers -> headers.setBearerAuth(bearer))
                .exchange((request, response) -> {
                    byte[] bytes = response.getBody().readAllBytes();
                    return new Recording(
                            response.getStatusCode().value(),
                            response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                            bytes.length == 0 ? mapper.createObjectNode() : mapper.readTree(bytes));
                });
        assertThat(recording.status).as("GET %s -> %s", uri, recording.body).isEqualTo(expectedStatus);
        return recording.body;
    }

    private JsonNode post(String bearer, String uri, Map<String, Object> body, Integer expectedStatus) {
        Recording recording = exchange(uri, bearer, body);
        if (expectedStatus != null) {
            assertThat(recording.status)
                    .as("POST %s -> %s", uri, recording.body)
                    .isEqualTo(expectedStatus);
        }
        return recording.body;
    }

    private Recording exchange(String uri, String bearer, Map<String, Object> body) {
        RestClient.RequestBodySpec spec = client.method(HttpMethod.POST).uri(URI.create(uri));
        if (bearer != null) {
            spec = spec.headers(headers -> headers.setBearerAuth(bearer));
        }
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
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

    private record Recording(int status, String contentType, JsonNode body) {

        Recording expect(int expectedStatus) {
            assertThat(status).as("HTTP status for %s", body.toString()).isEqualTo(expectedStatus);
            return this;
        }
    }
}
