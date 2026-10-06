package com.shipperdog.api.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.config.MetricNames;
import com.shipperdog.api.identity.AuditCapture;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.HashMap;
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
 * End-to-end acceptance flow of the validation pipeline and the
 * artifact state machine (issue #24, S5-05, FR-006, PRD 18/42): a real
 * fixture binary uploads through the real API, the extraction tier
 * settles, the seven-stage pipeline runs on its own bounded executor,
 * and the outcome lands everywhere it must — the artifact read's
 * validation block (per-stage status/code/reason in FR-006 order),
 * the V18 {@code format} and {@code validation} columns, the version
 * views' extended artifact slots, the ARTIFACT_VALIDATED audit trail,
 * and the {@code shipperdog_artifact_validation_duration_seconds}
 * meter.
 *
 * <p>The failure matrix pins every deterministic stage failure
 * (renamed AAB at FORMAT, payload-fault extraction at METADATA,
 * unsigned and expired profiles at SIGNING, registry mismatch at
 * APPLICATION_MATCH, version and pinned-build mismatch at VERSION_
 * MATCH) with later stages SKIPPED, and the revalidation loop proves
 * the story's retry scope end to end: a FAILED artifact re-drives
 * through FAILED -> VALIDATING, deterministic stages reproduce their
 * verdict, a corrected world fact (the registry's identifier) wins on
 * the next attempt, and the attempts counter carries the history.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
@Import(AuditCapture.class)
class ArtifactValidationIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";

    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";

    private static final String PASSWORD = "a-perfectly-valid-password";

    /** The compose GCS emulator image (ADR-0003), in a container. */
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

    @Autowired
    private MeterRegistry meters;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        audit.reset();
    }

    // -----------------------------------------------------------------------
    // AC1 + AC2: the happy path lands READY with every stage PASSED
    // -----------------------------------------------------------------------

    @Test
    void ipaUploadValidatesToReadyWithAllStagesPassed() throws Exception {
        World world = world("av-happy");
        JsonNode response =
                upload(world.token(), world.versionId(), "IOS", "app.ipa", null, IpaFixtures.releaseIpa(), 201);
        UUID artifactId = UUID.fromString(response.get("id").asText());
        assertThat(response.get("validation").get("status").asText())
                .as("validation is async; the upload answer can only show PENDING")
                .isEqualTo("PENDING");

        JsonNode settled = awaitValidation(world, artifactId, "PASSED");
        assertThat(settled.get("status").asText()).isEqualTo("READY");
        assertThat(settled.get("format").asText()).isEqualTo("IPA");
        assertThat(settled.get("validation").get("attempts").asInt()).isEqualTo(1);
        assertThat(settled.get("validation").get("durationMs").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(stageNames(settled))
                .containsExactly(
                        "FILE", "FORMAT", "METADATA", "CHECKSUM", "SIGNING", "APPLICATION_MATCH", "VERSION_MATCH");
        assertThat(stageStatuses(settled)).containsOnly("PASSED");

        Map<String, Object> row =
                jdbc.queryForMap("SELECT status, format, validation FROM artifacts WHERE id = ?", artifactId);
        assertThat(row.get("status")).isEqualTo("READY");
        assertThat(row.get("format")).isEqualTo("IPA");
        JsonNode document = mapper.readTree(String.valueOf(row.get("validation")));
        assertThat(document.get("outcome").asText()).isEqualTo("PASSED");
        assertThat(document.get("attempts").asInt()).isEqualTo(1);
        assertThat(document.get("format").asText()).isEqualTo("IPA");
        assertThat(document.get("stages").size()).isEqualTo(7);

        JsonNode version = get(
                world.token(), "/api/v1/applications/" + world.applicationId() + "/versions/" + world.versionId(), 200);
        JsonNode slot = version.get("artifactsByPlatform").get("IOS").get(0);
        assertThat(slot.get("status").asText()).isEqualTo("READY");
        assertThat(slot.get("format").asText()).isEqualTo("IPA");
        assertThat(slot.get("validation").asText()).isEqualTo("PASSED");

        assertThat(audit.eventsOfType(AuditEventType.ARTIFACT_VALIDATED).stream()
                        .filter(event -> artifactId.equals(event.artifactId()))
                        .toList())
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.success()).isTrue();
                    assertThat(event.detail()).containsEntry("platform", "IOS");
                    assertThat(event.detail()).containsEntry("outcome", "PASSED");
                    assertThat(event.detail()).containsEntry("format", "IPA");
                    assertThat(event.detail()).containsKey("durationMs");
                });
        Timer timer = meters.get(MetricNames.ARTIFACT_VALIDATION_DURATION)
                .tag("platform", "IOS")
                .tag("outcome", "success")
                .timer();
        assertThat(timer.count()).isGreaterThan(0);
    }

    // -----------------------------------------------------------------------
    // AC2: FAILED artifacts list per-stage failure details; later stages
    // record SKIPPED
    // -----------------------------------------------------------------------

    @Test
    void applicationMismatchFailsWithTheExplicitMismatchError() throws Exception {
        World world = world("av-app-mismatch", "IOS", "com.other.registered");
        UUID artifactId = uploadIpa(world, IpaFixtures.releaseIpa());

        JsonNode settled = awaitValidation(world, artifactId, "FAILED");
        assertThat(settled.get("status").asText()).isEqualTo("FAILED");
        JsonNode failedStage = failedStage(settled);
        assertThat(failedStage.get("name").asText()).isEqualTo("APPLICATION_MATCH");
        assertThat(failedStage.get("code").asText()).isEqualTo("APPLICATION_MISMATCH");
        assertThat(failedStage.get("reason").asText())
                .contains(IpaFixtures.BUNDLE_ID)
                .contains("com.other.registered");
        assertThat(stageAfter(settled, "APPLICATION_MATCH").get("status").asText())
                .isEqualTo("SKIPPED");

        assertThat(audit.eventsOfType(AuditEventType.ARTIFACT_VALIDATED).stream()
                        .filter(event -> artifactId.equals(event.artifactId()))
                        .toList())
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.success()).isFalse();
                    assertThat(event.detail()).containsEntry("stage", "APPLICATION_MATCH");
                    assertThat(event.detail()).containsEntry("errorCode", "APPLICATION_MISMATCH");
                });
        assertThat(meters.get(MetricNames.ARTIFACT_VALIDATION_DURATION)
                        .tag("platform", "IOS")
                        .tag("outcome", "failed")
                        .timer()
                        .count())
                .isGreaterThan(0);
    }

    @Test
    void versionMismatchFailsAgainstTheVersionRecord() throws Exception {
        World world = worldWithVersionName("av-version-mismatch", "8.0.0");
        UUID artifactId = uploadIpa(world, IpaFixtures.releaseIpa());

        JsonNode settled = awaitValidation(world, artifactId, "FAILED");
        JsonNode failedStage = failedStage(settled);
        assertThat(failedStage.get("name").asText()).isEqualTo("VERSION_MATCH");
        assertThat(failedStage.get("code").asText()).isEqualTo("VERSION_MISMATCH");
        assertThat(failedStage.get("reason").asText()).contains("7.12.0").contains("8.0.0");
    }

    @Test
    void pinnedBuildMismatchFailsAgainstTheExtractedBuild() throws Exception {
        World world = world("av-pinned");
        JsonNode response =
                upload(world.token(), world.versionId(), "IOS", "app.ipa", 777L, IpaFixtures.releaseIpa(), 201);
        UUID artifactId = UUID.fromString(response.get("id").asText());

        JsonNode settled = awaitValidation(world, artifactId, "FAILED");
        JsonNode failedStage = failedStage(settled);
        assertThat(failedStage.get("name").asText()).isEqualTo("VERSION_MATCH");
        assertThat(failedStage.get("code").asText()).isEqualTo("VERSION_MISMATCH");
        assertThat(failedStage.get("reason").asText()).contains("777").contains("421");
    }

    // -----------------------------------------------------------------------
    // AC4: the channel-format fact — structural verdict beats the name
    // -----------------------------------------------------------------------

    @Test
    void aRenamedAabFailsFormatCoherenceAndRecordsTheStructuralTruth() throws Exception {
        World world = world("av-renamed-aab", "ANDROID", AndroidFixtures.PACKAGE);
        // An AAB's bytes uploaded under an .apk name: the upload is
        // accepted (both are ANDROID extensions), extraction succeeds
        // with the structural verdict AAB, and the FORMAT stage fails
        // the coherence check WHILE RECORDING the truth — the
        // channel-format rule (direct download refuses AAB) acts on
        // this recorded fact at distribution time.
        UUID artifactId = uploadAabNamedApk(world);

        JsonNode settled = awaitValidation(world, artifactId, "FAILED");
        assertThat(settled.get("status").asText()).isEqualTo("FAILED");
        assertThat(settled.get("format").asText()).isEqualTo("AAB");
        JsonNode failedStage = failedStage(settled);
        assertThat(failedStage.get("name").asText()).isEqualTo("FORMAT");
        assertThat(failedStage.get("code").asText()).isEqualTo("FORMAT_MISMATCH");
        assertThat(failedStage.get("reason").asText()).contains("APK").contains("AAB");
        assertThat(stageAfter(settled, "FORMAT").get("status").asText()).isEqualTo("SKIPPED");
        assertThat(jdbc.queryForObject("SELECT format FROM artifacts WHERE id = ?", String.class, artifactId))
                .isEqualTo("AAB");
    }

    @Test
    void aGenuineAabStaysValidAndReachesReady() throws Exception {
        World world = world("av-aab", "ANDROID", AndroidFixtures.PACKAGE);
        JsonNode response = upload(
                world.token(),
                world.versionId(),
                "ANDROID",
                "app.aab",
                null,
                AndroidFixtures.aabVersionCodeInManifest(),
                201);
        UUID artifactId = UUID.fromString(response.get("id").asText());

        JsonNode settled = awaitValidation(world, artifactId, "PASSED");
        assertThat(settled.get("status").asText()).isEqualTo("READY");
        assertThat(settled.get("format").asText()).isEqualTo("AAB");
        // The AAB's signing leg is the documented deferral, not a pass
        // in disguise: the stage reason says exactly what was checked.
        JsonNode signing = stageNamed(settled, "SIGNING");
        assertThat(signing.get("status").asText()).isEqualTo("PASSED");
        assertThat(signing.get("reason").asText()).contains("AAB");
    }

    @Test
    void anUnsignedApkFailsSigningAbsent() throws Exception {
        World world = world("av-unsigned-apk", "ANDROID", AndroidFixtures.PACKAGE);
        JsonNode response =
                upload(world.token(), world.versionId(), "ANDROID", "app.apk", null, AndroidFixtures.releaseApk(), 201);
        UUID artifactId = UUID.fromString(response.get("id").asText());

        JsonNode settled = awaitValidation(world, artifactId, "FAILED");
        JsonNode failedStage = failedStage(settled);
        assertThat(failedStage.get("name").asText()).isEqualTo("SIGNING");
        assertThat(failedStage.get("code").asText()).isEqualTo("SIGNING_ABSENT");
    }

    // -----------------------------------------------------------------------
    // SIGNING depth at M1: presence + self-consistency
    // -----------------------------------------------------------------------

    @Test
    void anUnsignedIpaFailsSigningAbsent() throws Exception {
        World world = world("av-unsigned");
        UUID artifactId = uploadIpa(world, IpaFixtures.unsignedIpa());

        JsonNode settled = awaitValidation(world, artifactId, "FAILED");
        JsonNode failedStage = failedStage(settled);
        assertThat(failedStage.get("name").asText()).isEqualTo("SIGNING");
        assertThat(failedStage.get("code").asText()).isEqualTo("SIGNING_ABSENT");
        assertThat(failedStage.get("reason").asText()).contains("provisioning profile");
    }

    @Test
    void anExpiredProfileFailsSigningExpired() throws Exception {
        World world = world("av-expired");
        UUID artifactId = uploadIpa(world, IpaFixtures.expiredProfileIpa());

        JsonNode settled = awaitValidation(world, artifactId, "FAILED");
        JsonNode failedStage = failedStage(settled);
        assertThat(failedStage.get("name").asText()).isEqualTo("SIGNING");
        assertThat(failedStage.get("code").asText()).isEqualTo("SIGNING_PROFILE_EXPIRED");
        assertThat(failedStage.get("reason").asText()).contains("2020-01-01");
    }

    // -----------------------------------------------------------------------
    // The two async tiers join: container faults fail FORMAT, payload
    // faults fail METADATA carrying the extraction's own code
    // -----------------------------------------------------------------------

    @Test
    void aContainerFaultFailsAtFormat() throws Exception {
        World world = world("av-container");
        UUID artifactId = uploadIpa(world, IpaFixtures.notAZip(4096));

        JsonNode settled = awaitValidation(world, artifactId, "FAILED");
        assertThat(settled.get("status").asText()).isEqualTo("FAILED");
        JsonNode failedStage = failedStage(settled);
        assertThat(failedStage.get("name").asText()).isEqualTo("FORMAT");
        assertThat(failedStage.get("code").asText()).isEqualTo("FORMAT_MISMATCH");
        assertThat(failedStage.get("reason").asText()).contains("NOT_A_ZIP");
        assertThat(stageAfter(settled, "FORMAT").get("status").asText()).isEqualTo("SKIPPED");
    }

    @Test
    void aPayloadFaultFailsAtMetadataCarryingTheExtractionCode() throws Exception {
        World world = world("av-payload");
        UUID artifactId = uploadIpa(world, IpaFixtures.missingPlistIpa());

        JsonNode settled = awaitValidation(world, artifactId, "FAILED");
        JsonNode format = stageNamed(settled, "FORMAT");
        assertThat(format.get("status").asText()).isEqualTo("PASSED");
        assertThat(settled.get("format").isNull()).isTrue();
        JsonNode failedStage = failedStage(settled);
        assertThat(failedStage.get("name").asText()).isEqualTo("METADATA");
        assertThat(failedStage.get("code").asText()).isEqualTo("METADATA_FAILED");
        assertThat(failedStage.get("reason").asText()).contains("MISSING_INFO_PLIST");
    }

    // -----------------------------------------------------------------------
    // AC (retry): the revalidation loop — determinism, then the
    // corrected world fact wins; READY refuses
    // -----------------------------------------------------------------------

    @Test
    void revalidationReproducesThenWinsWhenTheWorldIsCorrected() throws Exception {
        World world = world("av-revalidate", "IOS", "com.wrong.initial");
        UUID artifactId = uploadIpa(world, IpaFixtures.releaseIpa());

        // Attempt 1: the registry mismatch fails deterministically.
        awaitValidation(world, artifactId, "FAILED");
        assertThat(failedStageOfRow(artifactId).get("code").asText()).isEqualTo("APPLICATION_MISMATCH");

        // Attempt 2: a corrupted checksum row fails earlier still
        // (CHECKSUM runs before APPLICATION_MATCH) — proving the
        // pipeline re-reads the world, not a cached verdict.
        String trueSha = jdbc.queryForObject("SELECT sha256 FROM artifacts WHERE id = ?", String.class, artifactId);
        jdbc.update("UPDATE artifacts SET sha256 = ? WHERE id = ?", "f".repeat(64), artifactId);
        post(
                world.token(),
                "/api/v1/artifacts/" + artifactId + "/revalidate?version_id=" + world.versionId(),
                null,
                202);
        JsonNode second = awaitAttempts(world, artifactId, 2);
        assertThat(second.get("status").asText()).isEqualTo("FAILED");
        assertThat(second.get("status").asText()).isEqualTo("FAILED");
        assertThat(failedStage(second).get("code").asText()).isEqualTo("CHECKSUM_MISMATCH");

        // Attempt 3: the corrected world — the true checksum restored
        // and the registry's identifier fixed — wins. READY.
        jdbc.update("UPDATE artifacts SET sha256 = ? WHERE id = ?", trueSha, artifactId);
        Map<String, Object> application = new HashMap<>();
        application.put("bundleId", IpaFixtures.BUNDLE_ID);
        patch(world.adminToken(), "/api/v1/applications/" + world.applicationId(), application, 200);
        post(
                world.token(),
                "/api/v1/artifacts/" + artifactId + "/revalidate?version_id=" + world.versionId(),
                null,
                202);
        JsonNode third = awaitAttempts(world, artifactId, 3);
        assertThat(third.get("status").asText()).isEqualTo("READY");
        assertThat(third.get("validation").get("status").asText()).isEqualTo("PASSED");
        // Two revalidation acceptances (attempts 2 and 3); the refused
        // READY re-drive that follows audits nothing — it never ran.
        assertThat(audit.eventsOfType(AuditEventType.ARTIFACT_REVALIDATION_REQUESTED).stream()
                        .filter(event -> artifactId.equals(event.artifactId()))
                        .toList())
                .hasSize(2);

        // READY refuses revalidation: PRD 18 has no path back to
        // VALIDATING, and the machine is the authority.
        JsonNode refused = post(
                world.token(),
                "/api/v1/artifacts/" + artifactId + "/revalidate?version_id=" + world.versionId(),
                null,
                409);
        assertThat(refused.get("code").asText()).isEqualTo("ARTIFACT_NOT_VALIDATABLE");
    }

    @Test
    void revalidationConcealsForeignVersionsLikeEveryArtifactRoute() throws Exception {
        World world = world("av-conceal");
        World other = world("av-conceal-other");
        UUID artifactId = uploadIpa(world, IpaFixtures.releaseIpa());
        awaitValidation(world, artifactId, "PASSED");

        JsonNode foreign = post(
                world.token(),
                "/api/v1/artifacts/" + artifactId + "/revalidate?version_id=" + other.versionId(),
                null,
                404);
        assertThat(foreign.get("code").asText()).isEqualTo("VERSION_NOT_FOUND");
    }

    // -----------------------------------------------------------------------
    // Tester pass: the remaining deterministic stage branches
    // -----------------------------------------------------------------------

    @Test
    void aMissingStoredObjectFailsTheFileStage() throws Exception {
        World world = worldWithVersionName("av-file-missing", "8.0.0");
        UUID artifactId = uploadIpa(world, IpaFixtures.releaseIpa());
        awaitValidation(world, artifactId, "FAILED");

        // The registered object key no longer resolves in storage: the
        // FILE stage fails and nothing later runs.
        jdbc.update("UPDATE artifacts SET storage_key = ? WHERE id = ?", "gone/does/not/exist", artifactId);
        post(
                world.token(),
                "/api/v1/artifacts/" + artifactId + "/revalidate?version_id=" + world.versionId(),
                null,
                202);
        JsonNode settled = awaitAttempts(world, artifactId, 2);
        JsonNode failedStage = failedStage(settled);
        assertThat(failedStage.get("name").asText()).isEqualTo("FILE");
        assertThat(failedStage.get("code").asText()).isEqualTo("FILE_MISSING");
        assertThat(stageAfter(settled, "FILE").get("status").asText()).isEqualTo("SKIPPED");
    }

    @Test
    void aCorruptedSizeFailsTheFileStage() throws Exception {
        World world = worldWithVersionName("av-size-mismatch", "8.0.0");
        UUID artifactId = uploadIpa(world, IpaFixtures.releaseIpa());
        awaitValidation(world, artifactId, "FAILED");

        long size = jdbc.queryForObject("SELECT size FROM artifacts WHERE id = ?", Long.class, artifactId);
        jdbc.update("UPDATE artifacts SET size = ? WHERE id = ?", size + 1, artifactId);
        post(
                world.token(),
                "/api/v1/artifacts/" + artifactId + "/revalidate?version_id=" + world.versionId(),
                null,
                202);
        JsonNode settled = awaitAttempts(world, artifactId, 2);
        JsonNode failedStage = failedStage(settled);
        assertThat(failedStage.get("name").asText()).isEqualTo("FILE");
        assertThat(failedStage.get("code").asText()).isEqualTo("SIZE_MISMATCH");
        assertThat(failedStage.get("reason").asText()).contains(Long.toString(size + 1));
    }

    @Test
    void aProfileWithoutExpirationFailsSigningIncoherent() throws Exception {
        World world = world("av-incoherent");
        UUID artifactId = uploadIpa(world, IpaFixtures.incoherentProfileIpa());

        JsonNode settled = awaitValidation(world, artifactId, "FAILED");
        JsonNode failedStage = failedStage(settled);
        assertThat(failedStage.get("name").asText()).isEqualTo("SIGNING");
        assertThat(failedStage.get("code").asText()).isEqualTo("SIGNING_INCOHERENT");
        assertThat(failedStage.get("reason").asText()).contains("expiration");
    }

    @Test
    void aNonIntegerBuildHasNoNumericComparisonAndReachesReady() throws Exception {
        World world = world("av-nonnumeric");
        UUID artifactId = uploadIpa(world, IpaFixtures.nonNumericSignedIpa());

        JsonNode settled = awaitValidation(world, artifactId, "PASSED");
        assertThat(settled.get("status").asText()).isEqualTo("READY");
        assertThat(settled.get("buildNumber").isNull()).isTrue();
        assertThat(stageNamed(settled, "VERSION_MATCH").get("status").asText()).isEqualTo("PASSED");
    }

    @Test
    void revalidationReArmsABlockedExtractionAndTheTiersReenterInOrder() throws Exception {
        World world = worldWithVersionName("av-rearm", "8.0.0");
        UUID artifactId = uploadIpa(world, IpaFixtures.releaseIpa());
        awaitValidation(world, artifactId, "FAILED");

        // Forge the infra-blocked extraction shape (the state a
        // queue-full or storage-unavailable extraction leaves): the
        // revalidation surface must clear it, re-run the PARSER (the
        // bytes are real), and only then re-enter validation.
        String blockedDocument = "{\"code\":\"EXTRACTION_UNAVAILABLE\",\"message\":\"storage was unreadable\"}";
        jdbc.update(
                "UPDATE artifacts SET status = 'VALIDATING', extracted_metadata = NULL, "
                        + "extraction_error = ?::jsonb, validation = NULL WHERE id = ?",
                blockedDocument,
                artifactId);
        post(
                world.token(),
                "/api/v1/artifacts/" + artifactId + "/revalidate?version_id=" + world.versionId(),
                null,
                202);

        long deadline = System.currentTimeMillis() + 20_000;
        JsonNode settled = null;
        while (System.currentTimeMillis() < deadline) {
            settled = get(world.token(), "/api/v1/artifacts/" + artifactId + "?version_id=" + world.versionId(), 200);
            if ("SUCCESS".equals(settled.get("extraction").get("status").asText())
                    && "FAILED".equals(settled.get("validation").get("status").asText())) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(settled.get("extraction").get("status").asText()).isEqualTo("SUCCESS");
        assertThat(settled.get("status").asText()).isEqualTo("FAILED");
        assertThat(settled.get("validation").get("attempts").asInt()).isEqualTo(1);
        assertThat(failedStage(settled).get("code").asText()).isEqualTo("VERSION_MISMATCH");
        assertThat(jdbc.queryForObject("SELECT extraction_error FROM artifacts WHERE id = ?", String.class, artifactId))
                .isNull();
    }

    // -----------------------------------------------------------------------
    // Harness
    // -----------------------------------------------------------------------

    /** Awaits attempt N's document: a re-drive's verdict must not be the stale attempt N-1's. */
    private JsonNode awaitAttempts(World world, UUID artifactId, int attempts) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        JsonNode last = null;
        while (System.currentTimeMillis() < deadline) {
            last = get(world.token(), "/api/v1/artifacts/" + artifactId + "?version_id=" + world.versionId(), 200);
            JsonNode validation = last.get("validation");
            if (validation.has("attempts")
                    && !validation.get("attempts").isNull()
                    && validation.get("attempts").asInt() >= attempts) {
                return last;
            }
            Thread.sleep(100);
        }
        return last;
    }

    private JsonNode awaitValidation(World world, UUID artifactId, String outcome) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        JsonNode last = null;
        while (System.currentTimeMillis() < deadline) {
            last = get(world.token(), "/api/v1/artifacts/" + artifactId + "?version_id=" + world.versionId(), 200);
            if (outcome.equals(last.get("validation").get("status").asText())) {
                return last;
            }
            Thread.sleep(100);
        }
        return last;
    }

    private UUID uploadIpa(World world, byte[] bytes) {
        return UUID.fromString(upload(world.token(), world.versionId(), "IOS", "app.ipa", null, bytes, 201)
                .get("id")
                .asText());
    }

    private UUID uploadAabNamedApk(World world) throws java.io.IOException {
        return UUID.fromString(upload(
                        world.token(),
                        world.versionId(),
                        "ANDROID",
                        "app.apk",
                        null,
                        AndroidFixtures.aabVersionCodeInManifest(),
                        201)
                .get("id")
                .asText());
    }

    private List<String> stageNames(JsonNode artifact) {
        List<String> names = new java.util.ArrayList<>();
        for (JsonNode stage : stageList(artifact)) {
            names.add(stage.get("name").asText());
        }
        return names;
    }

    private List<String> stageStatuses(JsonNode artifact) {
        List<String> statuses = new java.util.ArrayList<>();
        for (JsonNode stage : stageList(artifact)) {
            statuses.add(stage.get("status").asText());
        }
        return statuses;
    }

    private JsonNode stageList(JsonNode artifact) {
        return artifact.get("validation").get("stages");
    }

    private JsonNode stageNamed(JsonNode artifact, String name) {
        for (JsonNode stage : stageList(artifact)) {
            if (name.equals(stage.get("name").asText())) {
                return stage;
            }
        }
        throw new AssertionError("no stage " + name + " recorded");
    }

    private JsonNode stageAfter(JsonNode artifact, String name) {
        boolean found = false;
        for (JsonNode stage : stageList(artifact)) {
            if (found) {
                return stage;
            }
            found = name.equals(stage.get("name").asText());
        }
        throw new AssertionError("no stage after " + name + " recorded");
    }

    private JsonNode failedStage(JsonNode artifact) {
        for (JsonNode stage : stageList(artifact)) {
            if ("FAILED".equals(stage.get("status").asText())) {
                return stage;
            }
        }
        throw new AssertionError("no FAILED stage recorded for a FAILED artifact");
    }

    private JsonNode failedStageOfRow(UUID artifactId) throws Exception {
        String document =
                jdbc.queryForObject("SELECT validation FROM artifacts WHERE id = ?", String.class, artifactId);
        JsonNode stages = mapper.readTree(document).get("stages");
        for (JsonNode stage : stages) {
            if ("FAILED".equals(stage.get("status").asText())) {
                return stage;
            }
        }
        throw new AssertionError("no FAILED stage in the stored document");
    }

    /** One organization, tenant, application, and version; a DEVELOPER token plus the org admin's. */
    private World world(String suffix) {
        return world(suffix, "IOS", IpaFixtures.BUNDLE_ID);
    }

    /** The identifier-aware world: the registry keys bundleId or packageName by platform (S3-01). */
    private World world(String suffix, String platform, String registeredIdentifier) {
        return world(suffix, platform, registeredIdentifier, IpaFixtures.VERSION_NAME);
    }

    /** The version-name-aware world (the VERSION_MATCH fixture). */
    private World worldWithVersionName(String suffix, String versionName) {
        return world(suffix, "IOS", IpaFixtures.BUNDLE_ID, versionName);
    }

    private World world(String suffix, String platform, String registeredIdentifier, String versionName) {
        String adminToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        UUID organizationId =
                UUID.fromString(post(adminToken, "/api/v1/organizations", Map.of("name", "validation-" + suffix), 201)
                        .get("id")
                        .asText());
        UUID tenantId = UUID.fromString(post(
                        adminToken,
                        "/api/v1/tenants",
                        Map.of(
                                "organizationId", organizationId.toString(),
                                "name", "Validation " + suffix,
                                "externalId", "ext-" + suffix),
                        201)
                .get("id")
                .asText());
        Map<String, Object> application = new HashMap<>(Map.of(
                "tenantId", tenantId.toString(),
                "name", "Banking " + suffix,
                "platforms", List.of(platform)));
        application.put("ANDROID".equals(platform) ? "packageName" : "bundleId", registeredIdentifier);
        UUID applicationId = UUID.fromString(post(adminToken, "/api/v1/applications", application, 201)
                .get("id")
                .asText());
        UUID versionId = UUID.fromString(post(
                        adminToken,
                        "/api/v1/applications/" + applicationId + "/versions",
                        Map.of("versionName", versionName),
                        201)
                .get("id")
                .asText());
        String email = "dev-" + suffix + "@shipperdog.test";
        String developerToken = loginOfOrgRoleIn(adminToken, organizationId, email, "DEVELOPER");
        return new World(developerToken, adminToken, organizationId, tenantId, applicationId, versionId);
    }

    private JsonNode upload(
            String token,
            UUID versionId,
            String platform,
            String fileName,
            Long buildNumber,
            byte[] bytes,
            int expected) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("version_id", versionId.toString());
        parts.add("platform", platform);
        if (buildNumber != null) {
            parts.add("build_number", Long.toString(buildNumber));
        }
        parts.add("file", new org.springframework.core.io.ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        return client.post()
                .uri("/api/v1/artifacts")
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(expected);
                    return mapper.readTree(response.getBody().readAllBytes());
                });
    }

    /** Registers a user with the given role into an EXISTING organization; returns their token. */
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
                        invitation.get("invitationToken").asText(),
                        "email",
                        email,
                        "password",
                        PASSWORD,
                        "fullName",
                        "Validation " + role))
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
        return mapper.readTree(tokensJson).get("accessToken").asText();
    }

    private JsonNode post(String bearer, String uri, Map<String, Object> body, int expectedStatus) {
        RestClient.RequestBodySpec spec = client.post().uri(uri).headers(headers -> headers.setBearerAuth(bearer));
        if (body != null) {
            spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((request, response) -> {
            assertThat(response.getStatusCode().value()).as("POST %s", uri).isEqualTo(expectedStatus);
            byte[] bytes = response.getBody().readAllBytes();
            return bytes.length == 0 ? mapper.createObjectNode() : mapper.readTree(bytes);
        });
    }

    private JsonNode patch(String bearer, String uri, Map<String, Object> body, int expectedStatus) {
        return client.patch()
                .uri(uri)
                .headers(headers -> headers.setBearerAuth(bearer))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode().value())
                            .as("PATCH %s", uri)
                            .isEqualTo(expectedStatus);
                    return mapper.readTree(response.getBody().readAllBytes());
                });
    }

    private JsonNode get(String bearer, String uri, int expectedStatus) {
        return client.get()
                .uri(uri)
                .headers(headers -> headers.setBearerAuth(bearer))
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode().value())
                            .as("GET %s", uri)
                            .isEqualTo(expectedStatus);
                    byte[] bytes = response.getBody().readAllBytes();
                    return bytes.length == 0 ? mapper.createObjectNode() : mapper.readTree(bytes);
                });
    }

    private record World(
            String token, String adminToken, UUID organizationId, UUID tenantId, UUID applicationId, UUID versionId) {}
}
