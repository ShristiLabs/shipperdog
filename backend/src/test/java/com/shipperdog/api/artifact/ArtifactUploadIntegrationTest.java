package com.shipperdog.api.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.config.MetricNames;
import com.shipperdog.api.identity.AuditCapture;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.net.URI;
import java.security.MessageDigest;
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
 * End-to-end acceptance flow for artifact upload and object storage
 * (issue #20, S5-01, FR-004/FR-007, PRD 24, INV-003): the multipart
 * path storing bytes in object storage under the PRD 24 key with
 * metadata-only rows in PostgreSQL (AC1/AC2), INV-003's no-replace
 * discipline where a re-upload is a NEW artifact with its identical
 * checksum surfaced informationally (AC3/AC4), the resumable
 * direct-to-storage path for large binaries with its short-lived
 * session and finalize (AC5), the ARTIFACT_UPLOADED audit trail
 * (AC6), and the upload-duration metric (AC7).
 *
 * <p>The object-storage target is the compose GCS emulator image
 * (fsouza/fake-gcs-server 1.56.1) in a Testcontainer — the same
 * emulator docker compose has run since issue #1, now exercised for
 * real: streamed writes, the resumable-session grant, and the
 * hash-back read all go through the GCS client against it (ADR-0003).
 * The resumable threshold is dialed down to 4 KiB for the suite, so
 * the >threshold-rejects-multipart rule (AC5's enforcement leg) tests
 * with kilobytes instead of a hundred megabytes.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
@Import(AuditCapture.class)
class ArtifactUploadIntegrationTest {

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
        // The >threshold rule points at the session path; 4 KiB keeps
        // the suite's payloads small while exercising the real gate.
        registry.add("shipperdog.artifact.upload.resumable-threshold-bytes", () -> "4096");
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

    @Autowired
    private ArtifactStore store;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        audit.reset();
    }

    // -----------------------------------------------------------------------
    // AC1 + AC2: multipart upload lands the binary at the PRD 24 key and
    // only metadata in PostgreSQL
    // -----------------------------------------------------------------------

    @Test
    void multipartUploadStoresBinaryInObjectStorageAndMetadataInPostgres() throws Exception {
        Uploaded uploaded = uploadWorld("au-store");
        byte[] bytes = uploaded.bytes();
        UUID appId = uploaded.appId();
        UUID versionId = uploaded.versionId();
        UUID orgId = uploaded.orgId();
        UUID tenantId = uploaded.tenantId();

        JsonNode artifact = uploadMultipart(uploaded.token(), versionId, "IOS", "bank.ipa", null, bytes, 201);
        UUID artifactId = UUID.fromString(artifact.get("id").asText());

        // The row is metadata only: checksum, size, uploader, status.
        assertThat(artifact.get("sha256").asText()).isEqualTo(sha256Of(bytes));
        assertThat(artifact.get("size").asLong()).isEqualTo(bytes.length);
        assertThat(artifact.get("platform").asText()).isEqualTo("IOS");
        assertThat(artifact.get("fileName").asText()).isEqualTo("bank.ipa");
        assertThat(artifact.get("status").asText()).isEqualTo("VALIDATING");
        assertThat(artifact.get("uploadedBy").asText())
                .isEqualTo(userIdByEmail(uploaded.email()).toString());

        // The PRD 24 key: /{orgId}/{tenantId}/{appId}/{versionId}/{artifactId}/artifact
        String expectedKey = "%s/%s/%s/%s/%s/artifact".formatted(orgId, tenantId, appId, versionId, artifactId);
        assertThat(artifact.get("storageKey").asText()).isEqualTo(expectedKey);

        // The bytes are in object storage at that key, and nothing
        // binary is in PostgreSQL (FR-007): the row count is one and
        // the object hashes back to the same checksum.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM artifacts WHERE id = ?", Integer.class, artifactId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT storage_key FROM artifacts WHERE id = ?", String.class, artifactId))
                .isEqualTo(expectedKey);
        ArtifactStore.StoredChecksum stored = store.hash(expectedKey).orElseThrow();
        assertThat(stored.sha256Hex()).isEqualTo(sha256Of(bytes));
        assertThat(stored.size()).isEqualTo(bytes.length);

        // The version surfaces the artifact: per-platform slot (the #17
        // promise E5 fills) and the history read's chronological list
        // (the #19 promise E5 fills).
        JsonNode detail = get(uploaded.token(), versionUri(appId, versionId), 200);
        JsonNode slots = detail.get("artifactsByPlatform").get("IOS");
        assertThat(slots.size()).isEqualTo(1);
        assertThat(slots.get(0).get("id").asText()).isEqualTo(artifactId.toString());
        assertThat(slots.get(0).get("status").asText()).isEqualTo("VALIDATING");
        JsonNode history =
                get(uploaded.token(), historyUri(appId, versionId), 200).get("artifacts");
        assertThat(history.size()).isEqualTo(1);
        assertThat(history.get(0).get("sha256").asText()).isEqualTo(sha256Of(bytes));
        assertThat(detail.get("artifactsByPlatform").get("ANDROID").size()).isZero();
    }

    // -----------------------------------------------------------------------
    // AC1: the allowlist gate rejects before any byte is stored
    // -----------------------------------------------------------------------

    @Test
    void unknownExtensionsAndPlatformMismatchesRejectPreStorage() throws Exception {
        Uploaded uploaded = uploadWorld("au-allow");

        Recording zip = uploadMultipartRaw(
                uploaded.token(), uploaded.versionId(), "IOS", "notes.zip", null, payload(256), null);
        assertThat(zip.status).isEqualTo(415);
        assertThat(zip.body.get("code").asText()).isEqualTo("ARTIFACT_FILE_TYPE_REJECTED");

        Recording noExtension =
                uploadMultipartRaw(uploaded.token(), uploaded.versionId(), "IOS", "bank", null, payload(256), null);
        assertThat(noExtension.status).isEqualTo(415);

        // An .ipa is iOS; uploading it as ANDROID is incoherent.
        Recording mismatch = uploadMultipartRaw(
                uploaded.token(), uploaded.versionId(), "ANDROID", "bank.ipa", null, payload(256), null);
        assertThat(mismatch.status).isEqualTo(415);

        // A platform the application never registered: this world's
        // app is iOS-only.
        UUID orgId = createOrg("Au Allow IOS Corp");
        String token = loginOfOrgRoleIn(orgId, "au-allow-ios-owner@example.com", "APP_OWNER");
        String adminToken = loginOfOrgRoleIn(orgId, "au-allow-ios-admin@example.com", "ORG_ADMIN");
        String iosDevToken = loginOfOrgRoleIn(orgId, "au-allow-ios-dev@example.com", "DEVELOPER");
        UUID tenantId = createTenant(adminToken, orgId, "Au Allow IOS Bank", "crm-au-allow-ios");
        UUID iosOnlyApp = createTenantApp(token, tenantId, "Au Allow IOS App", "com.au.allowios", List.of("IOS"));
        UUID iosOnlyVersion = createVersion(token, iosOnlyApp, "1.0.0");
        Recording unregistered =
                uploadMultipartRaw(iosDevToken, iosOnlyVersion, "ANDROID", "bank.apk", null, payload(256), null);
        assertThat(unregistered.status).isEqualTo(400);

        // An empty file is a broken request, not an artifact.
        Recording empty =
                uploadMultipartRaw(uploaded.token(), uploaded.versionId(), "IOS", "bank.ipa", null, new byte[0], null);
        assertThat(empty.status).isEqualTo(400);

        // Nothing was stored anywhere (pre-storage means pre-storage).
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM artifacts WHERE version_id = ?", Integer.class, uploaded.versionId()))
                .isZero();
    }

    // -----------------------------------------------------------------------
    // AC3 + AC4: re-upload is a NEW artifact; identical content is
    // detected and surfaced, never rejected
    // -----------------------------------------------------------------------

    @Test
    void reUploadCreatesNewArtifactAndSurfacesDuplicateInformationally() throws Exception {
        Uploaded uploaded = uploadWorld("au-dup");
        byte[] bytes = payload(1024);
        UUID appId = uploaded.appId();

        JsonNode first = uploadMultipart(uploaded.token(), uploaded.versionId(), "IOS", "one.ipa", null, bytes, 201);
        JsonNode second =
                uploadMultipart(uploaded.token(), uploaded.versionId(), "IOS", "one-again.ipa", null, bytes, 201);

        // INV-003: distinct rows, distinct ids, distinct keys — same bytes.
        assertThat(first.get("id").asText()).isNotEqualTo(second.get("id").asText());
        assertThat(first.get("storageKey").asText())
                .isNotEqualTo(second.get("storageKey").asText());
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM artifacts WHERE version_id = ?", Integer.class, uploaded.versionId()))
                .isEqualTo(2);

        // The second upload knows about the first: informational, in
        // the response, with no rejection anywhere (AC4).
        assertThat(second.get("duplicateOf").size()).isEqualTo(1);
        assertThat(second.get("duplicateOf").get(0).get("artifactId").asText())
                .isEqualTo(first.get("id").asText());
        assertThat(first.get("duplicateOf").size()).isZero();

        // Both objects still hash to the same content — the first
        // artifact's bytes were never touched.
        assertThat(store.hash(first.get("storageKey").asText()).orElseThrow().sha256Hex())
                .isEqualTo(sha256Of(bytes));

        // The detection spans the application: the same binary under a
        // sibling version is still the same binary.
        UUID otherVersion = createVersion(uploaded.ownerToken(), appId, "2.0.0");
        JsonNode crossVersion = uploadMultipart(uploaded.token(), otherVersion, "IOS", "one.ipa", null, bytes, 201);
        assertThat(crossVersion.get("duplicateOf").size()).isEqualTo(2);
    }

    // -----------------------------------------------------------------------
    // The parked constraint decision: the V1 composite
    // (version, platform, build) is the authority
    // -----------------------------------------------------------------------

    @Test
    void pinnedBuildTriplesLandOnceAndAnswerThePreciseConflict() throws Exception {
        Uploaded uploaded = uploadWorld("au-build");
        byte[] bytes = payload(512);

        uploadMultipart(uploaded.token(), uploaded.versionId(), "IOS", "b7.ipa", 7L, bytes, 201);

        // The same pinned build cannot land twice — that is a replace,
        // and INV-003 refuses it.
        Recording repeat = uploadMultipartRaw(uploaded.token(), uploaded.versionId(), "IOS", "b7.ipa", 7L, bytes, null);
        assertThat(repeat.status).isEqualTo(409);
        assertThat(repeat.body.get("code").asText()).isEqualTo("ARTIFACT_BUILD_TAKEN");

        // A different build of the same platform is a new artifact.
        uploadMultipart(uploaded.token(), uploaded.versionId(), "IOS", "b8.ipa", 8L, bytes, 201);
        // A negative build number is a broken request.
        assertThat(uploadMultipartRaw(uploaded.token(), uploaded.versionId(), "IOS", "b0.ipa", -1L, bytes, null).status)
                .isEqualTo(400);
    }

    // -----------------------------------------------------------------------
    // AC5: the resumable direct-to-storage path
    // -----------------------------------------------------------------------

    @Test
    void largeUploadsUseTheResumableSessionPath() throws Exception {
        Uploaded uploaded = uploadWorld("au-big");
        byte[] bytes = payload(8192);

        // Above the threshold, the multipart path refuses with the 413
        // that points at the session route — the >100 MB rule is
        // enforced, not advisory (the response carries the stable
        // code-level detail; the route pointer lives in the API docs).
        Recording tooBig =
                uploadMultipartRaw(uploaded.token(), uploaded.versionId(), "IOS", "big.ipa", null, bytes, null);
        assertThat(tooBig.status).isEqualTo(413);
        assertThat(tooBig.body.get("code").asText()).isEqualTo("ARTIFACT_TOO_LARGE");

        // The session path carries the same bytes.
        JsonNode session = createSession(uploaded.token(), uploaded.versionId(), "IOS", "big.ipa", null, 201);
        assertThat(session.hasNonNull("uploadUrl")).isTrue();
        assertThat(session.hasNonNull("expiresAt")).isTrue();
        UUID artifactId = UUID.fromString(session.get("artifactId").asText());

        Recording push = pushBytes(session.get("uploadUrl").asText(), bytes);
        assertThat(push.status).as("the client streams bytes direct to storage").isEqualTo(200);

        JsonNode finalize = post(
                uploaded.token(),
                finalizeUri(artifactId),
                Map.of("versionId", uploaded.versionId().toString()),
                201);
        assertThat(finalize.get("created").asBoolean()).isTrue();
        assertThat(finalize.get("artifact").get("sha256").asText()).isEqualTo(sha256Of(bytes));
        assertThat(finalize.get("artifact").get("size").asLong()).isEqualTo(bytes.length);

        // The session is gone; the artifact remains.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM artifact_upload_sessions WHERE artifact_id = ?",
                        Integer.class,
                        artifactId))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM artifacts WHERE id = ?", Integer.class, artifactId))
                .isEqualTo(1);
    }

    @Test
    void finalizeGuardsExpiryEmptySessionsAndReplays() throws Exception {
        Uploaded uploaded = uploadWorld("au-fin");
        byte[] bytes = payload(1024);

        // Finalize before the client stored anything: broken request.
        JsonNode empty = createSession(uploaded.token(), uploaded.versionId(), "IOS", "e.ipa", null, 201);
        post(
                uploaded.token(),
                finalizeUri(UUID.fromString(empty.get("artifactId").asText())),
                Map.of("versionId", uploaded.versionId().toString()),
                400);

        // Expired sessions answer 410 (the TTL is real, AC5's
        // "short-lived" is enforced).
        JsonNode expired = createSession(uploaded.token(), uploaded.versionId(), "IOS", "x.ipa", null, 201);
        jdbc.update(
                "UPDATE artifact_upload_sessions SET expires_at = now() - interval '1 minute' WHERE artifact_id = ?",
                UUID.fromString(expired.get("artifactId").asText()));
        Recording gone = exchange(
                finalizeUri(UUID.fromString(expired.get("artifactId").asText())),
                uploaded.token(),
                "POST",
                Map.of("versionId", uploaded.versionId().toString()));
        assertThat(gone.status).isEqualTo(410);
        assertThat(gone.body.get("code").asText()).isEqualTo("UPLOAD_SESSION_EXPIRED");

        // A complete finalize, then its retry: the terminal state is
        // the replay answer — same artifact, created=false, 200.
        JsonNode session = createSession(uploaded.token(), uploaded.versionId(), "IOS", "ok.ipa", null, 201);
        UUID artifactId = UUID.fromString(session.get("artifactId").asText());
        pushBytes(session.get("uploadUrl").asText(), bytes);
        post(
                uploaded.token(),
                finalizeUri(artifactId),
                Map.of("versionId", uploaded.versionId().toString()),
                201);

        JsonNode replay = post(
                uploaded.token(),
                finalizeUri(artifactId),
                Map.of("versionId", uploaded.versionId().toString()),
                200);
        assertThat(replay.get("created").asBoolean()).isFalse();
        assertThat(replay.get("artifact").get("id").asText()).isEqualTo(artifactId.toString());

        // A version mismatch on finalize is the concealed miss, as is
        // an unknown artifact.
        assertThat(exchange(
                                finalizeUri(artifactId),
                                uploaded.token(),
                                "POST",
                                Map.of(
                                        "versionId",
                                        createVersion(uploaded.ownerToken(), uploaded.appId(), "9.9.9")
                                                .toString()))
                        .status)
                .isEqualTo(404);
        assertThat(exchange(
                                finalizeUri(UUID.randomUUID()),
                                uploaded.token(),
                                "POST",
                                Map.of("versionId", uploaded.versionId().toString()))
                        .status)
                .isEqualTo(404);
    }

    // -----------------------------------------------------------------------
    // The #19 wiring: no artifacts onto frozen versions; the archived
    // application guard; concealment
    // -----------------------------------------------------------------------

    @Test
    void frozenVersionsAndArchivedApplicationsRefuseUploads() throws Exception {
        Uploaded uploaded = uploadWorld("au-guard");
        byte[] bytes = payload(256);

        post(uploaded.ownerToken(), archiveUri(uploaded.appId(), uploaded.versionId()), Map.of(), 200);
        Recording frozen =
                uploadMultipartRaw(uploaded.token(), uploaded.versionId(), "IOS", "f.ipa", null, bytes, null);
        assertThat(frozen.status).isEqualTo(409);
        assertThat(frozen.body.get("code").asText()).isEqualTo("VERSION_IMMUTABLE");

        // The session path carries the same freeze (and re-checks at
        // finalize — the window between is the revoke window).
        UUID live = createVersion(uploaded.ownerToken(), uploaded.appId(), "1.1.0");
        JsonNode session = createSession(uploaded.token(), live, "IOS", "g.ipa", null, 201);
        post(uploaded.ownerToken(), revokeUri(uploaded.appId(), live), Map.of("reason", "bad build"), 200);
        assertThat(exchange(
                                finalizeUri(UUID.fromString(
                                        session.get("artifactId").asText())),
                                uploaded.token(),
                                "POST",
                                Map.of("versionId", live.toString()))
                        .status)
                .isEqualTo(409);

        // Archived applications take no new artifacts — the code issue
        // #13 reserved for exactly this surface.
        Uploaded archived = uploadWorld("au-arch");
        jdbc.update("UPDATE applications SET status = 'ARCHIVED' WHERE id = ?", archived.appId());
        Recording refused =
                uploadMultipartRaw(archived.token(), archived.versionId(), "IOS", "a.ipa", null, bytes, null);
        assertThat(refused.status).isEqualTo(409);
        assertThat(refused.body.get("code").asText()).isEqualTo("APPLICATION_ARCHIVED");

        // Unknown versions conceal (the permission check denies them
        // before the service runs); unauthenticated never arrives.
        assertThat(uploadMultipartRaw(uploaded.token(), UUID.randomUUID(), "IOS", "u.ipa", null, bytes, null).status)
                .isEqualTo(404);
        assertThat(uploadMultipartRaw(null, uploaded.versionId(), "IOS", "u.ipa", null, bytes, null).status)
                .isEqualTo(401);
    }

    // -----------------------------------------------------------------------
    // AC6 + AC7: the audit trail and the duration metric
    // -----------------------------------------------------------------------

    @Test
    void uploadsAreAuditedAndTimed() throws Exception {
        Uploaded uploaded = uploadWorld("au-obs");
        byte[] bytes = payload(1024);

        JsonNode artifact = uploadMultipart(uploaded.token(), uploaded.versionId(), "IOS", "obs.ipa", null, bytes, 201);
        UUID artifactId = UUID.fromString(artifact.get("id").asText());

        List<AuditEvent> events = audit.eventsOfType(AuditEventType.ARTIFACT_UPLOADED);
        assertThat(events).hasSize(1);
        AuditEvent event = events.get(0);
        assertThat(event.userId()).isEqualTo(userIdByEmail(uploaded.email()));
        assertThat(event.versionId()).isEqualTo(uploaded.versionId());
        assertThat(event.artifactId()).isEqualTo(artifactId);
        assertThat(event.tenantId()).isEqualTo(uploaded.tenantId());
        assertThat(event.detail()).containsEntry("platform", "IOS");
        assertThat(event.detail()).containsEntry("size", Long.toString(bytes.length));
        assertThat(event.detail()).containsEntry("sha256", sha256Of(bytes));
        assertThat(event.detail()).containsEntry("path", "multipart");

        // The resumable path audits both the session grant and the
        // registered artifact with its own path tag.
        JsonNode session = createSession(uploaded.token(), uploaded.versionId(), "IOS", "r.ipa", null, 201);
        assertThat(audit.eventsOfType(AuditEventType.ARTIFACT_UPLOAD_SESSION_CREATED))
                .hasSize(1);
        pushBytes(session.get("uploadUrl").asText(), bytes);
        post(
                uploaded.token(),
                finalizeUri(UUID.fromString(session.get("artifactId").asText())),
                Map.of("versionId", uploaded.versionId().toString()),
                201);
        assertThat(audit.eventsOfType(AuditEventType.ARTIFACT_UPLOADED)).hasSize(2);
        assertThat(audit.eventsOfType(AuditEventType.ARTIFACT_UPLOADED).get(1).detail())
                .containsEntry("path", "resumable");

        // AC7: artifact_upload_duration, both paths, success legs.
        Timer multipart = meters.find(MetricNames.ARTIFACT_UPLOAD_DURATION)
                .tag("path", "multipart")
                .tag("outcome", "success")
                .timer();
        Timer resumable = meters.find(MetricNames.ARTIFACT_UPLOAD_DURATION)
                .tag("path", "resumable")
                .tag("outcome", "success")
                .timer();
        assertThat(multipart).isNotNull();
        assertThat(multipart.count()).isGreaterThanOrEqualTo(1);
        assertThat(resumable).isNotNull();
        assertThat(resumable.count()).isGreaterThanOrEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // Permissions: upload is the DEVELOPER permission; read-only roles
    // conceal
    // -----------------------------------------------------------------------

    @Test
    void uploadPermissionResolvesThroughTheVersionTarget() throws Exception {
        Uploaded uploaded = uploadWorld("au-perm");
        byte[] bytes = payload(256);

        // DEVELOPER holds app:artifacts:upload — the story's user.
        String developerToken = loginOfOrgRoleIn(uploaded.orgId(), "au-perm-dev@example.com", "DEVELOPER");
        JsonNode asDeveloper = uploadMultipart(developerToken, uploaded.versionId(), "IOS", "d.ipa", null, bytes, 201);
        assertThat(asDeveloper.get("status").asText()).isEqualTo("VALIDATING");

        // APP_OWNER reads artifacts but cannot upload; VIEWER neither —
        // both conceal exactly like an unknown version.
        String ownerToken = loginOfOrgRoleIn(uploaded.orgId(), "au-perm-owner@example.com", "APP_OWNER");
        String viewerToken = loginOfOrgRoleIn(uploaded.orgId(), "au-perm-viewer@example.com", "VIEWER");
        assertThat(uploadMultipartRaw(ownerToken, uploaded.versionId(), "IOS", "o.ipa", null, bytes, null).status)
                .isEqualTo(404);
        assertThat(uploadMultipartRaw(viewerToken, uploaded.versionId(), "IOS", "v.ipa", null, bytes, null).status)
                .isEqualTo(404);
        assertThat(exchange(
                                "/api/v1/artifacts/upload-sessions",
                                ownerToken,
                                "POST",
                                Map.of(
                                        "versionId",
                                        uploaded.versionId().toString(),
                                        "platform",
                                        "IOS",
                                        "fileName",
                                        "o.ipa"))
                        .status)
                .isEqualTo(404);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * The world every upload test starts from: one org, one tenant app
     * (both platforms), one DRAFT version. The APP_OWNER builds the
     * world (applications and versions are write-permissioned); the
     * DEVELOPER is the story's uploader — the one role holding
     * {@code app:artifacts:upload}. The bundle identifier strips the
     * suffix's hyphens — reverse-DNS segments carry letters, digits,
     * and underscores only.
     */
    private record Uploaded(
            UUID orgId,
            UUID tenantId,
            UUID appId,
            UUID versionId,
            String ownerToken,
            String ownerEmail,
            String uploaderToken,
            String uploaderEmail,
            byte[] bytes) {

        /** The uploader's token: every artifact-surface call uses it. */
        String token() {
            return uploaderToken;
        }

        /** The uploader's email: the audit trail's actor. */
        String email() {
            return uploaderEmail;
        }
    }

    private Uploaded uploadWorld(String suffix) {
        UUID orgId = createOrg("Au " + suffix + " Corp");
        String ownerEmail = "au-" + suffix + "-owner@example.com";
        String ownerToken = loginOfOrgRoleIn(orgId, ownerEmail, "APP_OWNER");
        String adminToken = loginOfOrgRoleIn(orgId, "au-" + suffix + "-admin@example.com", "ORG_ADMIN");
        String uploaderEmail = "au-" + suffix + "-dev@example.com";
        String uploaderToken = loginOfOrgRoleIn(orgId, uploaderEmail, "DEVELOPER");
        UUID tenantId = createTenant(adminToken, orgId, "Au " + suffix + " Bank", "crm-au-" + suffix);
        UUID appId = createTenantApp(
                ownerToken,
                tenantId,
                "Au " + suffix + " App",
                "com.au." + suffix.replace("-", ""),
                List.of("IOS", "ANDROID"));
        UUID versionId = createVersion(ownerToken, appId, "1.0.0");
        return new Uploaded(
                orgId, tenantId, appId, versionId, ownerToken, ownerEmail, uploaderToken, uploaderEmail, payload(2048));
    }

    private static String versionsUri(UUID applicationId) {
        return "/api/v1/applications/" + applicationId + "/versions";
    }

    private static String versionUri(UUID applicationId, UUID versionId) {
        return versionsUri(applicationId) + "/" + versionId;
    }

    private static String archiveUri(UUID applicationId, UUID versionId) {
        return versionUri(applicationId, versionId) + "/archive";
    }

    private static String revokeUri(UUID applicationId, UUID versionId) {
        return versionUri(applicationId, versionId) + "/revoke-distribution";
    }

    private static String historyUri(UUID applicationId, UUID versionId) {
        return versionUri(applicationId, versionId) + "/history";
    }

    private static String finalizeUri(UUID artifactId) {
        return "/api/v1/artifacts/" + artifactId + "/finalize";
    }

    private UUID createVersion(String token, UUID applicationId, String versionName) {
        return UUID.fromString(post(token, versionsUri(applicationId), Map.of("versionName", versionName), 201)
                .get("id")
                .asText());
    }

    private UUID userIdByEmail(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
    }

    private JsonNode uploadMultipart(
            String token,
            UUID versionId,
            String platform,
            String fileName,
            Long buildNumber,
            byte[] bytes,
            int expected)
            throws Exception {
        Recording recording = uploadMultipartRaw(token, versionId, platform, fileName, buildNumber, bytes, null);
        assertThat(recording.status).as("multipart status: %s", recording.body).isEqualTo(expected);
        return recording.body;
    }

    private Recording uploadMultipartRaw(
            String token,
            UUID versionId,
            String platform,
            String fileName,
            Long buildNumber,
            byte[] bytes,
            Void unused) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("version_id", versionId.toString());
        parts.add("platform", platform);
        if (buildNumber != null) {
            parts.add("build_number", Long.toString(buildNumber));
        }
        parts.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        RestClient.RequestBodySpec spec = client.post().uri("/api/v1/artifacts");
        if (token != null) {
            spec.headers(headers -> headers.setBearerAuth(token));
        }
        return spec.contentType(MediaType.MULTIPART_FORM_DATA).body(parts).exchange((request, response) -> {
            byte[] raw = response.getBody().readAllBytes();
            return new Recording(
                    response.getStatusCode().value(),
                    response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                    raw.length == 0 ? mapper.createObjectNode() : mapper.readTree(raw));
        });
    }

    /** The client's direct-to-storage leg: PUT the bytes to the session URL. */
    private Recording pushBytes(String uploadUrl, byte[] bytes) {
        return client.put()
                .uri(URI.create(uploadUrl))
                .header("Content-Range", "bytes 0-" + (bytes.length - 1) + "/" + bytes.length)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(bytes)
                .exchange((request, response) -> {
                    byte[] raw = response.getBody().readAllBytes();
                    return new Recording(
                            response.getStatusCode().value(),
                            response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                            raw.length == 0 ? mapper.createObjectNode() : mapper.readTree(raw));
                });
    }

    private JsonNode createSession(
            String token, UUID versionId, String platform, String fileName, Long buildNumber, int expected) {
        Map<String, Object> body = new HashMap<>();
        body.put("versionId", versionId.toString());
        body.put("platform", platform);
        body.put("fileName", fileName);
        if (buildNumber != null) {
            body.put("buildNumber", buildNumber);
        }
        return post(token, "/api/v1/artifacts/upload-sessions", body, expected);
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
                        "Artifact " + role))
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

    private JsonNode post(String bearer, String uri, Map<String, Object> body, Integer expectedStatus) {
        Recording recording = exchange(uri, bearer, "POST", body);
        if (expectedStatus != null) {
            assertThat(recording.status)
                    .as("POST %s -> %s", uri, recording.body)
                    .isEqualTo(expectedStatus);
        }
        return recording.body;
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
