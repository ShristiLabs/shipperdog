package com.shipperdog.api.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.artifact.AndroidFixtures;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.AuditCapture;
import io.micrometer.core.instrument.MeterRegistry;
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
import org.springframework.context.annotation.Import;
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
 * The S7-03 acceptance flow (issue #32): the first-party decision API
 * end to end over a REAL stack — PostgreSQL, Redis (the cache itself),
 * and the GCS emulator — with the tester persona as the caller. The
 * download decision's issuance inputs on ALLOW (AC4), the concealed
 * denial shapes (never a layer name, never an existence leak), the
 * eligibility listing's shape, the write-through invalidation
 * discipline (AC2: a grant deletion, a membership suspension, and a
 * person-status flip all take effect on the NEXT call through a warm
 * cache), the cache's observable hit leg, the degraded-mode contract
 * (AC3, the companion class below), and the decision-api audit
 * surface tag.
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
class AuthorizationDecisionApiIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";
    private static final String PASSWORD = "a-perfectly-valid-password";

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
    // AC4: the download decision returns the issuance inputs on ALLOW
    // -----------------------------------------------------------------------

    @Test
    void anAllowedDownloadCarriesTheSignedUrlIssuanceInputs() throws Exception {
        World world = world("s703-allow");
        UUID artifactId = readyAndroidArtifact(world);
        TesterPerson john = testerOf(world, "s703-allow@example.com", "Allow Viewer");
        grantExplicitVersion(world, john);

        JsonNode decision = get(john.token(), downloadUri(world, world.versionId, "ANDROID"), 200);

        assertThat(UUID.fromString(decision.get("applicationId").asText())).isEqualTo(world.applicationId);
        assertThat(UUID.fromString(decision.get("versionId").asText())).isEqualTo(world.versionId);
        assertThat(UUID.fromString(decision.get("artifactId").asText())).isEqualTo(artifactId);
        assertThat(decision.get("platform").asString()).isEqualTo("ANDROID");
        assertThat(decision.get("storageKey").asString())
                .as("the PRD 24 storage key S8-04 signs")
                .endsWith("/" + artifactId + "/artifact")
                .contains(world.orgId.toString())
                .contains(world.tenantId.toString());

        // The decision is audited on its own surface, distinguishable
        // from the console's guarded reads and the admin inspection.
        assertThat(audit.eventsOfType(AuditEventType.AUTHORIZATION_DECISION).stream()
                        .anyMatch(event -> "decision-api".equals(event.detail().get("surface"))
                                && "DOWNLOAD".equals(event.detail().get("action"))
                                && event.success()))
                .as("the portal download decision is audited with surface=decision-api")
                .isTrue();
    }

    // -----------------------------------------------------------------------
    // Concealment: denials never leak the layer or existence (PRD 12)
    // -----------------------------------------------------------------------

    @Test
    void denialsRenderTheConcealedMissShapesByteForByte() throws Exception {
        World world = world("s703-conceal");
        readyAndroidArtifact(world);
        TesterPerson john = testerOf(world, "s703-conceal@example.com", "Conceal Viewer");

        // No grant: the version-grain miss, byte-identical to the
        // genuine unknown-version miss.
        Map<String, Object> denied = problemOf(getRaw(john.token(), downloadUri(world, world.versionId, "ANDROID")));
        Map<String, Object> genuine = problemOf(getRaw(john.token(), downloadUri(world, UUID.randomUUID(), "ANDROID")));
        assertThat(denied.get("status")).isEqualTo(404);
        assertThat(denied.get("code")).isEqualTo("VERSION_NOT_FOUND");
        assertThat(denied).containsAllEntriesOf(genuine);

        // With the grant but the WRONG platform (no IOS artifact): the
        // artifact-grain miss, exactly the artifact read's own shape.
        grantExplicitVersion(world, john);
        Map<String, Object> platformMiss = problemOf(getRaw(john.token(), downloadUri(world, world.versionId, "IOS")));
        assertThat(platformMiss.get("code")).isEqualTo("ARTIFACT_NOT_FOUND");

        // A REVOKED version blocks downloads (INV-007) as the version
        // miss, indistinguishable from an unknown version.
        Map<String, Object> revokedBody = new HashMap<>();
        revokedBody.put("reason", "concealment fixture");
        post(world.platformAdmin(), revokeUri(world, world.versionId), revokedBody, 200);
        assertThat(problemOf(getRaw(john.token(), downloadUri(world, world.versionId, "ANDROID")))
                        .get("code"))
                .isEqualTo("VERSION_NOT_FOUND");

        // An unknown tenant addresses nothing the caller can reach:
        // the engine's organization layer denies (tenant missing) and
        // the route conceals it as the same version miss — uniform,
        // never leaking WHICH grain failed.
        Map<String, Object> foreignTenant =
                problemOf(getRaw(john.token(), downloadUriOf(world.tenantId, UUID.randomUUID(), "ANDROID")));
        assertThat(foreignTenant.get("code")).isEqualTo("VERSION_NOT_FOUND");
        Map<String, Object> unknownTenant =
                problemOf(getRaw(john.token(), downloadUriOf(UUID.randomUUID(), world.versionId, "ANDROID")));
        assertThat(unknownTenant.get("code")).isEqualTo("VERSION_NOT_FOUND");

        // An invalid platform vocabulary is a 400, never a denial shape.
        assertThat(problemOf(getRaw(john.token(), downloadUri(world, world.versionId, "WATCHOS")))
                        .get("status"))
                .isEqualTo(400);
    }

    // -----------------------------------------------------------------------
    // Eligibility: the caller's authorized version set (FR-013)
    // -----------------------------------------------------------------------

    @Test
    void eligibilityListsTheAuthorizedVersionsNewestFirstWithTtl() throws Exception {
        World world = world("s703-eligibility");
        readyAndroidArtifact(world);
        UUID older = createVersion(world, "7.11.0");
        TesterPerson john = testerOf(world, "s703-eligibility@example.com", "Eligibility Viewer");

        // No grants: a legitimately EMPTY set (the resting state, not a
        // miss — the tenant and application are the caller's own).
        JsonNode empty = get(john.token(), eligibilityUri(world), 200);
        assertThat(empty.get("effectiveAccess").asBoolean()).isTrue();
        assertThat(empty.get("entries").size()).isZero();

        // The latest-2 window: newest first, TTL null (never expires).
        grantLatestN(world, john, 2);
        JsonNode eligible = get(john.token(), eligibilityUri(world), 200);
        List<String> names = new java.util.ArrayList<>();
        for (JsonNode entry : eligible.get("entries")) {
            names.add(entry.get("versionName").asString());
        }
        assertThat(names).containsExactly(AndroidFixtures.VERSION_NAME, "7.11.0");
        assertThat(eligible.get("entries").get(0).get("expiresAt").isNull()).isTrue();

        // An invisible application is the concealed miss; a console
        // caller without a tester record answers the empty set.
        UUID unknownApp = UUID.randomUUID();
        assertThat(problemOf(getRaw(john.token(), eligibilityUriOf(world.tenantId, unknownApp)))
                        .get("code"))
                .isEqualTo("APPLICATION_NOT_FOUND");
        String orgAdmin =
                loginOfOrgRoleIn(world.platformAdmin(), world.orgId, "s703-orgadmin@example.com", "ORG_ADMIN");
        JsonNode console = get(orgAdmin, eligibilityUri(world), 200);
        assertThat(console.get("effectiveAccess").asBoolean()).isFalse();
        assertThat(console.get("entries").size()).isZero();

        // A REAL tenant the caller holds no relationship to conceals
        // exactly like an unknown one (the engine's tenant layer, not
        // a distinguishable permission miss).
        UUID sisterTenant = UUID.fromString(post(
                        world.platformAdmin(),
                        "/api/v1/tenants",
                        Map.of(
                                "organizationId", world.orgId.toString(),
                                "name", "Sister s703-eligibility",
                                "externalId", "ext-s703-sister-elig"),
                        201)
                .get("id")
                .asText());
        Map<String, Object> unrelated =
                problemOf(getRaw(john.token(), eligibilityUriOf(sisterTenant, world.applicationId)));
        Map<String, Object> unknownTenant =
                problemOf(getRaw(john.token(), eligibilityUriOf(UUID.randomUUID(), world.applicationId)));
        assertThat(unrelated.get("code")).isEqualTo("TENANT_NOT_FOUND");
        assertThat(unrelated).containsAllEntriesOf(unknownTenant);
    }

    // -----------------------------------------------------------------------
    // AC2: write-through invalidation through a warm cache
    // -----------------------------------------------------------------------

    @Test
    void aSharedApplicationsVisibilityInvalidatesThroughTheWarmCache() throws Exception {
        // The VISIBILITY cache family end to end (the S7-03 scope's
        // tenant-app mapping leg): a grant through the S3-03-mapped
        // tenant covers the shared application's version; de-authorizing
        // the mapping invalidates the cached flag and denies on the
        // very next call — the shared-application contributor's live
        // reach check fed by a warm cache.
        World world = world("s703-visibility");
        TesterPerson john = testerOf(world, "s703-visibility@example.com", "Visibility Viewer");
        UUID sharedApp = UUID.fromString(post(
                        world.platformAdmin(),
                        "/api/v1/applications",
                        Map.of(
                                "ownershipScope", "ORGANIZATION",
                                "organizationId", world.orgId.toString(),
                                "name", "Shared Banking",
                                "platforms", List.of("ANDROID"),
                                "packageName", "com.acme.shared.s703"),
                        201)
                .get("id")
                .asText());
        post(world.platformAdmin(), "/api/v1/applications/" + sharedApp + "/tenants/" + world.tenantId, Map.of(), 201);
        UUID sharedVersion = UUID.fromString(post(
                        world.platformAdmin(),
                        "/api/v1/applications/" + sharedApp + "/versions",
                        Map.of("versionName", "1.0.0"),
                        201)
                .get("id")
                .asText());
        String download = "/api/v1/authorization/decisions/download?tenantId=" + world.tenantId + "&versionId="
                + sharedVersion + "&platform=ANDROID";

        // No grant: the concealed version miss (entitlement missing).
        assertThat(problemOf(getRaw(john.token(), download)).get("code")).isEqualTo("VERSION_NOT_FOUND");

        // The app-wide grant through the mapped tenant: the chain
        // passes — the artifact-grain miss PROVES it (no distributable
        // artifact exists on the shared version, so an allow stops at
        // issuance, one grain past the entitlement). This call warms
        // the grant, membership, and visibility entries.
        Map<String, Object> grant = new LinkedHashMap<>();
        grant.put("testerId", john.testerId().toString());
        grant.put("applicationId", sharedApp.toString());
        grant.put("accessType", "APPLICATION");
        grant.put("reason", "the whole shared app");
        post(world.platformAdmin(), "/api/v1/tenants/" + world.tenantId + "/authorization-grants", grant, 201);
        assertThat(problemOf(getRaw(john.token(), download)).get("code")).isEqualTo("ARTIFACT_NOT_FOUND");
        JsonNode eligible = get(john.token(), eligibilityUriOf(world.tenantId, sharedApp), 200);
        assertThat(versionNamesOf(eligible)).containsExactly("1.0.0");

        // De-authorize the mapping (S3-03): the cached visibility flag
        // drops write-through, and the next calls deny at the
        // application dimension — the download as the version miss,
        // the eligibility as the application miss.
        exchange(
                "/api/v1/applications/" + sharedApp + "/tenants/" + world.tenantId,
                world.platformAdmin(),
                HttpMethod.DELETE,
                null);
        assertThat(problemOf(getRaw(john.token(), download)).get("code")).isEqualTo("VERSION_NOT_FOUND");
        assertThat(problemOf(getRaw(john.token(), eligibilityUriOf(world.tenantId, sharedApp)))
                        .get("code"))
                .isEqualTo("APPLICATION_NOT_FOUND");
    }

    @Test
    void liveTagAndWindowProbesStayFreshOverTheWarmGrantSet() throws Exception {
        // The deliberately-LIVE probes (the story's technical note:
        // decisions stay computed): the TAG membership and the LATEST_N
        // working-set window resolve on every evaluation, so version
        // lifecycle changes — a newly tagged version joining, a newer
        // build entering the window — are visible on the very next
        // call with NO invalidation, while the grant SET stays cached.
        World world = world("s703-live");
        readyAndroidArtifact(world);
        UUID older = createVersion(world, "7.11.0");
        setTags(world, world.versionId, List.of("BETA"));
        TesterPerson john = testerOf(world, "s703-live@example.com", "Live Viewer");

        Map<String, Object> tagGrant = new LinkedHashMap<>();
        tagGrant.put("testerId", john.testerId().toString());
        tagGrant.put("applicationId", world.applicationId.toString());
        tagGrant.put("accessType", "TAG");
        tagGrant.put("tag", "BETA");
        tagGrant.put("reason", "the beta channel");
        post(world.platformAdmin(), "/api/v1/tenants/" + world.tenantId + "/authorization-grants", tagGrant, 201);

        // Warm: the tagged newest version only.
        assertThat(versionNamesOf(get(john.token(), eligibilityUri(world), 200)))
                .containsExactly(AndroidFixtures.VERSION_NAME);

        // A newly tagged version joins with no grant change and no
        // invalidation — the tag probe is live over the cached set.
        setTags(world, older, List.of("BETA"));
        assertThat(versionNamesOf(get(john.token(), eligibilityUri(world), 200)))
                .containsExactly(AndroidFixtures.VERSION_NAME, "7.11.0");

        // A LATEST_1 grant composes with the tag grant; a brand-new
        // version enters the window live — and the TAG-covered entries
        // stay listed (the union over cached inputs).
        Map<String, Object> windowGrant = new LinkedHashMap<>();
        windowGrant.put("testerId", john.testerId().toString());
        windowGrant.put("applicationId", world.applicationId.toString());
        windowGrant.put("accessType", "LATEST_N");
        windowGrant.put("latestN", 1);
        windowGrant.put("reason", "the newest build");
        post(world.platformAdmin(), "/api/v1/tenants/" + world.tenantId + "/authorization-grants", windowGrant, 201);
        createVersion(world, "7.13.0");
        assertThat(versionNamesOf(get(john.token(), eligibilityUri(world), 200)))
                .as("the window moved to the new newest live; the tag entries remain")
                .containsExactly("7.13.0", AndroidFixtures.VERSION_NAME, "7.11.0");
    }

    @Test
    void aScopeTransitionInvalidatesTheVisibilityFamilyThroughTheWarmCache() throws Exception {
        // The reviewer's medium advisory, folded (the invalidation
        // port's own contract): converting a TENANT-owned application
        // to ORGANIZATION ownership strips the old owner's
        // by-ownership visibility — the cached flag must drop in the
        // transition's transaction, so the very next eligibility read
        // conceals the application instead of serving a stale listing
        // until the TTL. Without the invalidation this test serves the
        // pre-transition entries (the failure mode the review named).
        World world = world("s703-scope");
        readyAndroidArtifact(world);
        TesterPerson john = testerOf(world, "s703-scope@example.com", "Scope Viewer");
        grantExplicitVersion(world, john);
        assertThat(versionNamesOf(get(john.token(), eligibilityUri(world), 200)))
                .containsExactly(AndroidFixtures.VERSION_NAME);

        // The transition (S3-02): TENANT -> ORGANIZATION, no new owner
        // (the registry's partial-update verb is PATCH).
        Map<String, Object> transition = new HashMap<>();
        transition.put("ownershipScope", "ORGANIZATION");
        Recording patched = exchange(
                "/api/v1/applications/" + world.applicationId, world.platformAdmin(), HttpMethod.PATCH, transition);
        assertThat(patched.status).as("scope transition: %s", patched.body).isEqualTo(200);

        assertThat(problemOf(getRaw(john.token(), eligibilityUri(world))).get("code"))
                .as("the old owner's visibility dropped with the transition, not at the TTL")
                .isEqualTo("APPLICATION_NOT_FOUND");
        assertThat(problemOf(getRaw(john.token(), downloadUri(world, world.versionId, "ANDROID")))
                        .get("code"))
                .isEqualTo("VERSION_NOT_FOUND");
    }

    @Test
    void aWarmedCacheServesAndEveryMutationInvalidatesBeforeTheNextCall() throws Exception {
        World world = world("s703-invalidate");
        readyAndroidArtifact(world);
        TesterPerson john = testerOf(world, "s703-invalidate@example.com", "Invalidate Viewer");
        UUID grantId = grantExplicitVersion(world, john);

        // Warm the cache with an ALLOWED download, then assert the hit
        // leg exists: the second decision's inputs came from Redis.
        double hitsBefore = cacheHits("grants");
        get(john.token(), downloadUri(world, world.versionId, "ANDROID"), 200);
        get(john.token(), downloadUri(world, world.versionId, "ANDROID"), 200);
        assertThat(cacheHits("grants")).isGreaterThan(hitsBefore);

        // Grant deletion (write-through): the next call denies — the
        // INV-005 resting state restored through a warm cache.
        Map<String, Object> reason = new HashMap<>();
        reason.put("reason", "revoked for the invalidation fixture");
        exchange(
                "/api/v1/tenants/" + world.tenantId + "/authorization-grants/" + grantId + "?reason=revoked",
                world.platformAdmin(),
                HttpMethod.DELETE,
                null);
        assertThat(problemOf(getRaw(john.token(), downloadUri(world, world.versionId, "ANDROID")))
                        .get("code"))
                .isEqualTo("VERSION_NOT_FOUND");

        // Re-grant, re-warm, then suspend the MEMBERSHIP: the next
        // decision denies at the tenant dimension (concealed) and the
        // eligibility flag flips false — both through the warm cache.
        grantExplicitVersion(world, john);
        get(john.token(), downloadUri(world, world.versionId, "ANDROID"), 200);
        Map<String, Object> suspended = new HashMap<>();
        suspended.put("status", "SUSPENDED");
        put(
                world.platformAdmin(),
                "/api/v1/tenants/" + world.tenantId + "/testers/" + john.testerId() + "/status",
                suspended,
                200);
        assertThat(problemOf(getRaw(john.token(), downloadUri(world, world.versionId, "ANDROID")))
                        .get("code"))
                .isEqualTo("VERSION_NOT_FOUND");
        // The self-scoped eligibility conceals a suspended tester
        // entirely (the LIST decision's tenant layer denies): they
        // learn nothing — stricter than the admin view, which still
        // lists the retained grants to the administrator.
        assertThat(problemOf(getRaw(john.token(), eligibilityUri(world))).get("code"))
                .isEqualTo("TENANT_NOT_FOUND");

        // Person-status flip (the S6-04 switch): suspend the person,
        // then re-enable the membership path and confirm the person
        // switch still denies until the person is re-enabled.
        Map<String, Object> reactivated = new HashMap<>();
        reactivated.put("status", "ACTIVE");
        put(
                world.platformAdmin(),
                "/api/v1/tenants/" + world.tenantId + "/testers/" + john.testerId() + "/status",
                reactivated,
                200);
        get(john.token(), downloadUri(world, world.versionId, "ANDROID"), 200);
        Map<String, Object> personSuspended = new HashMap<>();
        personSuspended.put("status", "SUSPENDED");
        personSuspended.put("reason", "person switch fixture");
        put(world.platformAdmin(), "/api/v1/testers/" + john.testerId() + "/status", personSuspended, 200);
        assertThat(problemOf(getRaw(john.token(), downloadUri(world, world.versionId, "ANDROID")))
                        .get("code"))
                .isEqualTo("VERSION_NOT_FOUND");
    }

    // -----------------------------------------------------------------------
    // World and helpers (the S7-02 integration suite's own shape)
    // -----------------------------------------------------------------------

    private World world(String suffix) {
        String platformAdmin = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        UUID orgId = UUID.fromString(
                post(platformAdmin, "/api/v1/organizations", Map.of("name", "S703 " + suffix + " Corp"), 201)
                        .get("id")
                        .asText());
        UUID tenantId = UUID.fromString(post(
                        platformAdmin,
                        "/api/v1/tenants",
                        Map.of(
                                "organizationId", orgId.toString(),
                                "name", "Banking " + suffix,
                                "externalId", "ext-s703-" + suffix),
                        201)
                .get("id")
                .asText());
        UUID applicationId = UUID.fromString(post(
                        platformAdmin,
                        "/api/v1/applications",
                        Map.of(
                                "tenantId",
                                tenantId.toString(),
                                "name",
                                "Banking",
                                "platforms",
                                List.of("ANDROID"),
                                "packageName",
                                AndroidFixtures.PACKAGE),
                        201)
                .get("id")
                .asText());
        UUID versionId = UUID.fromString(post(
                        platformAdmin,
                        "/api/v1/applications/" + applicationId + "/versions",
                        Map.of("versionName", AndroidFixtures.VERSION_NAME),
                        201)
                .get("id")
                .asText());
        return new World(platformAdmin, orgId, tenantId, applicationId, versionId);
    }

    private record World(String platformAdmin, UUID orgId, UUID tenantId, UUID applicationId, UUID versionId) {}

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
        return UUID.fromString(post(
                        world.platformAdmin(),
                        "/api/v1/applications/" + world.applicationId + "/versions",
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

    private static List<String> versionNamesOf(JsonNode eligibility) {
        List<String> names = new java.util.ArrayList<>();
        for (JsonNode entry : eligibility.get("entries")) {
            names.add(entry.get("versionName").asString());
        }
        return names;
    }

    private UUID grantExplicitVersion(World world, TesterPerson tester) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("testerId", tester.testerId().toString());
        body.put("applicationId", world.applicationId.toString());
        body.put("accessType", "EXPLICIT_VERSION");
        body.put("versionId", world.versionId.toString());
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

    private String downloadUri(World world, UUID versionId, String platform) {
        return downloadUriOf(world.tenantId, versionId, platform);
    }

    private String downloadUriOf(UUID tenantId, UUID versionId, String platform) {
        return "/api/v1/authorization/decisions/download?tenantId=" + tenantId + "&versionId=" + versionId
                + "&platform=" + platform;
    }

    private String eligibilityUri(World world) {
        return eligibilityUriOf(world.tenantId, world.applicationId);
    }

    private String eligibilityUriOf(UUID tenantId, UUID applicationId) {
        return "/api/v1/authorization/decisions/eligibility?tenantId=" + tenantId + "&applicationId=" + applicationId;
    }

    private String revokeUri(World world, UUID versionId) {
        return "/api/v1/applications/" + world.applicationId + "/versions/" + versionId + "/revoke-distribution";
    }

    private double cacheHits(String family) {
        var counter = meters.find(com.shipperdog.api.config.MetricNames.AUTHORIZATION_DECISION_CACHE)
                .tag("family", family)
                .tag("outcome", "hit")
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private UUID readyAndroidArtifact(World world) throws Exception {
        org.springframework.util.MultiValueMap<String, Object> parts =
                new org.springframework.util.LinkedMultiValueMap<>();
        parts.add("version_id", world.versionId.toString());
        parts.add("platform", "ANDROID");
        parts.add(
                "file", new org.springframework.core.io.ByteArrayResource(AndroidFixtures.aabVersionCodeInManifest()) {
                    @Override
                    public String getFilename() {
                        return "banking.aab";
                    }
                });
        Recording uploaded = exchange("/api/v1/artifacts", world.platformAdmin(), HttpMethod.POST, parts);
        assertThat(uploaded.status).as("artifact upload: %s", uploaded.body).isEqualTo(201);
        UUID artifactId = UUID.fromString(uploaded.body.get("id").asText());
        long deadline = System.currentTimeMillis() + 20_000;
        String status = "";
        while (System.currentTimeMillis() < deadline) {
            status = get(
                            world.platformAdmin(),
                            "/api/v1/artifacts/" + artifactId + "?version_id=" + world.versionId,
                            200)
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

    private String loginOfOrgRoleIn(String adminToken, UUID orgId, String email, String role) {
        JsonNode invitation = post(
                adminToken,
                "/api/v1/auth/invitations",
                Map.of("email", email, "organizationId", orgId.toString(), "role", role),
                201);
        client.post()
                .uri("/api/v1/auth/register")
                .body(Map.of(
                        "invitationToken",
                        invitation.get("invitationToken").asString(),
                        "email",
                        email,
                        "password",
                        PASSWORD,
                        "fullName",
                        "S703 " + role))
                .retrieve()
                .body(String.class);
        return accessToken(login(email, PASSWORD));
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

    private JsonNode put(String token, String uri, Map<String, Object> body, int expectedStatus) {
        Recording response = exchange(uri, token, HttpMethod.PUT, body);
        assertThat(response.status).as("PUT %s: %s", uri, response.body).isEqualTo(expectedStatus);
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

    private static Map<String, Object> problemOf(Recording recording) {
        Map<String, Object> shape = new LinkedHashMap<>();
        shape.put("status", recording.status);
        shape.put("code", recording.body.get("code").asString());
        shape.put("title", recording.body.get("title").asString());
        shape.put("detail", recording.body.get("detail").asString());
        return shape;
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
                    response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                    raw.length == 0 ? mapper.createObjectNode() : mapper.readTree(raw),
                    response.getHeaders().getFirst("Idempotency-Replayed"));
        });
    }

    private record Recording(int status, String contentType, JsonNode body, String replayed) {}
}
