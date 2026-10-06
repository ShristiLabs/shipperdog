package com.shipperdog.api.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
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
 * The adversarial legs of artifact upload (issue #20, S5-01): the
 * attempts the acceptance suite's happy paths never make — spoofed and
 * traversal-shaped file names, size ceilings probed through request
 * shaping rather than honest Content-Lengths, upload sessions attacked
 * from other tenants, other organizations, and with other versions'
 * ids, zero-byte direct pushes, and the invariants that hold only if
 * nothing regresses: stored objects refuse a second write, no route
 * can overwrite an artifact, and PostgreSQL carries no binary column.
 *
 * <p>The ceilings run tighter than production so kilobytes exercise
 * the real gates: {@code max-bytes} 8192 and the resumable threshold
 * 4096. The storage target is the same compose GCS emulator image
 * (fsouza/fake-gcs-server 1.56.1) the acceptance suite boots; the
 * object listing that proves rejections stored nothing goes straight
 * to the emulator's list API, so "pre-storage" is verified against
 * the bucket itself, not only the metadata rows.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
class ArtifactUploadAdversarialTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";
    private static final String PASSWORD = "a-perfectly-valid-password";
    private static final String BUCKET = "shipperdog-artifacts-local";

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
    static void adversarialCeilings(DynamicPropertyRegistry registry) {
        registry.add(
                "shipperdog.artifact.storage.host", () -> "http://" + GCS.getHost() + ":" + GCS.getMappedPort(4443));
        // The real gates at test scale: anything over 4096 bytes must
        // use the session path, and nothing over 8192 bytes may land.
        registry.add("shipperdog.artifact.upload.max-bytes", () -> "8192");
        registry.add("shipperdog.artifact.upload.resumable-threshold-bytes", () -> "4096");
    }

    @LocalServerPort
    int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private ArtifactStore store;

    private RestClient client;

    private java.net.http.HttpClient rawClient;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        rawClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // -----------------------------------------------------------------------
    // Spoofing: the extension allowlist against case games, compound
    // extensions, traversal names, and garbage form fields — with the
    // bucket itself inspected for orphaned objects afterwards
    // -----------------------------------------------------------------------

    @Test
    void fileNameSpoofsAndTraversalRejectBeforeStorageLeavingNoOrphanObjects() throws Exception {
        Uploaded uploaded = uploadWorld("au-adv-spoof");
        byte[] bytes = payload(512);

        // The extension match is case-insensitive: BANK.IPA is an IPA.
        Recording upperCase =
                uploadMultipartRaw(uploaded.token(), uploaded.versionId(), "IOS", "BANK.IPA", null, bytes, null);
        assertThat(upperCase.status).isEqualTo(201);

        // The allowlist reads the FINAL extension only: a double
        // extension ending outside it rejects pre-storage, while a
        // name merely CONTAINING other extensions stays an .ipa (the
        // content itself is S5-02/03 metadata extraction's to judge).
        assertThat(uploadMultipartRaw(
                                uploaded.token(), uploaded.versionId(), "IOS", "trojan.ipa.exe", null, bytes, null)
                        .status)
                .isEqualTo(415);
        assertThat(uploadMultipartRaw(
                                uploaded.token(), uploaded.versionId(), "IOS", "trojan.exe.zip.ipa", null, bytes, null)
                        .status)
                .isEqualTo(201);

        // Traversal and separator shapes never become path material:
        // the name is a bare name or the request is broken.
        assertThat(uploadMultipartRaw(uploaded.token(), uploaded.versionId(), "IOS", "../escape.ipa", null, bytes, null)
                        .status)
                .isEqualTo(400);
        assertThat(uploadMultipartRaw(uploaded.token(), uploaded.versionId(), "IOS", "sub\\bank.ipa", null, bytes, null)
                        .status)
                .isEqualTo(400);
        assertThat(uploadMultipartRaw(
                                uploaded.token(),
                                uploaded.versionId(),
                                "IOS",
                                "l".repeat(252) + ".ipa",
                                null,
                                bytes,
                                null)
                        .status)
                .isEqualTo(400);

        // A build number that is not a number is a broken request, not
        // a 500.
        assertThat(uploadMultipartGarbageBuildNumber(uploaded.token(), uploaded.versionId(), "IOS", "b.ipa", bytes)
                        .status)
                .isEqualTo(400);

        // Two successes, every rejection stored nothing: the row count
        // says so, and so does the bucket itself — "pre-storage" is
        // verified against object storage, not only PostgreSQL.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM artifacts WHERE version_id = ?", Integer.class, uploaded.versionId()))
                .isEqualTo(2);
        List<String> objects = storedObjectNamesUnder(uploaded.orgId());
        assertThat(objects).hasSize(2);
        for (String object : objects) {
            ArtifactStore.StoredChecksum stored = store.hash(object).orElseThrow();
            assertThat(stored.sha256Hex()).isEqualTo(sha256Of(bytes));
            assertThat(stored.size()).isEqualTo(bytes.length);
        }
    }

    // -----------------------------------------------------------------------
    // The ceiling binds the bytes that actually stream, not the shape
    // of the request: chunked bodies without a Content-Length, direct
    // pushes past the ceiling, and size hints that lie
    // -----------------------------------------------------------------------

    @Test
    void sizeCeilingBindsActualStreamedBytesRegardlessOfRequestHeaders() throws Exception {
        Uploaded uploaded = uploadWorld("au-adv-size");

        // A chunked body carries no Content-Length at all: the
        // threshold rule (and through it the ceiling) must still bind
        // the bytes that actually arrive.
        Recording chunkedOverThreshold =
                uploadMultipartChunked(uploaded.token(), uploaded.versionId(), "IOS", "chunked.ipa", payload(6000));
        assertThat(chunkedOverThreshold.status)
                .as("%s", chunkedOverThreshold.body)
                .isEqualTo(413);
        assertThat(chunkedOverThreshold.body.get("code").asText()).isEqualTo("ARTIFACT_TOO_LARGE");

        // The same transport under the threshold succeeds — it is the
        // size that refused, not the chunking.
        Recording chunkedUnder = uploadMultipartChunked(
                uploaded.token(), uploaded.versionId(), "IOS", "chunked-small.ipa", payload(2000));
        assertThat(chunkedUnder.status).as("%s", chunkedUnder.body).isEqualTo(201);

        // A lying size hint over the ceiling is refused before a
        // session is ever granted.
        Map<String, Object> oversizeHint = sessionBody(uploaded.versionId(), "IOS", "hint.ipa", null);
        oversizeHint.put("sizeHint", 9000L);
        assertThat(exchange("/api/v1/artifacts/upload-sessions", uploaded.token(), "POST", oversizeHint).status)
                .isEqualTo(413);

        // A nonsensical hint is a broken request.
        Map<String, Object> zeroHint = sessionBody(uploaded.versionId(), "IOS", "zero.ipa", null);
        zeroHint.put("sizeHint", 0L);
        assertThat(exchange("/api/v1/artifacts/upload-sessions", uploaded.token(), "POST", zeroHint).status)
                .isEqualTo(400);
        Map<String, Object> negativeHint = sessionBody(uploaded.versionId(), "IOS", "neg.ipa", null);
        negativeHint.put("sizeHint", -1L);
        assertThat(exchange("/api/v1/artifacts/upload-sessions", uploaded.token(), "POST", negativeHint).status)
                .isEqualTo(400);

        // The hint is optional for a reason: a client that streams
        // past the ceiling direct to storage is stopped at finalize —
        // no artifact row registers, and the session stays to answer
        // the retry or expire by TTL.
        JsonNode session = post(
                uploaded.token(),
                "/api/v1/artifacts/upload-sessions",
                sessionBody(uploaded.versionId(), "IOS", "past.ipa", null),
                201);
        UUID artifactId = UUID.fromString(session.get("artifactId").asText());
        pushBytes(session.get("uploadUrl").asText(), payload(9000));
        Recording overCeiling = exchange(
                finalizeUri(artifactId),
                uploaded.token(),
                "POST",
                Map.of("versionId", uploaded.versionId().toString()));
        assertThat(overCeiling.status).as("%s", overCeiling.body).isEqualTo(413);
        assertThat(overCeiling.body.get("code").asText()).isEqualTo("ARTIFACT_TOO_LARGE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM artifacts WHERE id = ?", Integer.class, artifactId))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM artifact_upload_sessions WHERE artifact_id = ?",
                        Integer.class,
                        artifactId))
                .isEqualTo(1);

        // Only the under-threshold chunked upload registered.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM artifacts WHERE version_id = ?", Integer.class, uploaded.versionId()))
                .isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // Session reach: retargeting a session to another version is the
    // concealed miss; an org developer reaches every tenant of the
    // organization by the union rule; a different organization is
    // refused both ways; and the registered artifact survives it all
    // -----------------------------------------------------------------------

    @Test
    void uploadSessionsResistCrossTenantAndCrossOrgHijack() throws Exception {
        Uploaded victim = uploadWorld("au-adv-victim");
        byte[] bytes = payload(2048);

        // A second tenant of the same organization, with its own app
        // and version — the cross-tenant principal holds a real
        // DEVELOPER grant, in the same organization as the victim, one
        // tenant over.
        UUID attackerTenant = createTenant(victim.adminToken(), victim.orgId(), "Attacker Bank", "crm-au-adv-att");
        UUID attackerApp = createTenantApp(
                victim.ownerToken(), attackerTenant, "Attacker App", "com.au.advatt", List.of("IOS", "ANDROID"));
        UUID attackerVersion = createVersion(victim.ownerToken(), attackerApp, "1.0.0");
        String crossTenantToken = loginOfOrgRoleIn(victim.orgId(), "au-adv-attacker@example.com", "DEVELOPER");

        // A different organization entirely, same shape.
        UUID otherOrgId = createOrg("Au Adv Other Corp");
        String otherAdminToken = loginOfOrgRoleIn(otherOrgId, "au-adv-other-admin@example.com", "ORG_ADMIN");
        String otherOwnerToken = loginOfOrgRoleIn(otherOrgId, "au-adv-other-owner@example.com", "APP_OWNER");
        UUID otherTenant = createTenant(otherAdminToken, otherOrgId, "Other Bank", "crm-au-adv-other");
        UUID otherApp = createTenantApp(otherOwnerToken, otherTenant, "Other App", "com.au.advother", List.of("IOS"));
        UUID otherVersion = createVersion(otherOwnerToken, otherApp, "1.0.0");
        String crossOrgToken = loginOfOrgRoleIn(otherOrgId, "au-adv-other-dev@example.com", "DEVELOPER");

        // The victim's session: created, bytes pushed, not finalized.
        JsonNode session = post(
                victim.token(),
                "/api/v1/artifacts/upload-sessions",
                sessionBody(victim.versionId(), "IOS", "victim.ipa", null),
                201);
        UUID victimArtifactId = UUID.fromString(session.get("artifactId").asText());
        String victimUploadUrl = session.get("uploadUrl").asText();
        assertThat(pushBytes(victimUploadUrl, bytes).status).isEqualTo(200);

        // The session URL is GCS-issued and opaque: long, and never
        // the same token twice.
        JsonNode secondSession = post(
                victim.token(),
                "/api/v1/artifacts/upload-sessions",
                sessionBody(victim.versionId(), "IOS", "second.ipa", null),
                201);
        assertThat(victimUploadUrl.length()).isGreaterThan(60);
        assertThat(secondSession.get("uploadUrl").asText()).isNotEqualTo(victimUploadUrl);

        // Hijack with the attacker's own version id: the session and
        // the artifact are invisible in the attacker's scope — the
        // concealed miss, exactly like an unknown artifact.
        Recording ownVersion = exchange(
                finalizeUri(victimArtifactId),
                crossTenantToken,
                "POST",
                Map.of("versionId", attackerVersion.toString()));
        assertThat(ownVersion.status).as("%s", ownVersion.body).isEqualTo(404);
        assertThat(ownVersion.body.get("code").asText()).isEqualTo("ARTIFACT_NOT_FOUND");

        // A same-organization developer of ANOTHER tenant, carrying
        // the VICTIM's version id: the authorization matrix's union
        // rule (docs/authorization.md -- the owning organization's
        // roles overlay tenant scopes, and DEVELOPER holds
        // app:artifacts:upload) makes an org developer an authorized
        // uploader for every tenant of the organization. The finalize
        // registers the artifact on the victim's version with the ACTOR
        // as uploader and consumes the session -- authorized use, not a
        // hijack, and this test pins that contract down explicitly.
        Recording orgDeveloperFinalize = exchange(
                finalizeUri(victimArtifactId),
                crossTenantToken,
                "POST",
                Map.of("versionId", victim.versionId().toString()));
        assertThat(orgDeveloperFinalize.status)
                .as("%s", orgDeveloperFinalize.body)
                .isEqualTo(201);
        assertThat(orgDeveloperFinalize.body.get("created").asBoolean()).isTrue();
        assertThat(orgDeveloperFinalize.body.get("artifact").get("uploadedBy").asText())
                .isEqualTo(userIdByEmail("au-adv-attacker@example.com").toString());
        assertThat(orgDeveloperFinalize.body.get("artifact").get("sha256").asText())
                .isEqualTo(sha256Of(bytes));
        String victimStorageKey =
                orgDeveloperFinalize.body.get("artifact").get("storageKey").asText();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM artifact_upload_sessions WHERE artifact_id = ?",
                        Integer.class,
                        victimArtifactId))
                .isZero();

        // The victim's own finalize afterwards replays the terminal
        // state -- 200, created=false, the same artifact.
        Recording victimReplay = exchange(
                finalizeUri(victimArtifactId),
                victim.token(),
                "POST",
                Map.of("versionId", victim.versionId().toString()));
        assertThat(victimReplay.status).as("%s", victimReplay.body).isEqualTo(200);
        assertThat(victimReplay.body.get("created").asBoolean()).isFalse();
        assertThat(victimReplay.body.get("artifact").get("id").asText()).isEqualTo(victimArtifactId.toString());

        // A developer of a DIFFERENT organization is refused both
        // ways: with their own version id the artifact is invisible in
        // their scope, and with the victim's version id the permission
        // check conceals the denial.
        Recording foreignOrgOwnVersion = exchange(
                finalizeUri(victimArtifactId), crossOrgToken, "POST", Map.of("versionId", otherVersion.toString()));
        assertThat(foreignOrgOwnVersion.status).isEqualTo(404);
        assertThat(foreignOrgOwnVersion.body.get("code").asText()).isEqualTo("ARTIFACT_NOT_FOUND");
        Recording foreignOrgVictimVersion = exchange(
                finalizeUri(victimArtifactId),
                crossOrgToken,
                "POST",
                Map.of("versionId", victim.versionId().toString()));
        assertThat(foreignOrgVictimVersion.status).isEqualTo(404);

        // Session creation against the victim's version from the
        // other organization conceals the same way.
        assertThat(exchange(
                                "/api/v1/artifacts/upload-sessions",
                                crossOrgToken,
                                "POST",
                                sessionBody(victim.versionId(), "IOS", "steal.ipa", null))
                        .status)
                .isEqualTo(404);

        // The artifact survived every probe: one row, bytes untouched.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM artifacts WHERE id = ?", Integer.class, victimArtifactId))
                .isEqualTo(1);
        assertThat(store.hash(victimStorageKey).orElseThrow().sha256Hex()).isEqualTo(sha256Of(bytes));

        // The cross-tenant developer is not a broken principal: their
        // own tenant's upload works end to end.
        JsonSessionLeg attackerLeg = pushAndFinalize(crossTenantToken, attackerVersion, "attacker.ipa", bytes);
        assertThat(attackerLeg.outcome().status).isEqualTo(201);
    }

    // -----------------------------------------------------------------------
    // A zero-byte direct push is a broken request, never a stored
    // artifact
    // -----------------------------------------------------------------------

    @Test
    void zeroByteDirectPushFinalizesAsBadRequest() throws Exception {
        Uploaded uploaded = uploadWorld("au-adv-zero");
        JsonNode session = post(
                uploaded.token(),
                "/api/v1/artifacts/upload-sessions",
                sessionBody(uploaded.versionId(), "IOS", "empty.ipa", null),
                201);
        UUID artifactId = UUID.fromString(session.get("artifactId").asText());

        client.put()
                .uri(URI.create(session.get("uploadUrl").asText()))
                .header("Content-Range", "bytes */0")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new byte[0])
                .exchange((request, response) -> response.getStatusCode().value());

        Recording finalize = exchange(
                finalizeUri(artifactId),
                uploaded.token(),
                "POST",
                Map.of("versionId", uploaded.versionId().toString()));
        assertThat(finalize.status).as("%s", finalize.body).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM artifacts WHERE id = ?", Integer.class, artifactId))
                .isZero();
    }

    // -----------------------------------------------------------------------
    // INV-003's invariants: the store itself refuses a second write to
    // the same key and keeps the first bytes, no HTTP route can
    // overwrite an artifact, and no artifact table carries a binary
    // column (FR-007)
    // -----------------------------------------------------------------------

    @Test
    void objectStorageIsWriteOnceAndNoRouteOverwritesAndPostgresCarriesNoBinaryColumns() throws Exception {
        Uploaded uploaded = uploadWorld("au-adv-inv");
        byte[] bytes = payload(1024);
        JsonNode artifact = uploadMultipart(uploaded.token(), uploaded.versionId(), "IOS", "inv.ipa", null, bytes, 201);
        UUID artifactId = UUID.fromString(artifact.get("id").asText());

        // The store-level belt-and-braces: the doesNotExist
        // precondition refuses the second write to the same key, and
        // the object keeps its original bytes.
        String key = artifact.get("storageKey").asText();
        assertThatThrownBy(() -> store.put(key, new ByteArrayInputStream(payload(64)), "application/octet-stream"))
                .isInstanceOf(ArtifactException.class)
                .extracting(exception -> ((ArtifactException) exception).code())
                .isEqualTo(ArtifactException.Code.STORAGE_UNAVAILABLE);
        assertThat(store.hash(key).orElseThrow().sha256Hex()).isEqualTo(sha256Of(bytes));

        // No artifact route answers a mutation method: the collection
        // refuses the method outright, the item path does not exist.
        assertThat(exchange("/api/v1/artifacts", uploaded.token(), "PUT", Map.of()).status)
                .isEqualTo(405);
        assertThat(exchange("/api/v1/artifacts", uploaded.token(), "DELETE", null).status)
                .isEqualTo(405);
        assertThat(exchange("/api/v1/artifacts/" + artifactId, uploaded.token(), "PATCH", Map.of()).status)
                .isEqualTo(404);
        assertThat(exchange("/api/v1/artifacts/" + artifactId, uploaded.token(), "PUT", Map.of()).status)
                .isEqualTo(404);
        assertThat(exchange("/api/v1/artifacts/" + artifactId, uploaded.token(), "DELETE", null).status)
                .isEqualTo(404);

        // FR-007's schema leg: no binary column anywhere in the
        // artifact tables.
        Integer binaryColumns = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name IN ('artifacts', 'artifact_upload_sessions') "
                        + "AND data_type IN ('bytea', 'bytea[]', 'binary', 'varbinary', 'blob', 'tinyblob', 'mediumblob', 'longblob')",
                Integer.class);
        assertThat(binaryColumns).isZero();

        // The stored bytes survived every probe above.
        assertThat(store.hash(key).orElseThrow().sha256Hex()).isEqualTo(sha256Of(bytes));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** The world every adversarial test starts from (see the acceptance suite's shape). */
    private record Uploaded(
            UUID orgId,
            UUID tenantId,
            UUID appId,
            UUID versionId,
            String ownerToken,
            String adminToken,
            String uploaderToken) {

        String token() {
            return uploaderToken;
        }
    }

    private Uploaded uploadWorld(String suffix) {
        UUID orgId = createOrg("Au Adv " + suffix + " Corp");
        String ownerToken = loginOfOrgRoleIn(orgId, "au-adv-" + suffix + "-owner@example.com", "APP_OWNER");
        String adminToken = loginOfOrgRoleIn(orgId, "au-adv-" + suffix + "-admin@example.com", "ORG_ADMIN");
        String uploaderToken = loginOfOrgRoleIn(orgId, "au-adv-" + suffix + "-dev@example.com", "DEVELOPER");
        UUID tenantId = createTenant(adminToken, orgId, "Au Adv " + suffix + " Bank", "crm-au-adv-" + suffix);
        UUID appId = createTenantApp(
                ownerToken,
                tenantId,
                "Au Adv " + suffix + " App",
                "com.au.adv." + suffix.replace("-", ""),
                List.of("IOS", "ANDROID"));
        UUID versionId = createVersion(ownerToken, appId, "1.0.0");
        return new Uploaded(orgId, tenantId, appId, versionId, ownerToken, adminToken, uploaderToken);
    }

    /** One full session-path leg: create, push, finalize (used for control legs). */
    private record JsonSessionLeg(Recording push, Recording outcome) {}

    private JsonSessionLeg pushAndFinalize(String token, UUID versionId, String fileName, byte[] bytes) {
        JsonNode session =
                post(token, "/api/v1/artifacts/upload-sessions", sessionBody(versionId, "IOS", fileName, null), 201);
        Recording push = pushBytes(session.get("uploadUrl").asText(), bytes);
        assertThat(push.status).as("direct push").isEqualTo(200);
        Recording finalize = exchange(
                finalizeUri(UUID.fromString(session.get("artifactId").asText())),
                token,
                "POST",
                Map.of("versionId", versionId.toString()));
        return new JsonSessionLeg(push, finalize);
    }

    private static Map<String, Object> sessionBody(UUID versionId, String platform, String fileName, Long buildNumber) {
        Map<String, Object> body = new HashMap<>();
        body.put("versionId", versionId.toString());
        body.put("platform", platform);
        body.put("fileName", fileName);
        if (buildNumber != null) {
            body.put("buildNumber", buildNumber);
        }
        return body;
    }

    private static String finalizeUri(UUID artifactId) {
        return "/api/v1/artifacts/" + artifactId + "/finalize";
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

    private UUID userIdByEmail(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
    }

    /**
     * Every object the bucket holds under the organization's prefix —
     * the emulator's own list API, so orphan checks see the store, not
     * just the registry.
     */
    private List<String> storedObjectNamesUnder(UUID orgId) {
        String listing = client.get()
                .uri(URI.create(
                        "http://" + GCS.getHost() + ":" + GCS.getMappedPort(4443) + "/storage/v1/b/" + BUCKET + "/o"))
                .retrieve()
                .body(String.class);
        try {
            JsonNode items = mapper.readTree(listing).get("items");
            List<String> names = new java.util.ArrayList<>();
            if (items != null && !items.isNull()) {
                for (JsonNode item : items) {
                    names.add(item.get("name").asText());
                }
            }
            return names.stream().filter(name -> name.startsWith(orgId + "/")).toList();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode uploadMultipart(
            String token,
            UUID versionId,
            String platform,
            String fileName,
            Long buildNumber,
            byte[] bytes,
            int expected) {
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
        parts.add("file", new org.springframework.core.io.ByteArrayResource(bytes) {
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
                    raw.length == 0 ? mapper.createObjectNode() : mapper.readTree(raw));
        });
    }

    /** A multipart upload whose build_number field is not a number. */
    private Recording uploadMultipartGarbageBuildNumber(
            String token, UUID versionId, String platform, String fileName, byte[] bytes) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("version_id", versionId.toString());
        parts.add("platform", platform);
        parts.add("build_number", "abc");
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
                    byte[] raw = response.getBody().readAllBytes();
                    return new Recording(
                            response.getStatusCode().value(),
                            raw.length == 0 ? mapper.createObjectNode() : mapper.readTree(raw));
                });
    }

    /**
     * A multipart upload over chunked transfer encoding — no
     * Content-Length anywhere, the only honest way to prove the
     * ceiling binds the bytes that actually arrive.
     */
    private Recording uploadMultipartChunked(
            String token, UUID versionId, String platform, String fileName, byte[] bytes) throws Exception {
        String boundary =
                "----shipperdogadversarial" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        formField(body, boundary, "version_id", versionId.toString());
        formField(body, boundary, "platform", platform);
        byte[] preamble = body.toByteArray();
        byte[] head = ("--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                        + "Content-Type: application/octet-stream\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream whole =
                new ByteArrayOutputStream(preamble.length + head.length + bytes.length + tail.length);
        whole.write(preamble);
        whole.write(head);
        whole.write(bytes);
        whole.write(tail);

        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/artifacts"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .timeout(Duration.ofSeconds(30))
                // An InputStream publisher has unknown length:
                // the transport chunks, no Content-Length exists.
                .POST(java.net.http.HttpRequest.BodyPublishers.ofInputStream(
                        () -> new ByteArrayInputStream(whole.toByteArray())))
                .build();
        HttpResponse<String> response = rawClient.send(request, HttpResponse.BodyHandlers.ofString());
        return new Recording(
                response.statusCode(),
                response.body().isEmpty() ? mapper.createObjectNode() : mapper.readTree(response.body()));
    }

    private static void formField(ByteArrayOutputStream out, String boundary, String name, String value) {
        try {
            out.write(("--" + boundary + "\r\n" + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                            + value + "\r\n")
                    .getBytes(StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
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
                            raw.length == 0 ? mapper.createObjectNode() : mapper.readTree(raw));
                });
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
                        "Adversarial " + role))
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

    private record Recording(int status, JsonNode body) {}
}
