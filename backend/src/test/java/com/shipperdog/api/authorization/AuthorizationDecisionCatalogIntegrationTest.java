package com.shipperdog.api.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.artifact.AndroidFixtures;
import com.shipperdog.api.artifact.IpaFixtures;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.AuditCapture;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
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
 * The S8-02 acceptance flow (issue #36): the decision API's application
 * catalog end to end over a REAL stack — PostgreSQL, Redis (the
 * decision-input cache), and the GCS emulator — with the linked-tester
 * persona as the caller. The catalog lists exactly the authorized
 * applications (AC1, cross-tenant proof), resolves every grant shape
 * into the card model (AC2: explicit, TAG, LATEST_N, per-platform
 * availability), drops dead cards (AC3: revoked grants, applications
 * with nothing installable), pages with the platform cursor, and
 * answers the honest empty set to a grantless tester, a console
 * persona, and a suspended tester (the INV-005 resting state).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef",
            "shipperdog.authorization-cache.enabled=true"
        })
@Testcontainers
@Import(AuditCapture.class)
class AuthorizationDecisionCatalogIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";
    private static final String PASSWORD = "a-perfectly-valid-password";
    private static final String CATALOG_URI = "/api/v1/authorization/decisions/applications";

    /** The compose GCS emulator image, in a container (ADR-0003). */
    @Container
    static final GenericContainer<?> GCS = new GenericContainer<>(
                    DockerImageName.parse("fsouza/fake-gcs-server:1.56.1"))
            .withCommand("-scheme", "http", "-host", "0.0.0.0", "-port", "4443")
            .withExposedPorts(4443);

    /** The decision-input cache's own store (S7-03): real Redis. */
    @Container
    @ServiceConnection
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:8-alpine")).withExposedPorts(6379);

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
    private AuditCapture audit;

    @Autowired
    private ObjectMapper mapper;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        audit.reset();
    }

    // -----------------------------------------------------------------------
    // AC1: exactly the authorized applications; the foreign side never appears
    // -----------------------------------------------------------------------

    @Test
    void catalogListsExactlyTheAuthorizedApplicationsAndNeverTheForeignSide() throws Exception {
        World own = world("s802-own", "Banking", "ANDROID", null);
        World foreign = world("s802-foreign", "Payments", "ANDROID", null);
        TesterPerson tester = testerOf(own, "s802-ac1@example.com", "Ac One");
        readyAndroidArtifact(own, own.versionId, "7.12.0", "421");
        grantLatestN(own, tester, 5);

        JsonNode catalog = get(tester.token(), CATALOG_URI, 200);

        assertThat(catalog.get("effectiveAccess").asBoolean()).isTrue();
        assertThat(catalog.get("items").size()).as("exactly one card").isEqualTo(1);
        JsonNode card = catalog.get("items").get(0);
        assertThat(card.get("application").get("applicationId").asText()).isEqualTo(own.applicationId.toString());
        assertThat(card.get("application").get("name").asText()).isEqualTo("Banking");
        assertThat(card.get("latestAuthorizedVersion").get("versionName").asText())
                .isEqualTo("7.12.0");
        assertThat(platformsOf(card)).containsExactly("ANDROID");

        // Zero leakage of the foreign side (AC1): no identifier, no name.
        String body = catalog.toString();
        for (String leak : List.of(
                foreign.orgId().toString(),
                foreign.tenantId().toString(),
                foreign.applicationId().toString(),
                foreign.versionId().toString(),
                "Payments")) {
            assertThat(body).as("the catalog must not leak %s", leak).doesNotContain(leak);
        }

        // The catalog's LIST decisions are audited on the decision-api surface.
        assertThat(audit.eventsOfType(AuditEventType.AUTHORIZATION_DECISION).stream()
                        .anyMatch(event -> "decision-api".equals(event.detail().get("surface"))
                                && "LIST_APPLICATIONS".equals(event.detail().get("action"))
                                && event.success()))
                .as("the catalog resolution is audited with surface=decision-api")
                .isTrue();
    }

    // -----------------------------------------------------------------------
    // AC2: every grant shape resolves visibly into the card model
    // -----------------------------------------------------------------------

    @Test
    void explicitGrantPinsTheCardVersionAndBothPlatformsResolve() throws Exception {
        World world = world("s802-explicit", "Wallet", "BOTH", IpaFixtures.BUNDLE_ID);
        TesterPerson tester = testerOf(world, "s802-ac2a@example.com", "Ac Two A");
        readyIosArtifact(world, world.versionId);
        readyAndroidArtifact(world, world.versionId, IpaFixtures.VERSION_NAME, "421");
        grantExplicitVersion(world, tester);

        JsonNode card = get(tester.token(), CATALOG_URI, 200).get("items").get(0);

        assertThat(card.get("latestAuthorizedVersion").get("versionName").asText())
                .isEqualTo("7.12.0");
        assertThat(platformsOf(card)).containsExactly("IOS", "ANDROID");
        assertThat(card.get("platforms").get(0).get("versionId").asText()).isEqualTo(world.versionId.toString());
        assertThat(card.get("platforms").get(1).get("versionId").asText()).isEqualTo(world.versionId.toString());
    }

    @Test
    void latestNWindowSlidesToTheNewestVersionWithoutAGrantChange() throws Exception {
        World world = world("s802-latestn", "Banking", "ANDROID", null);
        TesterPerson tester = testerOf(world, "s802-ac2b@example.com", "Ac Two B");
        UUID v711 = createVersion(world, "7.11.0");
        readyAndroidArtifact(world, v711, "7.11.0", "419");
        readyAndroidArtifact(world, world.versionId, "7.12.0", "421");
        grantLatestN(world, tester, 1);

        JsonNode first = get(tester.token(), CATALOG_URI, 200).get("items").get(0);
        assertThat(first.get("latestAuthorizedVersion").get("versionName").asText())
                .isEqualTo("7.12.0");

        UUID v713 = createVersion(world, "7.13.0");
        readyAndroidArtifact(world, v713, "7.13.0", "423");

        JsonNode slid = get(tester.token(), CATALOG_URI, 200).get("items").get(0);
        assertThat(slid.get("latestAuthorizedVersion").get("versionName").asText())
                .as("the LATEST_N window slides with no grant change")
                .isEqualTo("7.13.0");
        assertThat(platformOf(slid, "ANDROID").get("versionId").asText()).isEqualTo(v713.toString());
    }

    @Test
    void tagGrantPinsTheCardToTheCurrentlyTaggedVersion() throws Exception {
        World world = world("s802-tag", "Banking", "ANDROID", null);
        TesterPerson tester = testerOf(world, "s802-ac2c@example.com", "Ac Two C");
        UUID older = createVersion(world, "7.11.0");
        readyAndroidArtifact(world, older, "7.11.0", "419");
        readyAndroidArtifact(world, world.versionId, "7.12.0", "421");
        setTags(world, older, List.of("BETA"));
        grantTag(world, tester, "BETA");

        JsonNode card = get(tester.token(), CATALOG_URI, 200).get("items").get(0);
        assertThat(card.get("latestAuthorizedVersion").get("versionId").asText())
                .as("a TAG grant authorizes exactly the versions carrying the tag")
                .isEqualTo(older.toString());
        assertThat(platformOf(card, "ANDROID").get("versionId").asText()).isEqualTo(older.toString());

        // Evaluation-time tag resolution: moving the tag to the newest
        // version flips the card with no grant change (FR-011).
        setTags(world, world.versionId, List.of("BETA"));
        setTags(world, older, List.of());
        JsonNode moved = get(tester.token(), CATALOG_URI, 200).get("items").get(0);
        assertThat(moved.get("latestAuthorizedVersion").get("versionId").asText())
                .as("the card follows the moved tag")
                .isEqualTo(world.versionId.toString());
    }

    @Test
    void anExpiredGrantDropsTheCardAtItsExpiry() throws Exception {
        World world = world("s802-ttl", "Banking", "ANDROID", null);
        TesterPerson tester = testerOf(world, "s802-ac3b@example.com", "Ac Three B");
        readyAndroidArtifact(world, world.versionId, "7.12.0", "421");
        // Microsecond-truncated on purpose: PostgreSQL timestamps carry
        // microsecond precision, so an exact round trip stays deterministic
        // across CI and dev clocks (the S7-04 suite's own rule).
        java.time.Instant expiresAt =
                java.time.Instant.now().plusSeconds(3).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("testerId", tester.testerId().toString());
        body.put("applicationId", world.applicationId.toString());
        body.put("accessType", "EXPLICIT_VERSION");
        body.put("versionId", world.versionId.toString());
        body.put("expiresAt", expiresAt.toString());
        body.put("reason", "a short-lived beta");
        post(world.platformAdmin(), "/api/v1/tenants/" + world.tenantId + "/authorization-grants", body, 201);

        assertThat(applicationNamesOf(get(tester.token(), CATALOG_URI, 200)))
                .as("the live TTL grant yields the card")
                .containsExactly("Banking");

        // The flip is evaluation-time: poll the catalog, bounded, until the
        // expired grant leaves nothing installable (AC3's expired half).
        boolean dropped = false;
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (get(tester.token(), CATALOG_URI, 200).get("items").size() == 0) {
                dropped = true;
                break;
            }
            Thread.sleep(250);
        }
        assertThat(dropped).as("the expired grant drops the card at its expiry").isTrue();
    }

    // -----------------------------------------------------------------------
    // AC3: no dead cards — revoked grants and nothing-installable stay off
    // -----------------------------------------------------------------------

    @Test
    void revokedGrantsAndArtifactlessApplicationsYieldNoCard() throws Exception {
        World world = world("s802-dead", "Banking", "ANDROID", null);
        TesterPerson tester = testerOf(world, "s802-ac3@example.com", "Ac Three");
        readyAndroidArtifact(world, world.versionId, "7.12.0", "421");
        UUID grantId = grantExplicitVersion(world, tester);
        // Authorized but nothing installable: a grant over a version with
        // no settled artifact is a dead card and must not appear.
        Application bare = applicationIn(world, "Cardless");
        UUID bareVersionId = createVersion(world, bare.applicationId(), "7.12.0");
        grantExplicitVersionOf(world, tester, bare.applicationId(), bareVersionId);

        JsonNode withBoth = get(tester.token(), CATALOG_URI, 200);
        assertThat(applicationNamesOf(withBoth)).containsExactly("Banking");

        delete(
                world.platformAdmin(),
                "/api/v1/tenants/" + world.tenantId + "/authorization-grants/" + grantId + "?reason=left the beta",
                204);

        JsonNode afterRevoke = get(tester.token(), CATALOG_URI, 200);
        assertThat(afterRevoke.get("items").size())
                .as("a revoked grant never authorizes (INV-007), and the artifactless side never had a card")
                .isZero();
    }

    // -----------------------------------------------------------------------
    // Paging: the platform cursor envelope over the card set
    // -----------------------------------------------------------------------

    @Test
    void catalogPagesWithThePlatformCursor() throws Exception {
        World world = world("s802-page", "Alpha", "ANDROID", null);
        TesterPerson tester = testerOf(world, "s802-page@example.com", "Ac Page");
        Application beta = applicationIn(world, "Beta");
        readyAndroidArtifact(world, world.versionId, "7.12.0", "421");
        UUID betaVersionId = createVersion(world, beta.applicationId(), "7.12.0");
        readyAndroidArtifactForPackage(world, betaVersionId, beta.packageName(), "7.12.0", "421");
        grantLatestN(world, tester, 5);
        grantExplicitVersionOf(world, tester, beta.applicationId(), betaVersionId);

        JsonNode pageOne = get(tester.token(), CATALOG_URI + "?limit=1", 200);
        assertThat(applicationNamesOf(pageOne)).containsExactly("Alpha");
        String cursor = pageOne.get("next_cursor").asString();
        assertThat(cursor).isNotBlank();

        JsonNode pageTwo = get(tester.token(), CATALOG_URI + "?limit=1&cursor=" + cursor, 200);
        assertThat(applicationNamesOf(pageTwo)).containsExactly("Beta");
        assertThat(pageTwo.has("next_cursor"))
                .as("the last page carries no cursor")
                .isFalse();
    }

    // -----------------------------------------------------------------------
    // The honest empty catalog (INV-005 resting state)
    // -----------------------------------------------------------------------

    @Test
    void grantlessTesterConsoleCallerAndSuspendedTesterSeeTheHonestEmptyCatalog() throws Exception {
        World world = world("s802-empty", "Banking", "ANDROID", null);
        TesterPerson grantless = testerOf(world, "s802-ac5@example.com", "Ac Five");
        readyAndroidArtifact(world, world.versionId, "7.12.0", "421");

        JsonNode grantlessCatalog = get(grantless.token(), CATALOG_URI, 200);
        assertThat(grantlessCatalog.get("effectiveAccess").asBoolean()).isTrue();
        assertThat(grantlessCatalog.get("items").size())
                .as("a linked tester with no grants sees nothing, not an error")
                .isZero();

        JsonNode consoleCatalog = get(world.platformAdmin(), CATALOG_URI, 200);
        assertThat(consoleCatalog.get("effectiveAccess").asBoolean()).isFalse();
        assertThat(consoleCatalog.get("items").size())
                .as("a console persona has no tester reach")
                .isZero();

        put(
                world.platformAdmin(),
                "/api/v1/tenants/" + world.tenantId + "/testers/" + grantless.testerId() + "/status",
                Map.of("status", "SUSPENDED"),
                200);
        JsonNode suspendedCatalog = get(grantless.token(), CATALOG_URI, 200);
        assertThat(suspendedCatalog.get("effectiveAccess").asBoolean())
                .as("the two-switches rule denies the suspended membership's tenants")
                .isFalse();
        assertThat(suspendedCatalog.get("items").size()).isZero();
    }

    // -----------------------------------------------------------------------
    // World and helpers (the S7-03 decision suite's own shape)
    // -----------------------------------------------------------------------

    private World world(String suffix, String applicationName, String platforms, String bundleId) {
        String platformAdmin = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        UUID orgId = UUID.fromString(
                post(platformAdmin, "/api/v1/organizations", Map.of("name", "S802 " + suffix + " Corp"), 201)
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
        application.put("name", applicationName);
        application.put("platforms", "BOTH".equals(platforms) ? List.of("IOS", "ANDROID") : List.of(platforms));
        if ("BOTH".equals(platforms) || "IOS".equals(platforms)) {
            application.put("bundleId", bundleId);
        }
        if ("BOTH".equals(platforms) || "ANDROID".equals(platforms)) {
            application.put("packageName", AndroidFixtures.PACKAGE);
        }
        UUID applicationId = UUID.fromString(post(platformAdmin, "/api/v1/applications", application, 201)
                .get("id")
                .asText());
        UUID versionId = UUID.fromString(post(
                        platformAdmin,
                        "/api/v1/applications/" + applicationId + "/versions",
                        Map.of("versionName", "7.12.0"),
                        201)
                .get("id")
                .asText());
        return new World(platformAdmin, orgId, tenantId, applicationId, versionId);
    }

    private record World(String platformAdmin, UUID orgId, UUID tenantId, UUID applicationId, UUID versionId) {}

    /**
     * A second application inside the world's tenant (same organization —
     * the linked-tester pool is org-scoped), registering its own package
     * name (the org-wide uniqueness rule).
     */
    private Application applicationIn(World world, String name) {
        String packageName = AndroidFixtures.PACKAGE + "." + name.toLowerCase(Locale.ROOT);
        return new Application(
                UUID.fromString(post(
                                world.platformAdmin(),
                                "/api/v1/applications",
                                Map.of(
                                        "tenantId",
                                        world.tenantId.toString(),
                                        "name",
                                        name,
                                        "platforms",
                                        List.of("ANDROID"),
                                        "packageName",
                                        packageName),
                                201)
                        .get("id")
                        .asText()),
                packageName);
    }

    private record Application(UUID applicationId, String packageName) {}

    /** An ACTIVE tester person record linked to the world's tenant, with their login token. */
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

    private record TesterPerson(UUID testerId, String token) {}

    private UUID createVersion(World world, String versionName) {
        return createVersion(world, world.applicationId, versionName);
    }

    private UUID createVersion(World world, UUID applicationId, String versionName) {
        return UUID.fromString(post(
                        world.platformAdmin(),
                        "/api/v1/applications/" + applicationId + "/versions",
                        Map.of("versionName", versionName),
                        201)
                .get("id")
                .asText());
    }

    private void setTags(World world, UUID versionId, List<String> tags) {
        put(
                world.platformAdmin(),
                "/api/v1/applications/" + world.applicationId + "/versions/" + versionId + "/tags",
                Map.of("tags", tags),
                200);
    }

    private UUID grantExplicitVersion(World world, TesterPerson tester) {
        return grantExplicitVersionOf(world, tester, world.applicationId, world.versionId);
    }

    private UUID grantExplicitVersionOf(World world, TesterPerson tester, UUID applicationId, UUID versionId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("testerId", tester.testerId().toString());
        body.put("applicationId", applicationId.toString());
        body.put("accessType", "EXPLICIT_VERSION");
        body.put("versionId", versionId.toString());
        body.put("reason", "one specific version");
        return UUID.fromString(
                post(world.platformAdmin(), "/api/v1/tenants/" + world.tenantId + "/authorization-grants", body, 201)
                        .get("grant")
                        .get("id")
                        .asText());
    }

    private void grantLatestN(World world, TesterPerson tester, int n) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("testerId", tester.testerId().toString());
        body.put("applicationId", world.applicationId.toString());
        body.put("accessType", "LATEST_N");
        body.put("latestN", n);
        body.put("reason", "the newest " + n);
        post(world.platformAdmin(), "/api/v1/tenants/" + world.tenantId + "/authorization-grants", body, 201);
    }

    private void grantTag(World world, TesterPerson tester, String tag) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("testerId", tester.testerId().toString());
        body.put("applicationId", world.applicationId.toString());
        body.put("accessType", "TAG");
        body.put("tag", tag);
        body.put("reason", "the beta channel");
        post(world.platformAdmin(), "/api/v1/tenants/" + world.tenantId + "/authorization-grants", body, 201);
    }

    private static List<String> platformsOf(JsonNode card) {
        List<String> platforms = new ArrayList<>();
        for (JsonNode platform : card.get("platforms")) {
            platforms.add(platform.get("platform").asString());
        }
        return platforms;
    }

    private static JsonNode platformOf(JsonNode card, String platform) {
        for (JsonNode candidate : card.get("platforms")) {
            if (platform.equals(candidate.get("platform").asString())) {
                return candidate;
            }
        }
        throw new AssertionError("platform " + platform + " not resolved on the card");
    }

    private static List<String> applicationNamesOf(JsonNode catalog) {
        List<String> names = new ArrayList<>();
        for (JsonNode item : catalog.get("items")) {
            names.add(item.get("application").get("name").asString());
        }
        return names;
    }

    private UUID readyAndroidArtifact(World world, UUID versionId, String versionName, String versionCode)
            throws Exception {
        org.springframework.util.MultiValueMap<String, Object> parts =
                new org.springframework.util.LinkedMultiValueMap<>();
        parts.add("version_id", versionId.toString());
        parts.add("platform", "ANDROID");
        parts.add(
                "file",
                new org.springframework.core.io.ByteArrayResource(
                        com.shipperdog.api.artifact.AndroidFixtures.signedApk(versionName, versionCode)) {
                    @Override
                    public String getFilename() {
                        return "app.apk";
                    }
                });
        return settleReady(
                world, exchange("/api/v1/artifacts", world.platformAdmin(), HttpMethod.POST, parts), versionId);
    }

    /** The catalog's second-application leg: the manifest package matches the app's own registration. */
    private UUID readyAndroidArtifactForPackage(
            World world, UUID versionId, String packageName, String versionName, String versionCode) throws Exception {
        org.springframework.util.MultiValueMap<String, Object> parts =
                new org.springframework.util.LinkedMultiValueMap<>();
        parts.add("version_id", versionId.toString());
        parts.add("platform", "ANDROID");
        parts.add(
                "file",
                new org.springframework.core.io.ByteArrayResource(
                        AndroidFixtures.signedApkForPackage(packageName, versionName, versionCode)) {
                    @Override
                    public String getFilename() {
                        return "app.apk";
                    }
                });
        return settleReady(
                world, exchange("/api/v1/artifacts", world.platformAdmin(), HttpMethod.POST, parts), versionId);
    }

    private UUID readyIosArtifact(World world, UUID versionId) throws Exception {
        org.springframework.util.MultiValueMap<String, Object> parts =
                new org.springframework.util.LinkedMultiValueMap<>();
        parts.add("version_id", versionId.toString());
        parts.add("platform", "IOS");
        parts.add(
                "file",
                new org.springframework.core.io.ByteArrayResource(
                        com.shipperdog.api.artifact.IpaFixtures.releaseIpa()) {
                    @Override
                    public String getFilename() {
                        return "app.ipa";
                    }
                });
        return settleReady(
                world, exchange("/api/v1/artifacts", world.platformAdmin(), HttpMethod.POST, parts), versionId);
    }

    /** Uploads settle asynchronously; the catalog's availability read demands READY (PRD 18). */
    private UUID settleReady(World world, Recording uploaded, UUID versionId) {
        assertThat(uploaded.status).as("artifact upload: %s", uploaded.body).isEqualTo(201);
        UUID artifactId = UUID.fromString(uploaded.body.get("id").asText());
        long deadline = System.currentTimeMillis() + 20_000;
        String status = "";
        while (System.currentTimeMillis() < deadline) {
            status = get(world.platformAdmin(), "/api/v1/artifacts/" + artifactId + "?version_id=" + versionId, 200)
                    .get("status")
                    .asString();
            if ("READY".equals(status)) {
                return artifactId;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted awaiting extraction", e);
            }
        }
        throw new AssertionError("the fixture must settle READY but stayed " + status);
    }

    private String login(String email, String password) {
        return client.post()
                .uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", password))
                .retrieve()
                .body(String.class);
    }

    private String accessToken(String tokensJson) {
        try {
            return mapper.readTree(tokensJson).get("accessToken").asString();
        } catch (Exception e) {
            throw new IllegalStateException("cannot parse login answer", e);
        }
    }

    private JsonNode post(String token, String uri, Map<String, Object> body, int expectedStatus) {
        Recording answer = exchange(uri, token, HttpMethod.POST, body);
        assertThat(answer.status).as("POST %s: %s", uri, answer.body).isEqualTo(expectedStatus);
        return answer.body;
    }

    private JsonNode put(String token, String uri, Map<String, Object> body, int expectedStatus) {
        Recording answer = exchange(uri, token, HttpMethod.PUT, body);
        assertThat(answer.status).as("PUT %s: %s", uri, answer.body).isEqualTo(expectedStatus);
        return answer.body;
    }

    private void delete(String token, String uri, int expectedStatus) {
        Recording answer = exchange(uri, token, HttpMethod.DELETE, null);
        assertThat(answer.status).as("DELETE %s: %s", uri, answer.body).isEqualTo(expectedStatus);
    }

    private JsonNode get(String token, String uri, int expectedStatus) {
        Recording answer = exchange(uri, token, HttpMethod.GET, null);
        assertThat(answer.status).as("GET %s: %s", uri, answer.body).isEqualTo(expectedStatus);
        return answer.body;
    }

    private Recording exchange(String uri, String token, HttpMethod method, Object body) {
        RestClient.RequestBodySpec spec = client.method(method).uri(uri);
        if (token != null) {
            spec.headers(headers -> headers.setBearerAuth(token));
        }
        if (body != null) {
            spec.contentType(
                    body instanceof org.springframework.util.MultiValueMap<?, ?>
                            ? MediaType.MULTIPART_FORM_DATA
                            : MediaType.APPLICATION_JSON);
            spec.body(body);
        }
        return spec.exchange((request, response) -> {
            byte[] raw = response.getBody().readAllBytes();
            return new Recording(
                    response.getStatusCode().value(),
                    raw.length == 0 ? mapper.createObjectNode() : mapper.readTree(raw));
        });
    }

    private record Recording(int status, JsonNode body) {}
}
