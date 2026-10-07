package com.shipperdog.api.release;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.AuditCapture;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end acceptance flow for the release manifest (issue #57,
 * S9-03, PRD 16, FR-024): the freeze at the APPROVED crossing with
 * every PRD 16 block deep-copied and the digest audited (AC1/AC2/AC4),
 * the stored-never-recomputed read in JSON and YAML (AC3), the diff
 * between two freezes (AC5), the "what did tenant X receive on date
 * Y" compliance question answered from stored manifests (AC6, PRD
 * 16's own example), and the loud integrity verification that refuses
 * a tampered document (the never-mutate discipline).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
@Import(AuditCapture.class)
class ReleaseManifestIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";
    private static final String PASSWORD = "a-perfectly-valid-password";

    /** How a fresh release reaches APPROVED, the freeze moment. */
    private static final List<String> TO_APPROVED = List.of("VALIDATING", "READY", "APPROVAL_PENDING", "APPROVED");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @LocalServerPort
    int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AuditCapture audit;

    @Autowired
    private ObjectMapper mapper;

    private RestClient client;

    /** The test's own YAML reader (the same dataformat the render uses). */
    private final com.fasterxml.jackson.databind.ObjectMapper yamlReader =
            new com.fasterxml.jackson.databind.ObjectMapper(new YAMLFactory());

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        audit.reset();
    }

    // -----------------------------------------------------------------------
    // AC1 + AC2 + AC4: freeze at APPROVED, every block, digest audited
    // -----------------------------------------------------------------------

    @Test
    void theManifestFreezesAtApprovedCarryingEveryPrd16BlockAndTheAuditedDigest() throws Exception {
        World world = tenantOwnedWorld("relm-freeze");
        UUID group = createGroup(world.adminToken, world.tenantA, "Pilot Group");
        Fixture release = draftRelease(
                world,
                "1.4.0",
                421,
                "qa",
                "a1",
                List.of(target(world.tenantA, List.of(group)), target(world.tenantB, List.of())));

        // Before APPROVED there is no manifest: the honest state miss.
        JsonNode pending = get(world.managerToken, manifestPath(release.releaseId(), world.app), 404);
        assertThat(pending.get("code").asText()).isEqualTo("RELEASE_MANIFEST_NOT_FOUND");
        assertThat(manifestRowCount(release.releaseId())).isZero();
        assertThat(audit.eventsOfType(AuditEventType.RELEASE_MANIFEST_FROZEN)).isEmpty();

        walk(world.managerToken, release.releaseId(), world.app, TO_APPROVED);

        JsonNode view = get(world.managerToken, manifestPath(release.releaseId(), world.app), 200);
        assertThat(view.get("releaseId").asText()).isEqualTo(release.releaseId().toString());
        assertThat(view.get("releaseRef").asText()).matches("R-[0-9]+");
        assertThat(view.get("applicationId").asText()).isEqualTo(world.app.toString());
        assertThat(view.get("versionId").asText()).isEqualTo(release.versionId().toString());
        assertThat(view.get("frozenBy").asText())
                .isEqualTo(userIdOf(world.managerToken).toString());
        assertThat(view.get("frozenAt").asText()).isNotBlank();
        assertThat(view.get("sha256").asText()).matches("[0-9a-f]{64}");

        // AC2: every PRD 16 block, deep-copied values.
        JsonNode document = view.get("manifest");
        assertThat(document.get("release").get("releaseRef").asText()).startsWith("R-");
        assertThat(document.get("release").get("applicationName").asText()).isEqualTo(world.appName);
        assertThat(document.get("release").get("versionName").asText()).isEqualTo("1.4.0");
        assertThat(document.get("release").get("organizationId").asText()).isEqualTo(world.org.toString());
        JsonNode artifacts = document.get("artifacts");
        assertThat(artifacts).hasSize(1);
        assertThat(artifacts.get(0).get("platform").asText()).isEqualTo("IOS");
        assertThat(artifacts.get(0).get("artifactId").asText())
                .isEqualTo(release.artifactId().toString());
        assertThat(artifacts.get(0).get("buildNumber").asLong()).isEqualTo(421);
        assertThat(artifacts.get(0).get("sha256").asText()).isEqualTo("a1".repeat(32));
        assertThat(artifacts.get(0).get("sizeBytes").asLong()).isEqualTo(1024);
        assertThat(artifacts.get(0).get("fileName").asText()).isEqualTo("ios-421.ipa");
        assertThat(artifacts.get(0).get("uploadedAt").asText()).isNotBlank();
        JsonNode targets = document.get("targets");
        assertThat(targets).hasSize(2);
        // The freeze sorts targets by tenant id (UUID order), not
        // insertion order — look each up, never assume position.
        JsonNode acmeTarget = targetOf(targets, world.tenantA);
        assertThat(acmeTarget.get("testerGroupIds"))
                .extracting(JsonNode::asText)
                .containsExactly(group.toString());
        JsonNode contosoTarget = targetOf(targets, world.tenantB);
        assertThat(contosoTarget.get("testerGroupIds").isEmpty()).isTrue();
        assertThat(document.get("ring").get("code").asText()).isEqualTo("qa");
        assertThat(document.get("ring").get("name").asText()).isEqualTo("QA");
        assertThat(document.get("ring").get("position").asInt()).isEqualTo(1);
        // The not-yet-modeled blocks carry their keys with honest null values.
        assertThat(document.has("environment")).isTrue();
        assertThat(document.get("environment").isNull()).isTrue();
        assertThat(document.get("rollout").get("percentage").isNull()).isTrue();
        assertThat(document.get("configuration").has("configVersion")).isTrue();
        assertThat(document.get("configuration").get("configVersion").isNull()).isTrue();
        assertThat(document.get("configuration").get("featureFlags").isNull()).isTrue();
        assertThat(document.get("configuration").get("tenantConfig").isNull()).isTrue();
        assertThat(document.get("approval").get("required").isEmpty()).isTrue();

        // AC4: the digest is in the freeze audit event, once, with the counts.
        List<AuditEvent> events = audit.eventsOfType(AuditEventType.RELEASE_MANIFEST_FROZEN);
        assertThat(events).hasSize(1);
        AuditEvent event = events.get(0);
        assertThat(event.detail()).containsEntry("sha256", view.get("sha256").asText());
        assertThat(event.detail())
                .containsEntry("releaseRef", view.get("releaseRef").asText());
        assertThat(event.detail()).containsEntry("artifacts", "1");
        assertThat(event.detail()).containsEntry("targets", "2");
        assertThat(event.applicationId()).isEqualTo(world.app);
        assertThat(event.userId()).isEqualTo(userIdOf(world.managerToken));

        // The stored digest is the SHA-256 of the canonical bytes: the
        // stored content, key-sorted and re-serialized, hashes to it.
        String content = jdbc.queryForObject(
                "SELECT content FROM release_manifests WHERE release_id = ?", String.class, release.releaseId());
        String storedDigest = jdbc.queryForObject(
                "SELECT sha256 FROM release_manifests WHERE release_id = ?", String.class, release.releaseId());
        assertThat(canonicalSha256(content)).isEqualTo(storedDigest);
    }

    // -----------------------------------------------------------------------
    // AC1: immutability through the rest of the lifecycle
    // -----------------------------------------------------------------------

    @Test
    void theManifestIsImmutableAcrossTheRestOfTheLifecycleAndHasNoMutationSurface() throws Exception {
        World world = tenantOwnedWorld("relm-immutable");
        Fixture release =
                draftRelease(world, "2.0.0", 500, "engineering", "a1", List.of(target(world.tenantA, List.of())));
        walk(world.managerToken, release.releaseId(), world.app, TO_APPROVED);
        JsonNode frozen = get(world.managerToken, manifestPath(release.releaseId(), world.app), 200);
        String digest = frozen.get("sha256").asText();
        // The freeze event is already asserted by its own suite; this
        // test pins what happens AFTER — nothing may freeze again.
        audit.reset();

        // Every later crossing leaves the frozen document untouched.
        walk(
                world.managerToken,
                release.releaseId(),
                world.app,
                List.of("DEPLOYING", "ACTIVE", "PROMOTED", "COMPLETED"));
        assertThat(manifestRowCount(release.releaseId())).isEqualTo(1);
        JsonNode after = get(world.managerToken, manifestPath(release.releaseId(), world.app), 200);
        assertThat(after.get("sha256").asText()).isEqualTo(digest);
        assertThat(after.get("manifest")).isEqualTo(frozen.get("manifest"));
        assertThat(audit.eventsOfType(AuditEventType.RELEASE_MANIFEST_FROZEN)).isEmpty();

        // No mutation surface: PUT and POST on the manifest path do not exist.
        assertThat(exchange(manifestPath(release.releaseId(), world.app), world.managerToken, "PUT", Map.of())
                        .status())
                .isEqualTo(405);
        assertThat(exchange(manifestPath(release.releaseId(), world.app), world.managerToken, "POST", Map.of())
                        .status())
                .isEqualTo(405);

        // Re-entering APPROVED is not an edge; the manifest cannot re-freeze.
        JsonNode rejected = transition(world.managerToken, release.releaseId(), world.app, "APPROVED", null, 409);
        assertThat(rejected.get("code").asText()).isEqualTo("RELEASE_TRANSITION_INVALID");
        assertThat(manifestRowCount(release.releaseId())).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // AC1: exactly-once and atomicity under adversarial crossings
    // -----------------------------------------------------------------------

    @Test
    void concurrentApprovedCrossingsFreezeTheManifestExactlyOnce() throws Exception {
        World world = tenantOwnedWorld("relm-race");
        Fixture release = draftRelease(world, "8.0.0", 800, "qa", "a1", List.of(target(world.tenantA, List.of())));
        walk(world.managerToken, release.releaseId(), world.app, List.of("VALIDATING", "READY", "APPROVAL_PENDING"));

        audit.reset();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService racers = Executors.newFixedThreadPool(2);
        try {
            List<Future<Recording>> crossings = List.of(
                    racers.submit(() -> racedCrossing(world, release, start)),
                    racers.submit(() -> racedCrossing(world, release, start)));
            start.countDown();
            List<Recording> answers = new ArrayList<>();
            for (Future<Recording> crossing : crossings) {
                answers.add(crossing.get(60, TimeUnit.SECONDS));
            }
            // Exactly one crossing commits; the database compare-and-set
            // serializes the racers, the loser re-reads the committed
            // APPROVED state and answers the precise conflict.
            assertThat(answers).extracting(Recording::status).containsExactlyInAnyOrder(200, 409);
            Recording loser = answers.stream()
                    .filter(answer -> answer.status() == 409)
                    .findFirst()
                    .orElseThrow();
            assertThat(loser.body().get("code").asText()).isEqualTo("RELEASE_TRANSITION_INVALID");

            assertThat(manifestRowCount(release.releaseId())).isEqualTo(1);
            assertThat(audit.eventsOfType(AuditEventType.RELEASE_MANIFEST_FROZEN))
                    .hasSize(1);
            assertThat(audit.eventsOfType(AuditEventType.RELEASE_TRANSITIONED).stream()
                            .filter(event -> "APPROVED".equals(event.detail().get("toStatus")))
                            .count())
                    .isEqualTo(1);
        } finally {
            racers.shutdownNow();
        }
    }

    /** One racer's APPROVED attempt, released together with its peer by the latch. */
    private Recording racedCrossing(World world, Fixture release, CountDownLatch start) {
        try {
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return exchange(
                "/api/v1/releases/" + release.releaseId() + "/transitions",
                world.managerToken,
                "POST",
                Map.of("applicationId", world.app.toString(), "to", "APPROVED"));
    }

    @Test
    void aFreezeFailureRollsBackTheWholeApprovedCrossing() throws Exception {
        World world = tenantOwnedWorld("relm-atomic");
        Fixture release = draftRelease(world, "9.0.0", 900, "qa", "a1", List.of(target(world.tenantA, List.of())));
        walk(world.managerToken, release.releaseId(), world.app, List.of("VALIDATING", "READY", "APPROVAL_PENDING"));

        // A pre-existing manifest row (an operator's stray insert, say)
        // makes the freeze's write violate the one-per-release authority
        // INSIDE the transition's committing transaction — the loudest
        // possible probe of AC1's atomicity: the crossing and the freeze
        // must commit or roll back together.
        jdbc.update(
                "INSERT INTO release_manifests (release_id, content, sha256, frozen_at, frozen_by) "
                        + "VALUES (?, '\"stray\"'::jsonb, ?, now(), ?)",
                release.releaseId(),
                "ab".repeat(32),
                userIdOf(world.managerToken));
        audit.reset();

        JsonNode rejected = transition(world.managerToken, release.releaseId(), world.app, "APPROVED", null, 409);
        assertThat(rejected.get("code").asText()).isEqualTo("RELEASE_MANIFEST_IMMUTABLE");

        // The failed crossing left nothing behind: the status never
        // moved, the timeline carries no APPROVED row, and the stray
        // row stands untouched. The in-process audit capture may hold
        // the pre-rollback events (a capture-double artifact — the
        // durable sink writes only at BEFORE_COMMIT, which a rollback
        // never reaches), so durability is asserted on the audit table.
        JsonNode detail =
                get(world.managerToken, "/api/v1/releases/" + release.releaseId() + "?applicationId=" + world.app, 200);
        assertThat(detail.get("status").asText()).isEqualTo("APPROVAL_PENDING");
        String releaseRef = detail.get("releaseRef").asText();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM release_status_history WHERE release_id = ? AND to_status = 'APPROVED'",
                        Integer.class,
                        release.releaseId()))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM audit_events WHERE event_type = 'RELEASE_TRANSITIONED' "
                                + "AND detail->>'toStatus' = 'APPROVED' AND detail->>'releaseRef' = ?",
                        Integer.class,
                        releaseRef))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM audit_events WHERE event_type = 'RELEASE_MANIFEST_FROZEN' "
                                + "AND detail->>'releaseRef' = ?",
                        Integer.class,
                        releaseRef))
                .isZero();
        assertThat(manifestRowCount(release.releaseId())).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // AC3: the stored document, never recomputed; YAML is the same document
    // -----------------------------------------------------------------------

    @Test
    void theServedManifestIsTheStoredDocumentNotARecomputedView() throws Exception {
        World world = tenantOwnedWorld("relm-stored");
        UUID group = createGroup(world.adminToken, world.tenantA, "Pilot Group");
        Fixture release = draftRelease(world, "3.1.4", 77, "qa", "a1", List.of(target(world.tenantA, List.of(group))));
        walk(world.managerToken, release.releaseId(), world.app, TO_APPROVED);
        JsonNode frozen = get(world.managerToken, manifestPath(release.releaseId(), world.app), 200);

        // The world drifts: the application renames, and a stray target
        // row appears (an operator's console bug, say). The manifest
        // served after is the document frozen before.
        patch(world.ownerToken, "/api/v1/applications/" + world.app, Map.of("name", "Relm Stored Renamed"), 200);
        jdbc.update(
                "INSERT INTO release_targets (release_id, tenant_id, ring_id) VALUES (?, ?, "
                        + "(SELECT starting_ring_id FROM releases WHERE id = ?))",
                release.releaseId(),
                world.tenantB,
                release.releaseId());

        JsonNode served = get(world.managerToken, manifestPath(release.releaseId(), world.app), 200);
        assertThat(served.get("sha256").asText()).isEqualTo(frozen.get("sha256").asText());
        assertThat(served.get("manifest").get("release").get("applicationName").asText())
                .isEqualTo(world.appName);
        assertThat(served.get("manifest").get("targets")).hasSize(1);
        assertThat(served.get("manifest").get("targets").get(0).get("tenantId").asText())
                .isEqualTo(world.tenantA.toString());
    }

    @Test
    void yamlRendersTheSameStoredDocumentAndUnknownFormatsAreRejected() throws Exception {
        World world = tenantOwnedWorld("relm-yaml");
        Fixture release =
                draftRelease(world, "4.0.0", 42, "tenant-pilot", "a1", List.of(target(world.tenantA, List.of())));
        walk(world.managerToken, release.releaseId(), world.app, TO_APPROVED);
        JsonNode jsonView = get(world.managerToken, manifestPath(release.releaseId(), world.app), 200);

        Recording yaml = exchange(
                manifestPath(release.releaseId(), world.app) + "&format=yaml", world.managerToken, "GET", null);
        assertThat(yaml.status()).isEqualTo(200);
        assertThat(yaml.contentType()).startsWith("application/yaml");
        com.fasterxml.jackson.databind.JsonNode yamlView = yamlReader.readTree(yaml.text());
        // One stored document, two serializations: same digest, same
        // manifest (compared as Jackson 2 trees — ObjectNode equality
        // is key-order-insensitive, so the JSON render's component
        // order and the YAML render's alphabetical order agree).
        assertThat(yamlView.get("sha256").asText())
                .isEqualTo(jsonView.get("sha256").asText());
        com.fasterxml.jackson.databind.JsonNode yamlManifest = yamlView.get("manifest");
        com.fasterxml.jackson.databind.JsonNode jsonManifest =
                yamlReader.readTree(jsonView.get("manifest").toString());
        assertThat(yamlManifest).isEqualTo(jsonManifest);

        JsonNode rejected = get(world.managerToken, manifestPath(release.releaseId(), world.app) + "&format=xml", 400);
        assertThat(rejected.get("code").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(rejected.get("errors").get("format").asText()).contains("json or yaml");
    }

    @Test
    void unicodeContentFreezesAndHashesStablyAcrossJsonbStorage() throws Exception {
        World world = tenantOwnedWorld("relm-unicode");
        // Multibyte text enters the document BEFORE the crossing: the
        // canonical bytes carry UTF-8 multibyte characters, and the
        // digest must survive jsonb storage and read-back unchanged —
        // key order and escaping are jsonb's to reorder, content bytes
        // are the hash's to keep.
        String unicodeName = "Bänk 日本語 ✆ Portal";
        patch(world.ownerToken, "/api/v1/applications/" + world.app, Map.of("name", unicodeName), 200);
        Fixture release = draftRelease(world, "11.0.0", 1100, "qa", "a1", List.of(target(world.tenantA, List.of())));
        String unicodeFileName = "ünïcode-1100.ipa";
        jdbc.update("UPDATE artifacts SET file_name = ? WHERE id = ?", unicodeFileName, release.artifactId());
        walk(world.managerToken, release.releaseId(), world.app, TO_APPROVED);

        JsonNode served = get(world.managerToken, manifestPath(release.releaseId(), world.app), 200);
        assertThat(served.get("sha256").asText()).matches("[0-9a-f]{64}");
        assertThat(served.get("manifest").get("release").get("applicationName").asText())
                .isEqualTo(unicodeName);
        assertThat(served.get("manifest")
                        .get("artifacts")
                        .get(0)
                        .get("fileName")
                        .asText())
                .isEqualTo(unicodeFileName);

        // The read-side verification passed (the 200 above), and the
        // independent re-derivation of the canonical digest — sorted
        // keys over the STORED jsonb text — matches the stored hash.
        String content = jdbc.queryForObject(
                "SELECT content FROM release_manifests WHERE release_id = ?", String.class, release.releaseId());
        String storedDigest = jdbc.queryForObject(
                "SELECT sha256 FROM release_manifests WHERE release_id = ?", String.class, release.releaseId());
        assertThat(canonicalSha256(content)).isEqualTo(storedDigest);
        assertThat(served.get("sha256").asText()).isEqualTo(storedDigest);

        // The YAML render of the same stored document carries the same
        // multibyte values and the same digest.
        Recording yaml = exchange(
                manifestPath(release.releaseId(), world.app) + "&format=yaml", world.managerToken, "GET", null);
        assertThat(yaml.status()).isEqualTo(200);
        com.fasterxml.jackson.databind.JsonNode yamlView = yamlReader.readTree(yaml.text());
        assertThat(yamlView.get("sha256").asText()).isEqualTo(storedDigest);
        assertThat(yamlView.get("manifest")
                        .get("release")
                        .get("applicationName")
                        .asText())
                .isEqualTo(unicodeName);
    }

    // -----------------------------------------------------------------------
    // AC5: the diff between two freezes
    // -----------------------------------------------------------------------

    @Test
    void theDiffShowsArtifactTargetAndRingMovementsBetweenTwoFreezes() throws Exception {
        World world = tenantOwnedWorld("relm-diff");
        UUID group = createGroup(world.adminToken, world.tenantA, "Pilot Group");
        Fixture first = draftRelease(
                world,
                "5.0.0",
                100,
                "qa",
                "a1",
                List.of(target(world.tenantA, List.of(group)), target(world.tenantB, List.of())));
        Fixture second = draftRelease(
                world,
                "5.1.0",
                200,
                "tenant-pilot",
                "b2",
                List.of(target(world.tenantA, List.of()), target(world.tenantC, List.of())));
        walk(world.managerToken, first.releaseId(), world.app, TO_APPROVED);
        walk(world.managerToken, second.releaseId(), world.app, TO_APPROVED);

        JsonNode diff = get(world.managerToken, diffPath(first.releaseId(), second.releaseId(), world.app), 200);
        assertThat(diff.get("from").get("releaseId").asText())
                .isEqualTo(first.releaseId().toString());
        assertThat(diff.get("to").get("releaseId").asText())
                .isEqualTo(second.releaseId().toString());

        // The IOS pin changed: same platform, different bytes.
        JsonNode artifactChange = diff.get("artifacts").get("IOS");
        assertThat(artifactChange.get("change").asText()).isEqualTo("CHANGED");
        assertThat(artifactChange.get("from").get("sha256").asText()).isEqualTo("a1".repeat(32));
        assertThat(artifactChange.get("to").get("sha256").asText()).isEqualTo("b2".repeat(32));
        assertThat(artifactChange.get("from").get("buildNumber").asLong()).isEqualTo(100);
        assertThat(artifactChange.get("to").get("buildNumber").asLong()).isEqualTo(200);

        // Targets: tenant C added, tenant B removed, tenant A's group dropped.
        assertThat(diff.get("targets").get("added").toString()).contains(world.tenantC.toString());
        assertThat(diff.get("targets").get("removed").toString()).contains(world.tenantB.toString());
        JsonNode groupChange = diff.get("targets").get("groupsChanged").get(world.tenantA.toString());
        assertThat(groupChange.get("removed").toString()).contains(group.toString());

        // The ring moved; environment and rollout did not (null in both).
        assertThat(diff.get("ring").get("from").asText()).isEqualTo("qa");
        assertThat(diff.get("ring").get("to").asText()).isEqualTo("tenant-pilot");
        assertThat(diff.get("environment").isNull()).isTrue();
        assertThat(diff.get("rollout").isNull()).isTrue();
        assertThat(diff.get("configurationChanges").isEmpty()).isTrue();

        // The reverse diff mirrors; a self-diff is the 400.
        JsonNode reverse = get(world.managerToken, diffPath(second.releaseId(), first.releaseId(), world.app), 200);
        assertThat(reverse.get("ring").get("from").asText()).isEqualTo("tenant-pilot");
        assertThat(reverse.get("ring").get("to").asText()).isEqualTo("qa");
        assertThat(reverse.get("targets").get("added").toString()).contains(world.tenantB.toString());
        JsonNode selfDiff = get(world.managerToken, diffPath(second.releaseId(), second.releaseId(), world.app), 400);
        assertThat(selfDiff.get("errors").has("otherReleaseId")).isTrue();

        // A foreign otherReleaseId conceals; an unfrozen side answers the state miss.
        JsonNode concealed = get(world.managerToken, diffPath(second.releaseId(), UUID.randomUUID(), world.app), 404);
        assertThat(concealed.get("code").asText()).isEqualTo("RELEASE_NOT_FOUND");
        Fixture unfrozen = draftRelease(world, "5.2.0", 300, "qa", "a1", List.of(target(world.tenantA, List.of())));
        JsonNode pendingSide =
                get(world.managerToken, diffPath(second.releaseId(), unfrozen.releaseId(), world.app), 404);
        assertThat(pendingSide.get("code").asText()).isEqualTo("RELEASE_MANIFEST_NOT_FOUND");
    }

    @Test
    void theDiffReportsAddedAndRemovedPlatformsAcrossReleases() throws Exception {
        World world = tenantOwnedWorld("relm-platforms");
        Fixture iosOnly = draftReleasePlatforms(
                world,
                "10.0.0",
                "qa",
                List.of(new ArtifactSpec("IOS", 1000, "a1")),
                List.of(target(world.tenantA, List.of())));
        Fixture bothPlatforms = draftReleasePlatforms(
                world,
                "10.1.0",
                "qa",
                List.of(new ArtifactSpec("IOS", 1001, "a1"), new ArtifactSpec("ANDROID", 2001, "c3")),
                List.of(target(world.tenantA, List.of())));
        walk(world.managerToken, iosOnly.releaseId(), world.app, TO_APPROVED);
        walk(world.managerToken, bothPlatforms.releaseId(), world.app, TO_APPROVED);

        // Forward: ANDROID ships for the first time — ADDED with no
        // from-pin; IOS is a fresh row on the new version (CHANGED, the
        // existing suite's movement).
        JsonNode diff =
                get(world.managerToken, diffPath(iosOnly.releaseId(), bothPlatforms.releaseId(), world.app), 200);
        JsonNode added = diff.get("artifacts").get("ANDROID");
        assertThat(added.get("change").asText()).isEqualTo("ADDED");
        assertThat(added.get("from").isNull()).isTrue();
        assertThat(added.get("to").get("sha256").asText()).isEqualTo("c3".repeat(32));
        assertThat(added.get("to").get("buildNumber").asLong()).isEqualTo(2001);
        assertThat(added.get("to").get("fileName").asText()).isEqualTo("android-2001.apk");
        assertThat(diff.get("artifacts").get("IOS").get("change").asText()).isEqualTo("CHANGED");

        // Reverse: the same pair read backwards shows ANDROID REMOVED —
        // the pin the earlier freeze carried and the later one dropped.
        JsonNode reverse =
                get(world.managerToken, diffPath(bothPlatforms.releaseId(), iosOnly.releaseId(), world.app), 200);
        JsonNode removed = reverse.get("artifacts").get("ANDROID");
        assertThat(removed.get("change").asText()).isEqualTo("REMOVED");
        assertThat(removed.get("from").get("sha256").asText()).isEqualTo("c3".repeat(32));
        assertThat(removed.get("to").isNull()).isTrue();
    }

    // -----------------------------------------------------------------------
    // AC6: "what did tenant X receive on date Y", from stored manifests
    // -----------------------------------------------------------------------

    @Test
    void theComplianceQuestionAnswersFromStoredManifests() throws Exception {
        World world = tenantOwnedWorld("relm-acme");
        // Acme (tenantA) receives 1.0.0 (build 10) and then 2.0.0
        // (build 20); Contoso (tenantB) receives only 2.0.0. The
        // question — what did Acme receive — must answer artifact and
        // configuration per freeze, from the stored documents.
        Fixture first = draftRelease(world, "1.0.0", 10, "qa", "a1", List.of(target(world.tenantA, List.of())));
        Fixture second = draftRelease(
                world,
                "2.0.0",
                20,
                "production",
                "b2",
                List.of(target(world.tenantA, List.of()), target(world.tenantB, List.of())));
        walk(world.managerToken, first.releaseId(), world.app, TO_APPROVED);
        walk(world.managerToken, second.releaseId(), world.app, TO_APPROVED);

        // The compliance reviewer's query: the application's releases,
        // each manifest read back, filtered to Acme's tenant id.
        JsonNode page = get(world.managerToken, "/api/v1/releases?applicationId=" + world.app, 200);
        List<JsonNode> acmeReceived = new ArrayList<>();
        for (JsonNode item : page.get("items")) {
            JsonNode view = get(
                    world.managerToken,
                    manifestPath(UUID.fromString(item.get("id").asText()), world.app),
                    200);
            boolean acme = false;
            for (JsonNode target : view.get("manifest").get("targets")) {
                acme = acme
                        || world.tenantA
                                .toString()
                                .equals(target.get("tenantId").asText());
            }
            if (acme) {
                acmeReceived.add(view);
            }
        }
        assertThat(acmeReceived).hasSize(2);
        // Newest first (the listing's order): 2.0.0 then 1.0.0.
        assertThat(acmeReceived
                        .get(0)
                        .get("manifest")
                        .get("release")
                        .get("versionName")
                        .asText())
                .isEqualTo("2.0.0");
        assertThat(acmeReceived
                        .get(0)
                        .get("manifest")
                        .get("artifacts")
                        .get(0)
                        .get("buildNumber")
                        .asLong())
                .isEqualTo(20);
        assertThat(acmeReceived
                        .get(0)
                        .get("manifest")
                        .get("artifacts")
                        .get(0)
                        .get("sha256")
                        .asText())
                .isEqualTo("b2".repeat(32));
        assertThat(acmeReceived.get(0).get("manifest").get("ring").get("code").asText())
                .isEqualTo("production");
        assertThat(acmeReceived
                        .get(1)
                        .get("manifest")
                        .get("release")
                        .get("versionName")
                        .asText())
                .isEqualTo("1.0.0");
        assertThat(acmeReceived
                        .get(1)
                        .get("manifest")
                        .get("artifacts")
                        .get(0)
                        .get("sha256")
                        .asText())
                .isEqualTo("a1".repeat(32));
        // The configuration answer rides both: the key structure is
        // present, the honest null values frozen with each release.
        for (JsonNode view : acmeReceived) {
            JsonNode configuration = view.get("manifest").get("configuration");
            assertThat(configuration.has("configVersion")).isTrue();
            assertThat(configuration.get("featureFlags").isNull()).isTrue();
            assertThat(configuration.get("tenantConfig").isNull()).isTrue();
        }
    }

    // -----------------------------------------------------------------------
    // Integrity: a tampered document fails loudly, never auto-repaired
    // -----------------------------------------------------------------------

    @Test
    void aTamperedManifestFailsVerificationLoudlyAndIsNeverAutoRepaired() throws Exception {
        World world = tenantOwnedWorld("relm-tamper");
        Fixture release = draftRelease(world, "6.0.0", 900, "qa", "a1", List.of(target(world.tenantA, List.of())));
        walk(world.managerToken, release.releaseId(), world.app, TO_APPROVED);

        String tamperedDigest = "e0".repeat(32);
        jdbc.update(
                "UPDATE release_manifests SET content = jsonb_set(content, '{artifacts,0,sha256}', "
                        + "to_jsonb(?::text)) WHERE release_id = ?",
                tamperedDigest,
                release.releaseId());

        JsonNode corrupt = get(world.managerToken, manifestPath(release.releaseId(), world.app), 500);
        assertThat(corrupt.get("code").asText()).isEqualTo("RELEASE_MANIFEST_CORRUPT");
        // The platform repaired nothing: the tampered bytes stay as-is.
        String content = jdbc.queryForObject(
                "SELECT content FROM release_manifests WHERE release_id = ?", String.class, release.releaseId());
        assertThat(content).contains(tamperedDigest);
    }

    // -----------------------------------------------------------------------
    // Concealment parity: foreign and unknown release ids
    // -----------------------------------------------------------------------

    @Test
    void foreignAndUnknownReleasesConcealIdenticallyOnEveryManifestRoute() throws Exception {
        World world = tenantOwnedWorld("relm-conceal");
        World other = tenantOwnedWorld("relm-conceal-other");
        Fixture release = draftRelease(other, "7.0.0", 1, "qa", "a1", List.of(target(other.tenantA, List.of())));
        walk(other.managerToken, release.releaseId(), other.app, TO_APPROVED);

        JsonNode foreign = get(world.managerToken, manifestPath(release.releaseId(), world.app), 404);
        JsonNode unknown = get(world.managerToken, manifestPath(UUID.randomUUID(), world.app), 404);
        assertThat(foreign.get("code").asText()).isEqualTo("RELEASE_NOT_FOUND");
        assertThat(unknown.get("code").asText()).isEqualTo("RELEASE_NOT_FOUND");
        // Byte-identical modulo the per-request trace id and instance
        // path (each probe addresses a different unknown id).
        assertThat(concealmentShapeOf(foreign)).isEqualTo(concealmentShapeOf(unknown));

        JsonNode foreignDiff =
                get(world.managerToken, diffPath(UUID.randomUUID(), release.releaseId(), world.app), 404);
        assertThat(foreignDiff.get("code").asText()).isEqualTo("RELEASE_NOT_FOUND");
    }

    // -----------------------------------------------------------------------
    // Canonical digest, derived exactly as the read-side verification does
    // -----------------------------------------------------------------------

    private String canonicalSha256(String content) {
        tools.jackson.databind.json.JsonMapper canonical =
                tools.jackson.databind.json.JsonMapper.builder().build();
        // The canonical form sorts object keys recursively — jsonb's
        // stored order is irrelevant to the digest, exactly as the
        // service's read-side verification derives it.
        JsonNode sorted = sortedKeys(canonical.readTree(content), canonical);
        byte[] bytes = canonical.writeValueAsBytes(sorted);
        try {
            return java.util.HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode sortedKeys(JsonNode node, tools.jackson.databind.ObjectMapper mapper) {
        if (node.isObject()) {
            java.util.TreeMap<String, JsonNode> fields = new java.util.TreeMap<>();
            node.properties().forEach(entry -> fields.put(entry.getKey(), entry.getValue()));
            tools.jackson.databind.node.ObjectNode sorted = mapper.createObjectNode();
            fields.forEach((key, value) -> sorted.set(key, sortedKeys(value, mapper)));
            return sorted;
        }
        if (node.isArray()) {
            tools.jackson.databind.node.ArrayNode sorted = mapper.createArrayNode();
            node.forEach(child -> sorted.add(sortedKeys(child, mapper)));
            return sorted;
        }
        return node;
    }

    /** The concealment body modulo the per-request trace id and instance path. */
    private JsonNode concealmentShapeOf(JsonNode problem) {
        tools.jackson.databind.node.ObjectNode shape = ((tools.jackson.databind.node.ObjectNode) problem).deepCopy();
        shape.remove("instance");
        shape.remove("trace_id");
        return shape;
    }

    /** The target block of the given tenant from a frozen targets array. */
    private JsonNode targetOf(JsonNode targets, UUID tenantId) {
        for (JsonNode target : targets) {
            if (tenantId.toString().equals(target.get("tenantId").asText())) {
                return target;
            }
        }
        throw new AssertionError("tenant " + tenantId + " not among the frozen targets");
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private static final class World {
        UUID org;
        String adminToken;
        String ownerToken;
        String managerToken;
        UUID tenantA;
        UUID tenantB;
        UUID tenantC;
        UUID app;
        String appName;
    }

    /** One walked-or-draft release with its version and artifact. */
    private record Fixture(UUID releaseId, UUID versionId, UUID artifactId) {}

    /** One artifact slot of a multi-platform release fixture. */
    private record ArtifactSpec(String platform, long build, String shaPrefix) {}

    /**
     * A fresh DRAFT release pinning one artifact per given platform —
     * the multi-platform shape the single-artifact {@link #draftRelease}
     * cannot express (each platform's artifact is its own row on the
     * release's own version, exactly as the creation validation
     * demands).
     */
    private Fixture draftReleasePlatforms(
            World world, String versionName, String ring, List<ArtifactSpec> specs, List<Map<String, Object>> targets) {
        UUID version = createVersion(world.ownerToken, world.app, versionName);
        List<Map<String, Object>> artifacts = new ArrayList<>();
        UUID firstArtifact = null;
        for (ArtifactSpec spec : specs) {
            String extension = "IOS".equals(spec.platform()) ? ".ipa" : ".apk";
            String fileName = spec.platform().toLowerCase(Locale.ROOT) + "-" + spec.build() + extension;
            UUID artifact = insertArtifact(
                    version, spec.platform(), spec.build(), spec.shaPrefix().repeat(32), fileName);
            if (firstArtifact == null) {
                firstArtifact = artifact;
            }
            artifacts.add(Map.of("platform", spec.platform(), "artifactId", artifact.toString()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", world.app.toString());
        body.put("versionId", version.toString());
        body.put("ring", ring);
        body.put("artifacts", artifacts);
        body.put("targets", targets);
        UUID release = UUID.fromString(post(world.managerToken, "/api/v1/releases", body, 201)
                .get("id")
                .asText());
        return new Fixture(release, version, firstArtifact);
    }

    private Map<String, Object> target(UUID tenantId, List<UUID> groups) {
        return Map.of(
                "tenantId",
                tenantId.toString(),
                "testerGroupIds",
                groups.stream().map(UUID::toString).toList());
    }

    /** A fresh DRAFT release of a fresh version, one IOS artifact, two or three tenants targeted. */
    private Fixture draftRelease(
            World world,
            String versionName,
            long build,
            String ring,
            String shaPrefix,
            List<Map<String, Object>> targets) {
        UUID version = createVersion(world.ownerToken, world.app, versionName);
        UUID artifact = insertArtifact(version, "IOS", build, shaPrefix.repeat(32), "ios-" + build + ".ipa");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", world.app.toString());
        body.put("versionId", version.toString());
        body.put("ring", ring);
        body.put("artifacts", List.of(Map.of("platform", "IOS", "artifactId", artifact.toString())));
        body.put("targets", targets);
        UUID release = UUID.fromString(post(world.managerToken, "/api/v1/releases", body, 201)
                .get("id")
                .asText());
        return new Fixture(release, version, artifact);
    }

    /** Walks a release through the given crossings; the freeze fires on APPROVED. */
    private void walk(String bearer, UUID releaseId, UUID applicationId, List<String> steps) {
        for (String step : steps) {
            transition(bearer, releaseId, applicationId, step, null, 200);
        }
    }

    private UUID createGroup(String token, UUID tenantId, String name) {
        return UUID.fromString(
                post(token, "/api/v1/tester-groups", Map.of("tenantId", tenantId.toString(), "name", name), 201)
                        .get("id")
                        .asText());
    }

    /** Inserts an artifact row directly (READY) — the release fixture's binary-free shortcut. */
    private UUID insertArtifact(UUID versionId, String platform, long build, String sha256, String fileName) {
        UUID artifactId = UUID.randomUUID();
        Map<String, Object> owner =
                jdbc.queryForMap("SELECT organization_id, tenant_id FROM versions WHERE id = ?", versionId);
        jdbc.update(
                "INSERT INTO artifacts (id, version_id, platform, file_name, storage_key, sha256, size, "
                        + "build_number, status, organization_id, tenant_id) "
                        + "VALUES (?, ?, ?::platform, ?, ?, ?, ?, ?, 'READY'::artifact_status, ?, ?)",
                artifactId,
                versionId,
                platform,
                fileName,
                "fixture/" + artifactId + "/artifact",
                sha256,
                1024L,
                build,
                owner.get("organization_id"),
                owner.get("tenant_id"));
        return artifactId;
    }

    private int manifestRowCount(UUID releaseId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM release_manifests WHERE release_id = ?", Integer.class, releaseId);
    }

    private String manifestPath(UUID releaseId, UUID applicationId) {
        return "/api/v1/releases/" + releaseId + "/manifest?applicationId=" + applicationId;
    }

    private String diffPath(UUID releaseId, UUID otherReleaseId, UUID applicationId) {
        return "/api/v1/releases/" + releaseId + "/manifest/diff?applicationId=" + applicationId + "&otherReleaseId="
                + otherReleaseId;
    }

    /**
     * One organization with an ORGANIZATION-shared application and
     * three authorized target tenants: the release rows serve the
     * seam's organization-scope mode, and every target tenant holds
     * the application through an ACTIVE authorization (the S3-03
     * effective-visibility rule the multi-target fixtures need).
     */
    private World tenantOwnedWorld(String slug) {
        String suffix = slug.substring(slug.indexOf('-') + 1);
        String appName = "Relm " + Character.toUpperCase(suffix.charAt(0)) + suffix.substring(1) + " Banking";
        World world = new World();
        world.org = createOrg("Relm " + slug + " Corp");
        world.adminToken = loginOfOrgRoleIn(world.org, slug + "-admin", "ORG_ADMIN");
        world.ownerToken = loginOfOrgRoleIn(world.org, slug + "-owner", "APP_OWNER");
        world.managerToken = loginOfOrgRoleIn(world.org, slug + "-manager", "RELEASE_MANAGER");
        world.tenantA = createTenant(world.adminToken, world.org, "Relm " + slug + " Bank A", "crm-" + slug + "-a");
        world.tenantB = createTenant(world.adminToken, world.org, "Relm " + slug + " Bank B", "crm-" + slug + "-b");
        world.tenantC = createTenant(world.adminToken, world.org, "Relm " + slug + " Bank C", "crm-" + slug + "-c");
        world.appName = appName;
        Map<String, Object> app = new HashMap<>();
        app.put("ownershipScope", "ORGANIZATION");
        app.put("organizationId", world.org.toString());
        app.put("name", appName);
        app.put("platforms", List.of("IOS", "ANDROID"));
        app.put("bundleId", "com." + slug.replace("-", "") + ".banking");
        app.put("packageName", "com." + slug.replace("-", "") + ".banking");
        world.app = UUID.fromString(post(world.adminToken, "/api/v1/applications", app, 201)
                .get("id")
                .asText());
        for (UUID tenant : List.of(world.tenantA, world.tenantB, world.tenantC)) {
            post(world.adminToken, "/api/v1/applications/" + world.app + "/tenants/" + tenant, Map.of(), 201);
        }
        return world;
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

    private UUID createTenantApp(String token, UUID tenantId, String name, String bundleId) {
        Map<String, Object> body = new HashMap<>();
        body.put("tenantId", tenantId.toString());
        body.put("name", name);
        body.put("platforms", List.of("IOS", "ANDROID"));
        body.put("bundleId", bundleId);
        body.put("packageName", bundleId);
        return UUID.fromString(
                post(token, "/api/v1/applications", body, 201).get("id").asText());
    }

    private UUID createTenant(String token, UUID organizationId, String name, String externalId) {
        return UUID.fromString(post(
                        token,
                        "/api/v1/tenants",
                        Map.of("organizationId", organizationId.toString(), "name", name, "externalId", externalId),
                        201)
                .get("id")
                .asText());
    }

    private UUID userIdOf(String bearer) {
        return UUID.fromString(decode(bearer).get("sub").asText());
    }

    private JsonNode decode(String token) {
        String claims = token.split("\\.")[1];
        return mapper.readTree(Base64.getUrlDecoder().decode(claims));
    }

    /** Creates one organization per test; repeated calls for the same name are a fixture bug. */
    private UUID createOrg(String orgName) {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        return UUID.fromString(post(devToken, "/api/v1/organizations", Map.of("name", orgName), 201)
                .get("id")
                .asText());
    }

    /** Registers a user with the given role into an EXISTING organization; returns their token. */
    private String loginOfOrgRoleIn(UUID orgId, String slug, String role) {
        String email = slug + "@example.com";
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
                        "Relm " + role))
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

    private JsonNode transition(
            String bearer, UUID releaseId, UUID applicationId, String to, String reason, int expected) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", applicationId.toString());
        body.put("to", to);
        if (reason != null) {
            body.put("reason", reason);
        }
        return exchange("/api/v1/releases/" + releaseId + "/transitions", bearer, "POST", body)
                .expect(expected);
    }

    private JsonNode get(String bearer, String uri, int expectedStatus) {
        return exchange(uri, bearer, "GET", null).expect(expectedStatus);
    }

    private JsonNode post(String bearer, String uri, Map<String, Object> body, int expectedStatus) {
        return exchange(uri, bearer, "POST", body).expect(expectedStatus);
    }

    private JsonNode patch(String bearer, String uri, Map<String, Object> body, int expectedStatus) {
        return exchange(uri, bearer, "PATCH", body).expect(expectedStatus);
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
            String contentType = response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
            // Only JSON bodies parse into a tree; the YAML render rides
            // the raw text (its own reader is the Jackson 2 mapper).
            JsonNode parsed = bytes.length == 0 || (contentType != null && !contentType.contains("json"))
                    ? mapper.createObjectNode()
                    : mapper.readTree(bytes);
            return new Recording(
                    response.getStatusCode().value(), contentType, parsed, new String(bytes, StandardCharsets.UTF_8));
        });
    }

    private record Recording(int status, String contentType, JsonNode body, String text) {

        JsonNode expect(int expectedStatus) {
            assertThat(status).as("HTTP status for %s", body.toString()).isEqualTo(expectedStatus);
            return body;
        }
    }
}
