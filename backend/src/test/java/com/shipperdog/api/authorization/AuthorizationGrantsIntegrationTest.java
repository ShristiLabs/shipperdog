package com.shipperdog.api.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.artifact.AndroidFixtures;
import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.AuditCapture;
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
 * The S7-02 acceptance flow (issue #31, FR-011): the four grant
 * shapes end to end — the INV-005 resting state flipped by a grant
 * (the same tester persona S7-01 left denying now allows, and the
 * HTTP surface follows), an explicit grant's inability to deliver a
 * FAILED artifact's bytes (the S5-05 READY rule), application-wide
 * over everything the tenant sees, tag grants following live tag
 * membership, latest-N windows with version churn, expiry enforced at
 * evaluation time with TTL visible, the union of mixed overlapping
 * grants, CRUD validation with duplicate semantics, the audit trail
 * of every mutation, and the permission matrix of the administration
 * surface. The pure-set semantics live in
 * {@code internal.grant.EffectiveAccessResolverTest}; the engine's
 * per-decision probes in
 * {@code internal.grant.VersionAccessGrantEntitlementTest}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
@Import(AuditCapture.class)
class AuthorizationGrantsIntegrationTest {

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
    private ObjectMapper mapper;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        audit.reset();
    }

    // -----------------------------------------------------------------------
    // AC: the INV-005 flip — a grant authorizes the pure tester
    // -----------------------------------------------------------------------

    @Test
    void anExplicitGrantFlipsTheRestingStateAndTheHttpSurfaceFollows() throws Exception {
        World world = world("s702-flip");
        UUID artifactId = readyAndroidArtifact(world);
        TesterPerson john = testerOf(world, "s702-john@example.com", "John Tester");
        Map<String, Object> download = downloadContext(john, world, artifactId);

        // The resting state: no grants anywhere -> deny at the version
        // layer (INV-005 deny-by-default, S7-01's contract preserved).
        assertThat(inspect(world.platformAdmin(), download).get("allowed").asBoolean())
                .isFalse();
        assertThat(inspect(world.platformAdmin(), download).get("reason").asString())
                .isEqualTo("ENTITLEMENT_MISSING");
        assertThat(problemOf(getRaw(john.token(), "/api/v1/artifacts/" + artifactId + "?version_id=" + world.versionId))
                        .get("code"))
                .isEqualTo("VERSION_NOT_FOUND");

        // The grant flips it: the whole chain passes, the version
        // layer attributed by the grant contributor.
        JsonNode created = createGrant(
                world.platformAdmin(), world, explicitRequest(world, john.testerId(), world.versionId, null), 201);
        assertThat(created.get("created").asBoolean()).isTrue();
        JsonNode allowed = inspect(world.platformAdmin(), download);
        assertThat(allowed.get("allowed").asBoolean()).isTrue();
        JsonNode versionLayer = layerOf(allowed, "VERSION");
        assertThat(versionLayer.get("detail").asString()).contains("VersionAccessGrantEntitlement");
        // The engine is the grant's authority (the inspect surface
        // above): the artifact route's RBAC gate stays console-grade
        // in M1 (app:artifacts:read — no tester persona passes it),
        // and the tester's own portal surfaces arrive with S8. The
        // denial stays the concealed version miss either way.
        assertThat(problemOf(getRaw(john.token(), "/api/v1/artifacts/" + artifactId + "?version_id=" + world.versionId))
                        .get("code"))
                .isEqualTo("VERSION_NOT_FOUND");

        // The effective-access view the administrator watches: the
        // granted version with its TTL (null = never expires).
        JsonNode effective = effectiveView(world.platformAdmin(), world, john);
        assertThat(effective.get("effectiveAccess").asBoolean()).isTrue();
        assertThat(idsOf(effective)).containsExactly(world.versionId);
    }

    // -----------------------------------------------------------------------
    // AC1: an explicit grant cannot deliver a FAILED artifact's version
    // -----------------------------------------------------------------------

    @Test
    void anExplicitGrantCannotDeliverAFailedArtifactsVersion() throws Exception {
        // The registry's package differs from the fixture's binary:
        // validation fails at APPLICATION_MATCH and settles FAILED.
        World world = world("s702-failed", "com.other.registered");
        UUID artifactId = uploadArtifact(world, AndroidFixtures.aabVersionCodeInManifest(), "FAILED");
        TesterPerson john = testerOf(world, "s702-failed@example.com", "Failed Art Viewer");
        createGrant(world.platformAdmin(), world, explicitRequest(world, john.testerId(), world.versionId, null), 201);

        // The GRANT entitles the version (the entitlement seam passed);
        // the ARTIFACT layer refuses the bytes: downloads require READY
        // or DISTRIBUTED (S5-05) — the read carries no availability
        // gate and stays available.
        JsonNode download = inspect(world.platformAdmin(), downloadContext(john, world, artifactId));
        assertThat(download.get("allowed").asBoolean()).isFalse();
        assertThat(download.get("failingLayer").asString()).isEqualTo("ARTIFACT");
        assertThat(download.get("reason").asString()).isEqualTo("ARTIFACT_NOT_AVAILABLE");
        Map<String, Object> read = new HashMap<>(downloadContext(john, world, artifactId));
        read.put("action", "READ_ARTIFACT");
        assertThat(inspect(world.platformAdmin(), read).get("allowed").asBoolean())
                .isTrue();
    }

    // -----------------------------------------------------------------------
    // AC2: application-wide authorizes all versions the tenant can see
    // -----------------------------------------------------------------------

    @Test
    void anApplicationWideGrantAuthorizesEveryVisibleVersionAndTheChainStillApplies() throws Exception {
        World world = world("s702-appwide");
        UUID older = createVersion(world, "7.11.0");
        TesterPerson john = testerOf(world, "s702-wide@example.com", "Wide Viewer");
        createGrant(
                world.platformAdmin(),
                world,
                Map.of(
                        "testerId",
                        john.testerId().toString(),
                        "applicationId",
                        world.applicationId.toString(),
                        "accessType",
                        "APPLICATION",
                        "reason",
                        "whole app for the pilot"),
                201);
        for (UUID versionId : List.of(older, world.versionId)) {
            Map<String, Object> context = new HashMap<>(downloadContext(john, world, null));
            context.put("versionId", versionId.toString());
            context.remove("artifactId");
            assertThat(inspect(world.platformAdmin(), context).get("allowed").asBoolean())
                    .as("version %s is covered by the app-wide grant", versionId)
                    .isTrue();
        }

        // The chain still applies: an application the tenant cannot
        // see is a concealed miss at grant time (S3-03 visibility —
        // an app-wide grant never widens the tenant's own reach).
        World other = world("s702-appwide-other");
        Map<String, Object> invisible = new HashMap<>(Map.of(
                "testerId",
                john.testerId().toString(),
                "applicationId",
                other.applicationId.toString(),
                "accessType",
                "APPLICATION",
                "reason",
                "an app the granting tenant cannot see"));
        assertThat(problemOf(postRaw(
                                world.platformAdmin(),
                                "/api/v1/tenants/" + world.tenantId + "/authorization-grants",
                                invisible))
                        .get("code"))
                .isEqualTo("APPLICATION_NOT_FOUND");
    }

    // -----------------------------------------------------------------------
    // AC3: tag grants follow live tag membership
    // -----------------------------------------------------------------------

    @Test
    void aTagGrantCoversNewlyTaggedVersionsLive() throws Exception {
        World world = world("s702-tag");
        UUID second = createVersion(world, "7.13.0");
        setTags(world, world.versionId, List.of("BETA"));
        TesterPerson john = testerOf(world, "s702-tagged@example.com", "Tag Viewer");
        createGrant(
                world.platformAdmin(),
                world,
                Map.of(
                        "testerId", john.testerId().toString(),
                        "applicationId", world.applicationId.toString(),
                        "accessType", "TAG",
                        "tag", "beta",
                        "reason", "all BETA builds"),
                201);

        assertThat(decides(john, world, world.versionId)).isTrue();
        assertThat(decides(john, world, second)).isFalse();

        // A newly tagged version joins with no grant change; removing
        // the tag from the first leaves it out again — evaluation-time
        // membership, never a snapshot.
        setTags(world, second, List.of("BETA"));
        assertThat(decides(john, world, second)).isTrue();
        setTags(world, world.versionId, List.of());
        assertThat(decides(john, world, world.versionId)).isFalse();
    }

    // -----------------------------------------------------------------------
    // AC4: latest-N resolves the window with churn (FR-011's latest 3)
    // -----------------------------------------------------------------------

    @Test
    void aLatestNGrantResolvesTheNewestWorkingSetWithChurn() throws Exception {
        World world = world("s702-latest");
        // 7.12.0 exists from the world; add 7.12.1 and 7.12.2.
        UUID v1 = createVersion(world, "7.12.1");
        UUID v2 = createVersion(world, "7.12.2");
        TesterPerson john = testerOf(world, "s702-latest@example.com", "Latest Viewer");
        createGrant(
                world.platformAdmin(),
                world,
                Map.of(
                        "testerId",
                        john.testerId().toString(),
                        "applicationId",
                        world.applicationId.toString(),
                        "accessType",
                        "LATEST_N",
                        "latestN",
                        2,
                        "reason",
                        "the latest two builds"),
                201);

        // Window: {7.12.2, 7.12.1}; 7.12.0 is out.
        assertThat(decides(john, world, v2)).isTrue();
        assertThat(decides(john, world, v1)).isTrue();
        assertThat(decides(john, world, world.versionId)).isFalse();
        assertThat(idsOf(effectiveView(world.platformAdmin(), world, john))).containsExactlyInAnyOrder(v2, v1);

        // Churn: a 4th, newer version arrives -> the oldest (7.12.1)
        // drops out of the window, never the newest.
        UUID v3 = createVersion(world, "7.12.3");
        assertThat(idsOf(effectiveView(world.platformAdmin(), world, john))).containsExactlyInAnyOrder(v3, v2);
        assertThat(decides(john, world, v1)).isFalse();

        // An ARCHIVED version never occupies a window slot: archiving
        // the newest returns the window to the live builds.
        post(world.platformAdmin(), archiveUri(world, v3), Map.of(), 200);
        assertThat(idsOf(effectiveView(world.platformAdmin(), world, john)))
                .as("archived builds do not consume latest-N slots")
                .containsExactlyInAnyOrder(v2, v1);
    }

    // -----------------------------------------------------------------------
    // AC5: expiry enforced; TTL visible
    // -----------------------------------------------------------------------

    @Test
    void expiredGrantsNeverAuthorizeAndTheTtlIsVisible() throws Exception {
        World world = world("s702-ttl");
        TesterPerson john = testerOf(world, "s702-ttl@example.com", "Ttl Viewer");
        String soon =
                java.time.Instant.now().plus(java.time.Duration.ofHours(1)).toString();
        JsonNode created = createGrant(
                world.platformAdmin(), world, explicitRequest(world, john.testerId(), world.versionId, soon), 201);
        UUID grantId = UUID.fromString(created.get("grant").get("id").asText());
        assertThat(created.get("grant").get("expiresAt").asString()).isEqualTo(soon);

        // Live: the entry carries the TTL (AC5's visibility).
        JsonNode view = effectiveView(world.platformAdmin(), world, john);
        assertThat(view.get("effectiveAccess").asBoolean()).isTrue();
        assertThat(view.get("entries").size()).isEqualTo(1);
        assertThat(view.get("entries").get(0).get("expiresAt").asString()).isEqualTo(soon);

        // Expired: never authorizes, evaluation-time (no sweeper).
        jdbc.update("UPDATE version_access SET expires_at = now() - interval '1 hour' WHERE id = ?", grantId);
        assertThat(decides(john, world, world.versionId)).isFalse();
        JsonNode after = effectiveView(world.platformAdmin(), world, john);
        assertThat(after.get("entries").size()).isZero();
        assertThat(after.get("expiredGrantCount").asInt()).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // AC6: mixed overlapping grants compose as a union
    // -----------------------------------------------------------------------

    @Test
    void mixedOverlappingGrantsComposeAsAUnionWithSources() throws Exception {
        World world = world("s702-union");
        UUID oldest = createVersion(world, "7.10.0");
        UUID middle = createVersion(world, "7.11.0");
        setTags(world, middle, List.of("BETA"));
        TesterPerson john = testerOf(world, "s702-union@example.com", "Union Viewer");
        JsonNode explicitGrant = createGrant(
                        world.platformAdmin(), world, explicitRequest(world, john.testerId(), oldest, null), 201)
                .get("grant");
        JsonNode tagGrant = createGrant(
                        world.platformAdmin(),
                        world,
                        Map.of(
                                "testerId", john.testerId().toString(),
                                "applicationId", world.applicationId.toString(),
                                "accessType", "TAG",
                                "tag", "BETA",
                                "reason", "beta channel"),
                        201)
                .get("grant");

        // Union: the explicit oldest (out of any window), the tagged
        // middle, and the newest by the latest-1 window.
        createGrant(
                world.platformAdmin(),
                world,
                Map.of(
                        "testerId",
                        john.testerId().toString(),
                        "applicationId",
                        world.applicationId.toString(),
                        "accessType",
                        "LATEST_N",
                        "latestN",
                        1,
                        "reason",
                        "the newest build"),
                201);
        JsonNode view = effectiveView(world.platformAdmin(), world, john);
        assertThat(idsOf(view)).containsExactlyInAnyOrder(oldest, middle, world.versionId);
        JsonNode oldestEntry = entryOf(view, oldest);
        assertThat(oldestEntry.get("sourceGrantIds").size()).isEqualTo(1);
        assertThat(oldestEntry.get("sourceGrantIds").get(0).asText())
                .isEqualTo(explicitGrant.get("id").asText());
        assertThat(entryOf(view, middle).get("sourceGrantIds").get(0).asText())
                .isEqualTo(tagGrant.get("id").asText());
        // Entries are newest-first (the catalog order).
        assertThat(idsOf(view)).containsExactly(world.versionId, middle, oldest);
    }

    // -----------------------------------------------------------------------
    // CRUD: validation, duplicate semantics, expiry route, removal
    // -----------------------------------------------------------------------

    @Test
    void duplicateGrantsReplayIdempotentlyAndConflictsAnswer409() throws Exception {
        World world = world("s702-dup");
        TesterPerson john = testerOf(world, "s702-dup@example.com", "Dup Viewer");
        Map<String, Object> body = explicitRequest(world, john.testerId(), world.versionId, null);

        JsonNode first = createGrant(world.platformAdmin(), world, body, 201);
        assertThat(first.get("created").asBoolean()).isTrue();
        JsonNode replay = createGrant(world.platformAdmin(), world, body, 200);
        assertThat(replay.get("created").asBoolean()).isFalse();
        assertThat(replay.get("grant").get("id").asText())
                .isEqualTo(first.get("grant").get("id").asText());
        assertThat(audit.eventsOfType(AuditEventType.AUTHORIZATION_GRANT_CREATED))
                .as("an idempotent replay writes and audits nothing")
                .hasSize(1);

        // The same identity with a different expiry is a conflict —
        // renewal is the expiry route's job.
        Map<String, Object> renewed = new HashMap<>(body);
        renewed.put("expiresAt", "2027-01-01T00:00:00Z");
        assertThat(problemOf(postRaw(
                                world.platformAdmin(),
                                "/api/v1/tenants/" + world.tenantId + "/authorization-grants",
                                renewed))
                        .get("code"))
                .isEqualTo("AUTHORIZATION_GRANT_CONFLICT");

        // Two different explicit grants coexist (distinct targets).
        UUID other = createVersion(world, "7.13.0");
        assertThat(createGrant(world.platformAdmin(), world, explicitRequest(world, john.testerId(), other, null), 201)
                        .get("created")
                        .asBoolean())
                .isTrue();
    }

    @Test
    void creationValidatesShapePairingTargetsAndReason() throws Exception {
        World world = world("s702-valid");
        TesterPerson john = testerOf(world, "s702-valid@example.com", "Validation Viewer");
        String base = "/api/v1/tenants/" + world.tenantId + "/authorization-grants";
        Map<String, Object> explicit = explicitRequest(world, john.testerId(), world.versionId, null);

        assertThat(codeOf(postRaw(world.platformAdmin(), base, without(explicit, "reason"))))
                .isEqualTo("INVALID_REQUEST");
        assertThat(codeOf(postRaw(world.platformAdmin(), base, with(explicit, "reason", "x".repeat(1001)))))
                .isEqualTo("INVALID_REQUEST");
        assertThat(codeOf(postRaw(world.platformAdmin(), base, with(explicit, "versionId", null))))
                .as("EXPLICIT_VERSION requires its target")
                .isEqualTo("INVALID_REQUEST");
        assertThat(codeOf(postRaw(
                        world.platformAdmin(),
                        base,
                        Map.of(
                                "testerId", john.testerId().toString(),
                                "applicationId", world.applicationId.toString(),
                                "accessType", "APPLICATION",
                                "versionId", world.versionId.toString(),
                                "reason", "mixed shape"))))
                .as("APPLICATION carries no target")
                .isEqualTo("INVALID_REQUEST");
        assertThat(codeOf(postRaw(world.platformAdmin(), base, with(explicit, "latestN", 3))))
                .isEqualTo("INVALID_REQUEST");
        assertThat(codeOf(postRaw(
                        world.platformAdmin(),
                        base,
                        Map.of(
                                "testerId",
                                john.testerId().toString(),
                                "applicationId",
                                world.applicationId.toString(),
                                "accessType",
                                "LATEST_N",
                                "latestN",
                                0,
                                "reason",
                                "zero window"))))
                .isEqualTo("INVALID_REQUEST");
        assertThat(codeOf(postRaw(
                        world.platformAdmin(),
                        base,
                        Map.of(
                                "testerId",
                                john.testerId().toString(),
                                "applicationId",
                                world.applicationId.toString(),
                                "accessType",
                                "LATEST_N",
                                "latestN",
                                101,
                                "reason",
                                "too wide"))))
                .isEqualTo("INVALID_REQUEST");
        assertThat(codeOf(postRaw(
                        world.platformAdmin(),
                        base,
                        Map.of(
                                "testerId",
                                john.testerId().toString(),
                                "applicationId",
                                world.applicationId.toString(),
                                "accessType",
                                "SIDELOAD",
                                "reason",
                                "unknown shape"))))
                .isEqualTo("INVALID_REQUEST");

        // Unknown tag on the application: a 400, never a dead grant.
        assertThat(codeOf(postRaw(
                        world.platformAdmin(),
                        base,
                        Map.of(
                                "testerId", john.testerId().toString(),
                                "applicationId", world.applicationId.toString(),
                                "accessType", "TAG",
                                "tag", "GHOST",
                                "reason", "no such tag"))))
                .isEqualTo("AUTHORIZATION_GRANT_TAG_UNKNOWN");

        // Target concealment: unknown/foreign tester, unlinked tester,
        // invisible application, version of another application.
        assertThat(codeOf(postRaw(world.platformAdmin(), base, with(explicit, "testerId", UUID.randomUUID()))))
                .isEqualTo("TESTER_NOT_FOUND");
        World foreignOrg = world("s702-valid-foreign");
        TesterPerson foreignTester = testerOf(foreignOrg, "s702-foreign-tester@example.com", "Foreign Tester");
        assertThat(codeOf(postRaw(world.platformAdmin(), base, with(explicit, "testerId", foreignTester.testerId()))))
                .as("a foreign organization's tester is indistinguishable from a missing one")
                .isEqualTo("TESTER_NOT_FOUND");
        JsonNode unlinked = post(
                world.platformAdmin(),
                "/api/v1/testers",
                Map.of(
                        "organizationId", world.orgId.toString(),
                        "email", "s702-unlinked@example.com",
                        "fullName", "Never Linked"),
                201);
        assertThat(codeOf(postRaw(
                        world.platformAdmin(),
                        base,
                        with(
                                explicit,
                                "testerId",
                                UUID.fromString(unlinked.get("id").asText())))))
                .isEqualTo("TENANT_TESTER_MEMBERSHIP_NOT_FOUND");
        assertThat(codeOf(postRaw(world.platformAdmin(), base, with(explicit, "applicationId", UUID.randomUUID()))))
                .isEqualTo("APPLICATION_NOT_FOUND");
        assertThat(codeOf(postRaw(world.platformAdmin(), base, with(explicit, "versionId", foreignOrg.versionId))))
                .isEqualTo("VERSION_NOT_FOUND");

        // A past expiry is invalid at creation.
        assertThat(codeOf(postRaw(world.platformAdmin(), base, with(explicit, "expiresAt", "2020-01-01T00:00:00Z"))))
                .isEqualTo("INVALID_REQUEST");
    }

    @Test
    void theExpiryRouteAdministersTheTtlAndRemovalEndsTheGrant() throws Exception {
        World world = world("s702-expiry");
        TesterPerson john = testerOf(world, "s702-expiry@example.com", "Expiry Viewer");
        JsonNode grant = createGrant(
                        world.platformAdmin(),
                        world,
                        explicitRequest(world, john.testerId(), world.versionId, null),
                        201)
                .get("grant");
        UUID grantId = UUID.fromString(grant.get("id").asText());
        String uri = "/api/v1/tenants/" + world.tenantId + "/authorization-grants/" + grantId;

        JsonNode extended = put(
                world.platformAdmin(),
                uri + "/expiry",
                Map.of("expiresAt", "2027-06-01T00:00:00Z", "reason", "pilot extended"),
                200);
        assertThat(extended.get("expiresAt").asString()).isEqualTo("2027-06-01T00:00:00Z");
        // Same value: idempotent, no audit churn.
        put(world.platformAdmin(), uri + "/expiry", Map.of("expiresAt", "2027-06-01T00:00:00Z", "reason", "same"), 200);
        assertThat(audit.eventsOfType(AuditEventType.AUTHORIZATION_GRANT_EXPIRY_CHANGED))
                .hasSize(1);
        // Cleared to never-expires (a null field serializes explicitly).
        assertThat(put(world.platformAdmin(), uri + "/expiry", Map.of("reason", "no expiry"), 200)
                        .get("expiresAt")
                        .isNull())
                .isTrue();
        assertThat(codeOf(putRaw(
                        world.platformAdmin(),
                        uri + "/expiry",
                        Map.of("expiresAt", "2020-01-01T00:00:00Z", "reason", "into the past"))))
                .isEqualTo("INVALID_REQUEST");

        // Removal: 204, audited with the reason, and the tester's
        // access ends with the row.
        assertThat(decides(john, world, world.versionId)).isTrue();
        Recording removed = exchange(uri + "?reason=pilot-over", world.platformAdmin(), HttpMethod.DELETE, null);
        assertThat(removed.status).isEqualTo(204);
        assertThat(decides(john, world, world.versionId)).isFalse();
        assertThat(audit.eventsOfType(AuditEventType.AUTHORIZATION_GRANT_DELETED))
                .hasSize(1);
        AuditEvent deletion =
                audit.eventsOfType(AuditEventType.AUTHORIZATION_GRANT_DELETED).getFirst();
        assertThat(deletion.reason()).isEqualTo("pilot-over");
        assertThat(deletion.userId()).isEqualTo(platformAdminUserId());
        assertThat(problemOf(getRaw(world.platformAdmin(), uri)).get("code"))
                .isEqualTo("AUTHORIZATION_GRANT_NOT_FOUND");
    }

    @Test
    void theListingFiltersAndPagesWithTheCursorConvention() throws Exception {
        World world = world("s702-list");
        TesterPerson john = testerOf(world, "s702-list@example.com", "List Viewer");
        TesterPerson other = testerOf(world, "s702-list-2@example.com", "Second Viewer");
        createGrant(world.platformAdmin(), world, explicitRequest(world, john.testerId(), world.versionId, null), 201);
        UUID second = createVersion(world, "7.13.0");
        createGrant(world.platformAdmin(), world, explicitRequest(world, john.testerId(), second, null), 201);
        createGrant(world.platformAdmin(), world, explicitRequest(world, other.testerId(), world.versionId, null), 201);

        JsonNode all = get(world.platformAdmin(), "/api/v1/tenants/" + world.tenantId + "/authorization-grants", 200);
        assertThat(all.get("items").size()).isEqualTo(3);
        assertThat(all.has("next_cursor")).as("one page fits the working set").isFalse();

        JsonNode johns = get(
                world.platformAdmin(),
                "/api/v1/tenants/" + world.tenantId + "/authorization-grants?testerId=" + john.testerId(),
                200);
        assertThat(johns.get("items").size()).isEqualTo(2);
        JsonNode filtered = get(
                        world.platformAdmin(),
                        "/api/v1/tenants/" + world.tenantId + "/authorization-grants?testerId=" + john.testerId()
                                + "&applicationId=" + world.applicationId,
                        200)
                .get("items");
        assertThat(filtered.size()).isEqualTo(2);
        JsonNode onePage =
                get(world.platformAdmin(), "/api/v1/tenants/" + world.tenantId + "/authorization-grants?limit=2", 200);
        assertThat(onePage.get("items").size()).isEqualTo(2);
        assertThat(onePage.has("next_cursor")).as("two more grants follow").isTrue();
    }

    // -----------------------------------------------------------------------
    // AC7 + permissions: audit and the administration matrix
    // -----------------------------------------------------------------------

    @Test
    void everyMutationIsAuditedWithActorShapeAndReason() throws Exception {
        World world = world("s702-audit");
        TesterPerson john = testerOf(world, "s702-audit@example.com", "Audit Viewer");
        UUID second = createVersion(world, "7.13.0");
        String soon = "2027-03-01T00:00:00Z";

        UUID grantId = UUID.fromString(createGrant(
                        world.platformAdmin(),
                        world,
                        Map.of(
                                "testerId",
                                john.testerId().toString(),
                                "applicationId",
                                world.applicationId.toString(),
                                "accessType",
                                "LATEST_N",
                                "latestN",
                                3,
                                "expiresAt",
                                soon,
                                "reason",
                                "the latest three, expiring"),
                        201)
                .get("grant")
                .get("id")
                .asText());
        AuditEvent creation =
                audit.eventsOfType(AuditEventType.AUTHORIZATION_GRANT_CREATED).getFirst();
        assertThat(creation.userId()).isEqualTo(platformAdminUserId());
        assertThat(creation.tenantId()).isEqualTo(world.tenantId);
        assertThat(creation.applicationId()).isEqualTo(world.applicationId);
        assertThat(creation.reason()).isEqualTo("the latest three, expiring");
        assertThat(creation.detail())
                .containsEntry("accessType", "LATEST_N")
                .containsEntry("latestN", "3")
                .containsEntry("testerId", john.testerId().toString())
                .containsEntry("expiresAt", soon)
                .containsEntry("grantId", grantId.toString());
        put(
                world.platformAdmin(),
                "/api/v1/tenants/" + world.tenantId + "/authorization-grants/" + grantId + "/expiry",
                Map.of("reason", "tightened"),
                200);
        AuditEvent change = audit.eventsOfType(AuditEventType.AUTHORIZATION_GRANT_EXPIRY_CHANGED)
                .getFirst();
        assertThat(change.detail()).containsEntry("expiry.before", soon).containsEntry("expiry.after", "null");
        exchange(
                "/api/v1/tenants/" + world.tenantId + "/authorization-grants/" + grantId + "?reason=done",
                world.platformAdmin(),
                HttpMethod.DELETE,
                null);
        assertThat(audit.eventsOfType(AuditEventType.AUTHORIZATION_GRANT_DELETED))
                .hasSize(1);
    }

    @Test
    void grantAdministrationFollowsThePermissionMatrix() throws Exception {
        World world = world("s702-perms");
        TesterPerson john = testerOf(world, "s702-perms@example.com", "Perm Viewer");
        Map<String, Object> body = explicitRequest(world, john.testerId(), world.versionId, null);
        String base = "/api/v1/tenants/" + world.tenantId + "/authorization-grants";

        // TENANT_ADMIN (the tenant role), ORG_ADMIN and APP_OWNER (the
        // owning-organization overlay) administer grants.
        String tenantAdmin = tenantAdminOf(world);
        UUID byTenantAdmin = UUID.fromString(createGrant(tenantAdmin, world, body, 201)
                .get("grant")
                .get("id")
                .asText());
        exchange(base + "/" + byTenantAdmin + "?reason=cleanup", tenantAdmin, HttpMethod.DELETE, null);
        String appOwner =
                loginOfOrgRoleIn(world.platformAdmin(), world.orgId, "s702-appowner@example.com", "APP_OWNER");
        assertThat(createGrant(appOwner, world, body, 201).get("created").asBoolean())
                .isTrue();

        // DEVELOPER and the pure tester persona cannot even learn the
        // route exists: the concealed 404 of a genuine tenant miss.
        String developer = loginOfOrgRoleIn(world.platformAdmin(), world.orgId, "s702-dev@example.com", "DEVELOPER");
        Map<String, Object> denied = problemOf(getRaw(developer, base));
        Map<String, Object> genuine =
                problemOf(getRaw(developer, "/api/v1/tenants/" + UUID.randomUUID() + "/authorization-grants"));
        assertThat(denied.get("status")).isEqualTo(404);
        assertThat(denied).containsAllEntriesOf(genuine);
        assertThat(denied.get("code")).isEqualTo("TENANT_NOT_FOUND");
        assertThat(problemOf(postRaw(john.token(), base, body)).get("code"))
                .as("the tester persona has no administration surface")
                .isEqualTo("TENANT_NOT_FOUND");
    }

    @Test
    void creationHonorsTheIdempotencyKeyHeader() throws Exception {
        World world = world("s702-idem");
        TesterPerson john = testerOf(world, "s702-idem@example.com", "Idem Viewer");
        String uri = "/api/v1/tenants/" + world.tenantId + "/authorization-grants";
        Map<String, Object> body = explicitRequest(world, john.testerId(), world.versionId, null);

        Recording first =
                exchange(uri, world.platformAdmin(), HttpMethod.POST, body, "4f9d2c1e-77b0-4f2a-9c8d-grant01");
        assertThat(first.status).isEqualTo(201);
        Recording retry =
                exchange(uri, world.platformAdmin(), HttpMethod.POST, body, "4f9d2c1e-77b0-4f2a-9c8d-grant01");
        assertThat(retry.status).isEqualTo(201);
        assertThat(retry.replayed).isEqualTo("true");
        assertThat(audit.eventsOfType(AuditEventType.AUTHORIZATION_GRANT_CREATED))
                .hasSize(1);
    }

    // -----------------------------------------------------------------------
    // The two-switches rule composed with grants (S6-03 + S7-02)
    // -----------------------------------------------------------------------

    @Test
    void suspendingTheMembershipDeniesLiveEvenWithAGrant() throws Exception {
        World world = world("s702-switches");
        TesterPerson john = testerOf(world, "s702-switches@example.com", "Switches Viewer");
        createGrant(world.platformAdmin(), world, explicitRequest(world, john.testerId(), world.versionId, null), 201);
        assertThat(decides(john, world, world.versionId))
                .as("the grant authorizes while both switches are on")
                .isTrue();

        // The grant STAYS (grant-time checks nothing about
        // effectiveness); the membership switch denies at the tenant
        // layer on the very next evaluation — decision time, not
        // grant time.
        put(
                world.platformAdmin(),
                "/api/v1/tenants/" + world.tenantId + "/testers/" + john.testerId() + "/status",
                Map.of("status", "SUSPENDED"),
                200);
        JsonNode denied = inspect(world.platformAdmin(), new HashMap<>(downloadContext(john, world, null)));
        assertThat(denied.get("allowed").asBoolean()).isFalse();
        assertThat(denied.get("failingLayer").asString()).isEqualTo("TENANT");
        JsonNode view = effectiveView(world.platformAdmin(), world, john);
        assertThat(view.get("effectiveAccess").asBoolean()).isFalse();
        assertThat(view.get("entries").size())
                .as("the suspended tester's grants stay listed (nothing is swept)")
                .isEqualTo(1);

        // Re-enable: both switches on again, the same grant authorizes.
        put(
                world.platformAdmin(),
                "/api/v1/tenants/" + world.tenantId + "/testers/" + john.testerId() + "/status",
                Map.of("status", "ACTIVE"),
                200);
        assertThat(decides(john, world, world.versionId)).isTrue();
    }

    // -----------------------------------------------------------------------
    // Tenant lifecycle and the isolation seam
    // -----------------------------------------------------------------------

    @Test
    void aDeactivatedTenantTakesNoNewGrantsButStaysAdministrable() throws Exception {
        World world = world("s702-deactivated");
        TesterPerson john = testerOf(world, "s702-deactivated@example.com", "Deactivated Viewer");
        JsonNode grant = createGrant(
                        world.platformAdmin(),
                        world,
                        explicitRequest(world, john.testerId(), world.versionId, null),
                        201)
                .get("grant");
        UUID grantId = UUID.fromString(grant.get("id").asText());

        put(world.platformAdmin(), "/api/v1/tenants/" + world.tenantId, Map.of("status", "DEACTIVATED"), 200);
        assertThat(codeOf(postRaw(
                        world.platformAdmin(),
                        "/api/v1/tenants/" + world.tenantId + "/authorization-grants",
                        explicitRequest(world, john.testerId(), world.versionId, null))))
                .isEqualTo("TENANT_DEACTIVATED");

        // Deactivation blocks NEW affiliations only (the tenant
        // lifecycle contract): existing grants stay administrable.
        assertThat(get(world.platformAdmin(), "/api/v1/tenants/" + world.tenantId + "/authorization-grants", 200)
                        .get("items")
                        .size())
                .isEqualTo(1);
        put(
                world.platformAdmin(),
                "/api/v1/tenants/" + world.tenantId + "/authorization-grants/" + grantId + "/expiry",
                Map.of("expiresAt", "2028-01-01T00:00:00Z", "reason", "parked tenant, live grant"),
                200);
    }

    @Test
    void grantsAreIsolatedPerTenant() throws Exception {
        World world = world("s702-isolated");
        TesterPerson john = testerOf(world, "s702-isolated@example.com", "Isolated Viewer");
        UUID grantId = UUID.fromString(createGrant(
                        world.platformAdmin(),
                        world,
                        explicitRequest(world, john.testerId(), world.versionId, null),
                        201)
                .get("grant")
                .get("id")
                .asText());

        // A second tenant of the same organization: its administrator
        // sees none of the first tenant's grants (the seam scopes
        // every read) and cannot address them by id (concealed miss).
        UUID secondTenant = UUID.fromString(post(
                        world.platformAdmin(),
                        "/api/v1/tenants",
                        Map.of(
                                "organizationId",
                                world.orgId.toString(),
                                "name",
                                "Sister " + "s702-isolated",
                                "externalId",
                                "ext-s702-sister"),
                        201)
                .get("id")
                .asText());
        String sisterAdmin = tenantAdminOfTenant(world.platformAdmin(), world.orgId, secondTenant);
        assertThat(get(sisterAdmin, "/api/v1/tenants/" + secondTenant + "/authorization-grants", 200)
                        .get("items")
                        .size())
                .isZero();
        assertThat(problemOf(getRaw(
                                sisterAdmin, "/api/v1/tenants/" + secondTenant + "/authorization-grants/" + grantId))
                        .get("code"))
                .isEqualTo("AUTHORIZATION_GRANT_NOT_FOUND");
    }

    @Test
    void aGrantThroughTheMappingAuthorizesASharedApplication() throws Exception {
        // AC2's full shape: the tenant HOLDS the application per the
        // S3-03 mapping (it does not own it). The app-wide grant
        // through the mapped tenant authorizes the tester; the
        // contributor matches the grant's tenant live (effective
        // membership + the mapping), and a de-authorization denies on
        // the next evaluation.
        World world = world("s702-shared");
        UUID sharedApp = UUID.fromString(post(
                        world.platformAdmin(),
                        "/api/v1/applications",
                        Map.of(
                                "ownershipScope", "ORGANIZATION",
                                "organizationId", world.orgId.toString(),
                                "name", "Shared Banking",
                                "platforms", List.of("ANDROID"),
                                "packageName", "com.acme.shared"),
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
        TesterPerson john = testerOf(world, "s702-shared@example.com", "Shared Viewer");
        createGrant(
                world.platformAdmin(),
                world,
                Map.of(
                        "testerId",
                        john.testerId().toString(),
                        "applicationId",
                        sharedApp.toString(),
                        "accessType",
                        "APPLICATION",
                        "reason",
                        "the whole shared app"),
                201);
        Map<String, Object> context = new HashMap<>(downloadContext(john, world, null));
        context.put("applicationId", sharedApp.toString());
        context.put("versionId", sharedVersion.toString());
        assertThat(inspect(world.platformAdmin(), context).get("allowed").asBoolean())
                .as("the mapped tenant's grant covers the shared application's versions")
                .isTrue();

        // De-authorize the mapping (S3-03): the application layer
        // denies the very next evaluation — the chain still applies.
        exchange(
                "/api/v1/applications/" + sharedApp + "/tenants/" + world.tenantId,
                world.platformAdmin(),
                HttpMethod.DELETE,
                null);
        JsonNode denied = inspect(world.platformAdmin(), context);
        assertThat(denied.get("allowed").asBoolean()).isFalse();
        assertThat(denied.get("failingLayer").asString()).isEqualTo("APPLICATION");
    }

    // -----------------------------------------------------------------------
    // World and helpers
    // -----------------------------------------------------------------------

    /** One organization, tenant, Banking application, version 7.12.0, and a DEVELOPER. */
    private World world(String suffix) {
        return world(suffix, AndroidFixtures.PACKAGE);
    }

    private World world(String suffix, String packageName) {
        String platformAdmin = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        UUID orgId = UUID.fromString(
                post(platformAdmin, "/api/v1/organizations", Map.of("name", "S702 " + suffix + " Corp"), 201)
                        .get("id")
                        .asText());
        UUID tenantId = UUID.fromString(post(
                        platformAdmin,
                        "/api/v1/tenants",
                        Map.of(
                                "organizationId", orgId.toString(),
                                "name", "Banking " + suffix,
                                "externalId", "ext-s702-" + suffix),
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
                                packageName),
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

    private String archiveUri(World world, UUID versionId) {
        return "/api/v1/applications/" + world.applicationId + "/versions/" + versionId + "/archive";
    }

    private JsonNode createGrant(String token, World world, Map<String, Object> body, int expectedStatus) {
        return post(token, "/api/v1/tenants/" + world.tenantId + "/authorization-grants", body, expectedStatus);
    }

    private static Map<String, Object> explicitRequest(World world, UUID testerId, UUID versionId, String expiresAt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("testerId", testerId.toString());
        body.put("applicationId", world.applicationId.toString());
        body.put("accessType", "EXPLICIT_VERSION");
        body.put("versionId", versionId.toString());
        if (expiresAt != null) {
            body.put("expiresAt", expiresAt);
        }
        body.put("reason", "one specific version");
        return body;
    }

    private Map<String, Object> downloadContext(TesterPerson tester, World world, UUID artifactId) {
        Map<String, Object> context = new HashMap<>();
        context.put("userId", userIdOfTester(tester).toString());
        context.put("tenantId", world.tenantId.toString());
        context.put("versionId", world.versionId.toString());
        if (artifactId != null) {
            context.put("artifactId", artifactId.toString());
            context.put("platform", "ANDROID");
        }
        context.put("action", "DOWNLOAD");
        return context;
    }

    private boolean decides(TesterPerson tester, World world, UUID versionId) {
        Map<String, Object> context = new HashMap<>(downloadContext(tester, world, null));
        context.put("versionId", versionId.toString());
        return inspect(world.platformAdmin(), context).get("allowed").asBoolean();
    }

    private JsonNode effectiveView(String token, World world, TesterPerson tester) {
        return get(
                token,
                "/api/v1/tenants/" + world.tenantId + "/authorization-grants/effective?testerId=" + tester.testerId()
                        + "&applicationId=" + world.applicationId,
                200);
    }

    private static List<UUID> idsOf(JsonNode view) {
        return java.util.stream.StreamSupport.stream(view.get("entries").spliterator(), false)
                .map(entry -> UUID.fromString(entry.get("versionId").asText()))
                .toList();
    }

    private static JsonNode entryOf(JsonNode view, UUID versionId) {
        for (JsonNode entry : view.get("entries")) {
            if (versionId.toString().equals(entry.get("versionId").asText())) {
                return entry;
            }
        }
        throw new AssertionError("version " + versionId + " missing from the effective view");
    }

    private UUID userIdOfTester(TesterPerson tester) {
        return jdbc.queryForObject("SELECT user_id FROM testers WHERE id = ?", UUID.class, tester.testerId());
    }

    private UUID platformAdminUserId() {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, DEV_ADMIN_EMAIL);
    }

    /** A TENANT_ADMIN console member of the given (arbitrary) tenant. */
    private String tenantAdminOfTenant(String platformAdmin, UUID orgId, UUID tenantId) {
        String email = "s702-admin-" + tenantId + "@example.com";
        JsonNode invitation = post(
                platformAdmin,
                "/api/v1/auth/invitations",
                Map.of("email", email, "organizationId", orgId.toString(), "role", "VIEWER"),
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
                        "Sister Admin"))
                .retrieve()
                .body(String.class);
        UUID userId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
        post(
                platformAdmin,
                "/api/v1/tenants/" + tenantId + "/members",
                Map.of("userId", userId.toString(), "role", "TENANT_ADMIN"),
                201);
        return accessToken(login(email, PASSWORD));
    }

    private String tenantAdminOf(World world) {
        JsonNode invitation = post(
                world.platformAdmin(),
                "/api/v1/auth/invitations",
                Map.of(
                        "email",
                        "s702-tenantadmin@example.com",
                        "organizationId",
                        world.orgId.toString(),
                        "role",
                        "VIEWER"),
                201);
        client.post()
                .uri("/api/v1/auth/register")
                .body(Map.of(
                        "invitationToken",
                        invitation.get("invitationToken").asString(),
                        "email",
                        "s702-tenantadmin@example.com",
                        "password",
                        PASSWORD,
                        "fullName",
                        "Tenant Admin"))
                .retrieve()
                .body(String.class);
        UUID userId =
                jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, "s702-tenantadmin@example.com");
        post(
                world.platformAdmin(),
                "/api/v1/tenants/" + world.tenantId + "/members",
                Map.of("userId", userId.toString(), "role", "TENANT_ADMIN"),
                201);
        return accessToken(login("s702-tenantadmin@example.com", PASSWORD));
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
                        "S702 " + role))
                .retrieve()
                .body(String.class);
        return accessToken(login(email, PASSWORD));
    }

    private UUID readyAndroidArtifact(World world) throws Exception {
        return uploadArtifact(world, AndroidFixtures.aabVersionCodeInManifest(), "READY");
    }

    private UUID uploadArtifact(World world, byte[] bytes, String expectedStatus) throws Exception {
        org.springframework.util.MultiValueMap<String, Object> parts =
                new org.springframework.util.LinkedMultiValueMap<>();
        parts.add("version_id", world.versionId.toString());
        parts.add("platform", "ANDROID");
        parts.add("file", new org.springframework.core.io.ByteArrayResource(bytes) {
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
            if (expectedStatus.equals(status)) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(status).as("the fixture must settle %s", expectedStatus).isEqualTo(expectedStatus);
        return artifactId;
    }

    private JsonNode inspect(String token, Map<String, Object> body) {
        return post(token, "/api/v1/authorization/decisions", body, 200);
    }

    private static JsonNode layerOf(JsonNode decision, String layer) {
        for (JsonNode outcome : decision.get("chain")) {
            if (layer.equals(outcome.get("layer").asString())) {
                return outcome;
            }
        }
        throw new AssertionError("layer " + layer + " missing from the chain");
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

    private Recording postRaw(String token, String uri, Map<String, Object> body) {
        return exchange(uri, token, HttpMethod.POST, body);
    }

    private JsonNode put(String token, String uri, Map<String, Object> body, int expectedStatus) {
        Recording response = exchange(uri, token, HttpMethod.PUT, body);
        assertThat(response.status).as("PUT %s: %s", uri, response.body).isEqualTo(expectedStatus);
        return response.body;
    }

    private Recording putRaw(String token, String uri, Map<String, Object> body) {
        return exchange(uri, token, HttpMethod.PUT, body);
    }

    private JsonNode get(String token, String uri, int expectedStatus) {
        Recording response = getRaw(token, uri);
        assertThat(response.status).as("GET %s: %s", uri, response.body).isEqualTo(expectedStatus);
        return response.body;
    }

    private Recording getRaw(String token, String uri) {
        return exchange(uri, token, HttpMethod.GET, null);
    }

    private String codeOf(Recording recording) {
        return recording.body.get("code").asString();
    }

    private static Map<String, Object> problemOf(Recording recording) {
        Map<String, Object> shape = new LinkedHashMap<>();
        shape.put("status", recording.status);
        shape.put("code", recording.body.get("code").asString());
        shape.put("title", recording.body.get("title").asString());
        shape.put("detail", recording.body.get("detail").asString());
        return shape;
    }

    private static Map<String, Object> with(Map<String, Object> body, String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(body);
        copy.put(key, value);
        return copy;
    }

    private static Map<String, Object> without(Map<String, Object> body, String key) {
        Map<String, Object> copy = new LinkedHashMap<>(body);
        copy.remove(key);
        return copy;
    }

    private Recording exchange(String uri, String token, HttpMethod method, Object body) {
        return exchange(uri, token, method, body, null);
    }

    private Recording exchange(String uri, String token, HttpMethod method, Object body, String idempotencyKey) {
        RestClient.RequestBodySpec spec = client.method(method).uri(uri);
        if (token != null) {
            spec.headers(headers -> headers.setBearerAuth(token));
        }
        if (idempotencyKey != null) {
            spec.headers(headers -> headers.set("Idempotency-Key", idempotencyKey));
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
