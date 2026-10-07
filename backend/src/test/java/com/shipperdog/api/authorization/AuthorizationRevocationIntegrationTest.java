package com.shipperdog.api.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.artifact.AndroidFixtures;
import com.shipperdog.api.audit.AuditEvent;
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
 * The S7-04 acceptance flow (issue #33, INV-007): revocation semantics
 * end to end over a REAL stack — PostgreSQL, Redis (the decision-input
 * cache), and the GCS emulator — with the decision API as the
 * observation surface. A grant revocation denies the very next
 * download decision through a warm cache (AC1); the revoked row is
 * retained and re-granting mints a NEW grant whose effect is the only
 * access (AC4); the revocation report lists lost grants with both
 * directions of provenance (AC5); the reason is mandatory and every
 * flip is audited with actor + reason + before/after (AC6, FR-024);
 * a REVOKED version and a non-distributable artifact deny downloads
 * for EVERYONE regardless of grants — including the platform wildcard
 * (AC3); and the bulk pass empties a tester's grant set in one
 * transaction with one cache drop (the folded #32 follow-up).
 *
 * <p>AC2 (previously issued signed URLs live to their own TTL) is a
 * documented bound — signed URLs do not exist until S8-04; see
 * docs/authorization.md's revocation-semantics section, whose S6-04
 * alignment the docs suite covers.
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
class AuthorizationRevocationIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";
    private static final String PASSWORD = "a-perfectly-valid-password";

    /** The compose GCS emulator image, in a container (ADR-0003). */
    @Container
    static final GenericContainer<?> GCS = new GenericContainer<>(
                    DockerImageName.parse("fsouza/fake-gcs-server:1.56.1"))
            .withCommand("-scheme", "http", "-host", "0.0.0.0", "-port", "4443")
            .withExposedPorts(4443);

    /** The decision-input cache's own store: real Redis (S7-03). */
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
    // AC1: revocation denies the NEXT decision, through a warm cache
    // -----------------------------------------------------------------------

    @Test
    void revokingAGrantBlocksTheVeryNextDecisionThroughTheWarmCache() throws Exception {
        World world = world("s704-immediate");
        readyAndroidArtifact(world);
        TesterPerson john = testerOf(world, "s704-immediate@example.com", "Immediate Viewer");
        UUID grantId = grantExplicitVersion(world, john);

        // Warm the cache: the ALLOWED download decision caches the
        // grant set; the second call proves the hit leg.
        double hitsBefore = cacheHits("grants");
        get(john.token(), downloadUri(world, world.versionId, "ANDROID"), 200);
        get(john.token(), downloadUri(world, world.versionId, "ANDROID"), 200);
        assertThat(cacheHits("grants")).isGreaterThan(hitsBefore);
        assertThat(versionNamesOf(get(john.token(), eligibilityUri(world), 200)))
                .containsExactly(AndroidFixtures.VERSION_NAME);

        // Revoke (the DELETE route's S7-04 semantics): reason
        // mandatory, the row retained, the cache dropped write-through.
        Recording revoked = exchange(grantUri(world, grantId) + "?reason=overshared-build", world.platformAdmin());
        assertThat(revoked.status).isEqualTo(204);

        // The NEXT decisions deny — no TTL window, no stale allow.
        assertThat(problemOf(getRaw(john.token(), downloadUri(world, world.versionId, "ANDROID")))
                        .get("code"))
                .isEqualTo("VERSION_NOT_FOUND");
        assertThat(versionNamesOf(get(john.token(), eligibilityUri(world), 200)))
                .as("the eligibility listing drops the revoked grant's entry immediately")
                .isEmpty();

        // The audit trail carries the flip with actor + reason (FR-024).
        assertThat(audit.eventsOfType(AuditEventType.AUTHORIZATION_GRANT_REVOKED))
                .hasSize(1);
        AuditEvent event =
                audit.eventsOfType(AuditEventType.AUTHORIZATION_GRANT_REVOKED).getFirst();
        assertThat(event.userId()).isEqualTo(platformAdminUserId());
        assertThat(event.reason()).isEqualTo("overshared-build");
        assertThat(event.tenantId()).isEqualTo(world.tenantId);
        assertThat(event.detail())
                .containsEntry("status.before", "ACTIVE")
                .containsEntry("status.after", "REVOKED")
                .containsEntry("testerId", john.testerId().toString());

        // The row is RETAINED as history (INV-007: no deletes anywhere).
        JsonNode retained = get(world.platformAdmin(), grantUri(world, grantId), 200);
        assertThat(retained.get("status").asString()).isEqualTo("REVOKED");
        assertThat(retained.get("revocationReason").asString()).isEqualTo("overshared-build");
    }

    // -----------------------------------------------------------------------
    // AC6: the reason is mandatory (1..1000, trimmed) on every flip
    // -----------------------------------------------------------------------

    @Test
    void theRevocationReasonIsMandatoryAndBounded() throws Exception {
        World world = world("s704-reason");
        TesterPerson john = testerOf(world, "s704-reason@example.com", "Reason Viewer");
        UUID grantId = grantExplicitVersion(world, john);

        assertThat(codeOf(exchange(grantUri(world, grantId), world.platformAdmin())))
                .as("a revocation without a reason is a 400, never a silent flip")
                .isEqualTo("INVALID_REQUEST");
        assertThat(codeOf(exchange(grantUri(world, grantId) + "?reason=" + "x".repeat(1001), world.platformAdmin())))
                .isEqualTo("INVALID_REQUEST");
        assertThat(codeOf(exchange(grantUri(world, grantId) + "?reason=%20%20", world.platformAdmin())))
                .isEqualTo("INVALID_REQUEST");
        // A trimmed reason within bounds flips: the 204 of a real
        // revocation, not a validation refusal.
        assertThat(exchange(grantUri(world, grantId) + "?reason=ok", world.platformAdmin()).status)
                .isEqualTo(204);
        assertThat(audit.eventsOfType(AuditEventType.AUTHORIZATION_GRANT_REVOKED))
                .hasSize(1);
    }

    // -----------------------------------------------------------------------
    // AC4: re-granting after revocation = a NEW row; old row retained
    // -----------------------------------------------------------------------

    @Test
    void reGrantingAfterRevocationCreatesANewRowAndAccessIsTheNewGrantOnly() throws Exception {
        World world = world("s704-regrant");
        readyAndroidArtifact(world);
        TesterPerson john = testerOf(world, "s704-regrant@example.com", "Regrant Viewer");
        UUID firstId = grantExplicitVersion(world, john);
        assertThat(decides(world, john, world.versionId)).isTrue();

        exchange(grantUri(world, firstId) + "?reason=first-pilot-over", world.platformAdmin());
        assertThat(decides(world, john, world.versionId)).isFalse();

        // The same identity re-granted: a NEW row (201 created=true),
        // because the duplicate authority scopes to ACTIVE rows.
        Map<String, Object> again = explicitRequest(world, john.testerId(), world.versionId);
        JsonNode second = post(world.platformAdmin(), grantsPath(world), again, 201);
        assertThat(second.get("created").asBoolean()).isTrue();
        UUID secondId = UUID.fromString(second.get("grant").get("id").asText());
        assertThat(secondId).isNotEqualTo(firstId);

        // Effective access = the NEW grant only; the revoked twin stays
        // retained and never contributes.
        assertThat(decides(world, john, world.versionId)).isTrue();
        JsonNode effective = effectiveView(world, john);
        assertThat(effective.get("entries").size()).isEqualTo(1);
        assertThat(effective.get("entries").get(0).get("sourceGrantIds").get(0).asText())
                .isEqualTo(secondId.toString());

        // The duplicate semantics attach to the ACTIVE row: the replay
        // of the new grant is idempotent, and its expiry conflict is
        // the 409 — the revoked twin neither replays nor conflicts.
        JsonNode replay = post(world.platformAdmin(), grantsPath(world), again, 200);
        assertThat(replay.get("created").asBoolean()).isFalse();
        assertThat(replay.get("grant").get("id").asText()).isEqualTo(secondId.toString());
        Map<String, Object> renewed = new HashMap<>(again);
        renewed.put("expiresAt", "2027-06-01T00:00:00Z");
        assertThat(codeOf(postRaw(world.platformAdmin(), grantsPath(world), renewed)))
                .isEqualTo("AUTHORIZATION_GRANT_CONFLICT");

        // The revoked twin is queryable history with its facts.
        JsonNode revokedRow = get(world.platformAdmin(), grantUri(world, firstId), 200);
        assertThat(revokedRow.get("status").asString()).isEqualTo("REVOKED");
        assertThat(revokedRow.get("revokedAt")).isNotNull();
        assertThat(revokedRow.get("revokedBy").asString())
                .isEqualTo(platformAdminUserId().toString());
    }

    // -----------------------------------------------------------------------
    // AC3: revoked versions and non-distributable artifacts deny for
    // EVERYONE — grants and the platform wildcard included
    // -----------------------------------------------------------------------

    @Test
    void aRevokedVersionDeniesDownloadsForEveryoneRegardlessOfGrants() throws Exception {
        World world = world("s704-version");
        readyAndroidArtifact(world);
        TesterPerson john = testerOf(world, "s704-version@example.com", "Version Viewer");
        grantExplicitVersion(world, john);

        // The engine rule (S4-03 recorded the state; this story pins
        // the "for everyone" half): revoke the version's distribution.
        post(
                world.platformAdmin(),
                "/api/v1/applications/" + world.applicationId + "/versions/" + world.versionId + "/revoke-distribution",
                Map.of("reason", "bad build shipped"),
                200);

        // The granted tester's download conceals as the version miss.
        assertThat(problemOf(getRaw(john.token(), downloadUri(world, world.versionId, "ANDROID")))
                        .get("code"))
                .isEqualTo("VERSION_NOT_FOUND");

        // The PLATFORM WILDCARD is entitled at the version layer (the
        // role overlay) — the lifecycle fact still denies the download:
        // the rule precedes every entitlement. Reads stay available
        // (INV-006).
        JsonNode adminDownload = inspect(world.platformAdmin(), downloadContext(world, john));
        assertThat(adminDownload.get("allowed").asBoolean()).isFalse();
        assertThat(adminDownload.get("failingLayer").asString()).isEqualTo("VERSION");
        assertThat(adminDownload.get("reason").asString()).isEqualTo("VERSION_REVOKED");
        JsonNode adminAsSubject = inspect(world.platformAdmin(), downloadContextOf(platformAdminUserId(), world));
        assertThat(adminAsSubject.get("allowed").asBoolean()).isFalse();
        assertThat(adminAsSubject.get("reason").asString()).isEqualTo("VERSION_REVOKED");
        Map<String, Object> read = new HashMap<>(downloadContext(world, john));
        read.put("action", "READ_ARTIFACT");
        assertThat(inspect(world.platformAdmin(), read).get("allowed").asBoolean())
                .as("reads stay available (INV-006)")
                .isTrue();
    }

    @Test
    void aNonDistributableArtifactDeniesDownloadsForEveryoneRegardlessOfGrants() throws Exception {
        // PRD 18's availability gate: downloads require READY or
        // DISTRIBUTED — a FAILED artifact denies even the platform
        // wildcard (entitled at the version layer by the overlay).
        World world = world("s704-artifact", "com.other.registered");
        UUID artifactId = uploadArtifact(world, AndroidFixtures.aabVersionCodeInManifest(), "FAILED");
        TesterPerson john = testerOf(world, "s704-artifact@example.com", "Artifact Viewer");
        grantExplicitVersion(world, john);

        assertThat(inspect(world.platformAdmin(), artifactContextOf(platformAdminUserId(), world, artifactId))
                        .get("reason")
                        .asString())
                .isEqualTo("ARTIFACT_NOT_AVAILABLE");
        assertThat(inspect(world.platformAdmin(), artifactContextOf(userIdOfTester(john), world, artifactId))
                        .get("reason")
                        .asString())
                .isEqualTo("ARTIFACT_NOT_AVAILABLE");
    }

    // -----------------------------------------------------------------------
    // AC5: the revocation report
    // -----------------------------------------------------------------------

    @Test
    void theRevocationReportListsLostGrantsWithTimestampsAndActors() throws Exception {
        World world = world("s704-report");
        readyAndroidArtifact(world);
        TesterPerson john = testerOf(world, "s704-report@example.com", "Report Viewer");

        // Three grants with three fates: revoked, active-expired
        // (evaluation-time), and live.
        UUID revokedId = grantExplicitVersion(world, john.testerId(), world.versionId, null);
        UUID second = createVersion(world, "7.13.0");
        String soon = java.time.Instant.now()
                .plus(java.time.Duration.ofHours(1))
                .truncatedTo(java.time.temporal.ChronoUnit.MICROS)
                .toString();
        UUID expiringId = grantExplicitVersion(world, john.testerId(), second, soon);
        grantLatestN(world, john, 2);

        exchange(grantUri(world, revokedId) + "?reason=wrong-audience", world.platformAdmin());
        jdbc.update("UPDATE version_access SET expires_at = now() - interval '1 hour' WHERE id = ?", expiringId);

        JsonNode report = get(
                world.platformAdmin(),
                "/api/v1/tenants/" + world.tenantId + "/authorization-grants/revocation-report?testerId="
                        + john.testerId(),
                200);
        assertThat(report.get("items").size()).as("exactly the lost grants").isEqualTo(2);

        JsonNode revokedEntry = entryOfReport(report, revokedId);
        assertThat(revokedEntry.get("state").asString()).isEqualTo("REVOKED");
        assertThat(revokedEntry.get("revocationReason").asString()).isEqualTo("wrong-audience");
        assertThat(revokedEntry.get("revokedBy").asString())
                .isEqualTo(platformAdminUserId().toString());
        assertThat(revokedEntry.get("revokedAt")).isNotNull();
        assertThat(revokedEntry.get("grantedBy")).isNotNull();
        assertThat(revokedEntry.get("grantedAt")).isNotNull();

        JsonNode expiredEntry = entryOfReport(report, expiringId);
        assertThat(expiredEntry.get("state").asString()).isEqualTo("EXPIRED");
        assertThat(expiredEntry.get("revokedAt").isNull())
                .as("expiry alone records no revocation")
                .isTrue();

        // The live grant never appears; the application filter narrows.
        assertThat(entryOfReportOrNull(report, "LATEST_N")).isNull();
        JsonNode narrowed = get(
                world.platformAdmin(),
                "/api/v1/tenants/" + world.tenantId + "/authorization-grants/revocation-report?testerId="
                        + john.testerId()
                        + "&applicationId="
                        + world.applicationId
                        + "&limit=1",
                200);
        assertThat(narrowed.get("items").size()).isEqualTo(1);
        assertThat(narrowed.has("next_cursor")).isTrue();

        // Concealment mirrors the effective view: a foreign or unknown
        // tester is the genuine miss's shape.
        World other = world("s704-report-other");
        TesterPerson foreign = testerOf(other, "s704-foreign@example.com", "Foreign Viewer");
        assertThat(codeOf(getRaw(
                        world.platformAdmin(),
                        "/api/v1/tenants/" + world.tenantId + "/authorization-grants/revocation-report?testerId="
                                + foreign.testerId())))
                .isEqualTo("TESTER_NOT_FOUND");
        JsonNode unlinked = post(
                world.platformAdmin(),
                "/api/v1/testers",
                Map.of(
                        "organizationId", world.orgId.toString(),
                        "email", "s704-unlinked@example.com",
                        "fullName", "Never Linked"),
                201);
        assertThat(codeOf(getRaw(
                        world.platformAdmin(),
                        "/api/v1/tenants/" + world.tenantId + "/authorization-grants/revocation-report?testerId="
                                + unlinked.get("id").asText())))
                .isEqualTo("TENANT_TESTER_MEMBERSHIP_NOT_FOUND");
    }

    // -----------------------------------------------------------------------
    // The bulk pass (folded #32 follow-up): one transaction, one drop
    // -----------------------------------------------------------------------

    @Test
    void theBulkPassRevokesEveryActiveGrantWithOneCacheDropAndStaysRepeatable() throws Exception {
        World world = world("s704-bulk");
        readyAndroidArtifact(world);
        TesterPerson john = testerOf(world, "s704-bulk@example.com", "Bulk Viewer");
        UUID second = createVersion(world, "7.13.0");
        setTags(world, second, List.of("BETA"));
        grantExplicitVersion(world, john.testerId(), world.versionId, null);
        Map<String, Object> tagGrant = new LinkedHashMap<>();
        tagGrant.put("testerId", john.testerId().toString());
        tagGrant.put("applicationId", world.applicationId.toString());
        tagGrant.put("accessType", "TAG");
        tagGrant.put("tag", "BETA");
        tagGrant.put("reason", "the beta channel");
        post(world.platformAdmin(), grantsPath(world), tagGrant, 201);

        // A second application: the pass must leave its grants alone
        // when narrowed — and take them when not.
        UUID secondApp = UUID.fromString(post(
                        world.platformAdmin(),
                        "/api/v1/applications",
                        Map.of(
                                "tenantId",
                                world.tenantId.toString(),
                                "name",
                                "Wallet " + "s704-bulk",
                                "platforms",
                                List.of("ANDROID"),
                                "packageName",
                                "com.acme.wallet.s704bulk"),
                        201)
                .get("id")
                .asText());
        UUID walletVersion = UUID.fromString(post(
                        world.platformAdmin(),
                        "/api/v1/applications/" + secondApp + "/versions",
                        Map.of("versionName", "1.0.0"),
                        201)
                .get("id")
                .asText());
        Map<String, Object> walletGrant = new LinkedHashMap<>();
        walletGrant.put("testerId", john.testerId().toString());
        walletGrant.put("applicationId", secondApp.toString());
        walletGrant.put("accessType", "EXPLICIT_VERSION");
        walletGrant.put("versionId", walletVersion.toString());
        walletGrant.put("reason", "wallet pilot");
        post(world.platformAdmin(), grantsPath(world), walletGrant, 201);

        // Warm the cache with an allowed decision; the wallet grant's
        // entitlement is observed through the inspection surface (the
        // wallet application carries no artifact, so the download
        // route's artifact grain would conceal regardless).
        get(john.token(), downloadUri(world, world.versionId, "ANDROID"), 200);
        assertThat(inspect(world.platformAdmin(), walletContext(john, world, walletVersion))
                        .get("allowed")
                        .asBoolean())
                .isTrue();

        // The narrowed pass: application-scoped, two flips.
        JsonNode narrowed = post(
                world.platformAdmin(),
                "/api/v1/tenants/" + world.tenantId + "/authorization-grants/bulk-revoke",
                Map.of(
                        "testerId", john.testerId().toString(),
                        "applicationId", world.applicationId.toString(),
                        "reason", "banking overshared"),
                200);
        assertThat(narrowed.get("revokedCount").asInt()).isEqualTo(2);
        assertThat(problemOf(getRaw(john.token(), downloadUri(world, world.versionId, "ANDROID")))
                        .get("code"))
                .isEqualTo("VERSION_NOT_FOUND");
        assertThat(inspect(world.platformAdmin(), walletContext(john, world, walletVersion))
                        .get("allowed")
                        .asBoolean())
                .as("the narrowed pass leaves the other application's grants alone")
                .isTrue();
        assertThat(audit.eventsOfType(AuditEventType.AUTHORIZATION_GRANT_REVOKED))
                .hasSize(2);

        // The full pass: everything left, through the warm cache.
        JsonNode full = post(
                world.platformAdmin(),
                "/api/v1/tenants/" + world.tenantId + "/authorization-grants/bulk-revoke",
                Map.of("testerId", john.testerId().toString(), "reason", "offboard the pilot"),
                200);
        assertThat(full.get("revokedCount").asInt()).isEqualTo(1);
        assertThat(inspect(world.platformAdmin(), walletContext(john, world, walletVersion))
                        .get("allowed")
                        .asBoolean())
                .as("the full pass takes the wallet grant too")
                .isFalse();
        assertThat(audit.eventsOfType(AuditEventType.AUTHORIZATION_GRANT_REVOKED))
                .hasSize(3);

        // Repeatable: the state is the replay answer.
        JsonNode repeat = post(
                world.platformAdmin(),
                "/api/v1/tenants/" + world.tenantId + "/authorization-grants/bulk-revoke",
                Map.of("testerId", john.testerId().toString(), "reason", "offboard the pilot"),
                200);
        assertThat(repeat.get("revokedCount").asInt()).isZero();
        assertThat(audit.eventsOfType(AuditEventType.AUTHORIZATION_GRANT_REVOKED))
                .hasSize(3);

        // The report answers the auditor's question: all three lost.
        JsonNode report = get(
                world.platformAdmin(),
                "/api/v1/tenants/" + world.tenantId + "/authorization-grants/revocation-report?testerId="
                        + john.testerId(),
                200);
        assertThat(report.get("items").size()).isEqualTo(3);

        // The reason is as mandatory here as everywhere.
        assertThat(codeOf(postRaw(
                        world.platformAdmin(),
                        "/api/v1/tenants/" + world.tenantId + "/authorization-grants/bulk-revoke",
                        Map.of("testerId", john.testerId().toString()))))
                .isEqualTo("INVALID_REQUEST");
    }

    // -----------------------------------------------------------------------
    // The V22 partial unique index under race: the revoke and two
    // re-grants of the SAME identity fire together (issue #33
    // acceptance criterion 4's concurrent half; the sequential half
    // is above)
    // -----------------------------------------------------------------------

    @Test
    void aReGrantRacingRevocationNeverAdmitsTwoActiveRowsOrARevokedRowThatAuthorizes() throws Exception {
        World world = world("s704-race");
        readyAndroidArtifact(world);
        TesterPerson john = testerOf(world, "s704-race@example.com", "Race Viewer");

        // Three rounds of the same race, each on a FRESH identity:
        // whatever the interleaving, V22's partial unique index admits
        // at most one ACTIVE row per identity, a lost create race
        // answers with the winner (never a leaked constraint
        // violation), and a revoked row never authorizes.
        for (int round = 0; round < 3; round++) {
            final int r = round;
            UUID versionId = createVersion(world, "9." + round + ".0");
            Map<String, Object> identity = explicitRequest(world, john.testerId(), versionId);
            UUID granted = UUID.fromString(post(world.platformAdmin(), grantsPath(world), identity, 201)
                    .get("grant")
                    .get("id")
                    .asText());

            java.util.concurrent.ExecutorService racers = java.util.concurrent.Executors.newFixedThreadPool(3);
            try {
                List<java.util.concurrent.Future<Recording>> outcomes = List.of(
                        racers.submit(
                                () -> exchange(grantUri(world, granted) + "?reason=race-" + r, world.platformAdmin())),
                        racers.submit(() -> postRaw(world.platformAdmin(), grantsPath(world), identity)),
                        racers.submit(() -> postRaw(world.platformAdmin(), grantsPath(world), identity)));
                for (java.util.concurrent.Future<Recording> outcome : outcomes) {
                    Recording recording = outcome.get(30, java.util.concurrent.TimeUnit.SECONDS);
                    // The race's legal answers only: the revoke's 204,
                    // the create's 201, or the lost-create replay's 200
                    // — a leaked unique-index violation would be a 500.
                    assertThat(recording.status)
                            .as("round %d: %s", round, recording.body)
                            .isIn(200, 201, 204);
                }
            } finally {
                racers.shutdownNow();
            }

            // Invariant 1: at most ONE ACTIVE row for the identity —
            // the partial unique index is the duplicate authority.
            Integer activeRows = jdbc.queryForObject(
                    "SELECT count(*) FROM version_access WHERE tenant_id = ? AND tester_id = ?"
                            + " AND version_id = ? AND status = 'ACTIVE'",
                    Integer.class,
                    world.tenantId,
                    john.testerId(),
                    versionId);
            assertThat(activeRows).as("round %d active rows", round).isLessThanOrEqualTo(1);

            // Invariant 2: the decision surface agrees with the ACTIVE
            // row — a revoked row NEVER authorizes, whatever raced.
            boolean allowed = inspect(world.platformAdmin(), downloadContextOf(userIdOfTester(john), world, versionId))
                    .get("allowed")
                    .asBoolean();
            assertThat(allowed).as("round %d allowed", round).isEqualTo(activeRows == 1);
        }

        // Each round's revoke flipped exactly one ACTIVE row (the
        // fresh grant); the racing creates replay or mint, never flip.
        assertThat(audit.eventsOfType(AuditEventType.AUTHORIZATION_GRANT_REVOKED))
                .hasSize(3);
    }

    // -----------------------------------------------------------------------
    // World and helpers (the S7-02/S7-03 integration suites' own shape)
    // -----------------------------------------------------------------------

    private World world(String suffix) {
        return world(suffix, AndroidFixtures.PACKAGE);
    }

    private World world(String suffix, String packageName) {
        String platformAdmin = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        UUID orgId = UUID.fromString(
                post(platformAdmin, "/api/v1/organizations", Map.of("name", "S704 " + suffix + " Corp"), 201)
                        .get("id")
                        .asText());
        UUID tenantId = UUID.fromString(post(
                        platformAdmin,
                        "/api/v1/tenants",
                        Map.of(
                                "organizationId", orgId.toString(),
                                "name", "Banking " + suffix,
                                "externalId", "ext-s704-" + suffix),
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

    private String grantsPath(World world) {
        return "/api/v1/tenants/" + world.tenantId + "/authorization-grants";
    }

    private String grantUri(World world, UUID grantId) {
        return grantsPath(world) + "/" + grantId;
    }

    private UUID grantExplicitVersion(World world, TesterPerson tester) {
        return grantExplicitVersion(world, tester.testerId(), world.versionId, null);
    }

    private UUID grantExplicitVersion(World world, UUID testerId, UUID versionId, String expiresAt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("testerId", testerId.toString());
        body.put("applicationId", world.applicationId.toString());
        body.put("accessType", "EXPLICIT_VERSION");
        body.put("versionId", versionId.toString());
        if (expiresAt != null) {
            body.put("expiresAt", expiresAt);
        }
        body.put("reason", "one specific version");
        return UUID.fromString(post(world.platformAdmin(), grantsPath(world), body, 201)
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
        post(world.platformAdmin(), grantsPath(world), body, 201);
    }

    private static Map<String, Object> explicitRequest(World world, UUID testerId, UUID versionId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("testerId", testerId.toString());
        body.put("applicationId", world.applicationId.toString());
        body.put("accessType", "EXPLICIT_VERSION");
        body.put("versionId", versionId.toString());
        body.put("reason", "one specific version");
        return body;
    }

    private boolean decides(World world, TesterPerson tester, UUID versionId) {
        return inspect(world.platformAdmin(), downloadContextOf(userIdOfTester(tester), world, versionId))
                .get("allowed")
                .asBoolean();
    }

    private JsonNode effectiveView(World world, TesterPerson tester) {
        return get(
                world.platformAdmin(),
                grantsPath(world) + "/effective?testerId=" + tester.testerId() + "&applicationId="
                        + world.applicationId,
                200);
    }

    private String downloadUri(World world, UUID versionId, String platform) {
        return downloadUriOf(world.tenantId, versionId, platform);
    }

    private String downloadUriOf(UUID tenantId, UUID versionId, String platform) {
        return "/api/v1/authorization/decisions/download?tenantId=" + tenantId + "&versionId=" + versionId
                + "&platform=" + platform;
    }

    private String eligibilityUri(World world) {
        return "/api/v1/authorization/decisions/eligibility?tenantId=" + world.tenantId + "&applicationId="
                + world.applicationId;
    }

    private Map<String, Object> downloadContext(World world, TesterPerson tester) {
        return downloadContextOf(userIdOfTester(tester), world, world.versionId);
    }

    private Map<String, Object> downloadContextOf(UUID userId, World world) {
        return downloadContextOf(userId, world, world.versionId);
    }

    private Map<String, Object> downloadContextOf(UUID userId, World world, UUID versionId) {
        Map<String, Object> context = new HashMap<>();
        context.put("userId", userId.toString());
        context.put("tenantId", world.tenantId.toString());
        context.put("versionId", versionId.toString());
        context.put("action", "DOWNLOAD");
        return context;
    }

    /** The download context pinned to one artifact (the ARTIFACT layer's addressing). */
    private Map<String, Object> artifactContextOf(UUID userId, World world, UUID artifactId) {
        Map<String, Object> context = downloadContextOf(userId, world, world.versionId);
        context.put("artifactId", artifactId.toString());
        context.put("platform", "ANDROID");
        return context;
    }

    private JsonNode inspect(String token, Map<String, Object> body) {
        return post(token, "/api/v1/authorization/decisions", body, 200);
    }

    /** The tester's DOWNLOAD entitlement for a version of another application (the inspect surface). */
    private Map<String, Object> walletContext(TesterPerson tester, World world, UUID walletVersion) {
        Map<String, Object> context = new HashMap<>();
        context.put("userId", userIdOfTester(tester).toString());
        context.put("tenantId", world.tenantId.toString());
        context.put("versionId", walletVersion.toString());
        context.put("action", "DOWNLOAD");
        return context;
    }

    private static List<String> versionNamesOf(JsonNode eligibility) {
        List<String> names = new java.util.ArrayList<>();
        for (JsonNode entry : eligibility.get("entries")) {
            names.add(entry.get("versionName").asString());
        }
        return names;
    }

    private static JsonNode entryOfReport(JsonNode report, UUID grantId) {
        JsonNode entry = entryOfReportOrNull(report, grantId.toString());
        if (entry == null) {
            throw new AssertionError("grant " + grantId + " missing from the report");
        }
        return entry;
    }

    private static JsonNode entryOfReportOrNull(JsonNode report, String idOrAccessType) {
        for (JsonNode entry : report.get("items")) {
            if (idOrAccessType.equals(entry.get("id").asText())
                    || idOrAccessType.equals(entry.get("accessType").asString())) {
                return entry;
            }
        }
        return null;
    }

    private UUID userIdOfTester(TesterPerson tester) {
        return jdbc.queryForObject("SELECT user_id FROM testers WHERE id = ?", UUID.class, tester.testerId());
    }

    private UUID platformAdminUserId() {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, DEV_ADMIN_EMAIL);
    }

    private double cacheHits(String family) {
        var counter = meters.find(com.shipperdog.api.config.MetricNames.AUTHORIZATION_DECISION_CACHE)
                .tag("family", family)
                .tag("outcome", "hit")
                .counter();
        return counter == null ? 0 : counter.count();
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
        return shape;
    }

    /** A DELETE with the mandatory reason (the S7-04 revocation route). */
    private Recording exchange(String uri, String token) {
        return exchange(uri, token, HttpMethod.DELETE, null);
    }

    private Recording exchange(String uri, String token, HttpMethod method, Object body) {
        // Fully-built URIs, never templates: the query strings carry
        // pre-encoded values (a percent-encoded blank reason must
        // reach the server as blank, not as a re-encoded literal).
        RestClient.RequestBodySpec spec = client.method(method).uri(java.net.URI.create(uri));
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
                    raw.length == 0 ? mapper.createObjectNode() : mapper.readTree(raw));
        });
    }

    private record Recording(int status, String contentType, JsonNode body) {}
}
