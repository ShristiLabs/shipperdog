package com.shipperdog.api.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.AuditCapture;
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
 * End-to-end acceptance flow for build-provenance capture (issue #23,
 * S5-04, FR-005, PRD 8.5): every upload surface accepts the five
 * frozen claims and persists them with the artifact (AC1), an upload
 * without provenance stores none and never invents any (AC2), the
 * artifact read answers the provenance block (AC3), and malformed
 * claims reject with the 400 problem envelope naming every offending
 * field (AC4). The resumable path's capture point is session
 * creation; finalize replays the captured claims into the registered
 * row.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
@Import(AuditCapture.class)
class ArtifactProvenanceIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";
    private static final String PASSWORD = "a-perfectly-valid-password";

    private static final String SHA1 = "0123456789abcdef0123456789abcdef01234567";

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
        // Provenance legs stay under the threshold; the >100 MB rule is
        // the upload suite's concern.
        registry.add("shipperdog.artifact.upload.resumable-threshold-bytes", () -> "104857600");
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
    // AC1 + AC3: the five claims land with the artifact and read back
    // -----------------------------------------------------------------------

    @Test
    void multipartUploadPersistsProvenanceAndTheReadAnswersIt() throws Exception {
        Uploaded uploaded = uploadWorld("ap-full");
        byte[] bytes = uploaded.bytes();

        JsonNode artifact = uploadMultipart(
                uploaded.token(),
                uploaded.versionId(),
                "IOS",
                "bank.ipa",
                Map.of(
                        "git_commit", SHA1.toUpperCase(java.util.Locale.ROOT),
                        "git_branch", "release/7.12.0",
                        "pipeline", "mobile-nightly",
                        "ci_run_url", "https://ci.acme.dev/pipelines/mobile/runs/842",
                        "build_timestamp", "2026-10-04T14:30:05+02:00"),
                bytes,
                201);
        UUID artifactId = UUID.fromString(artifact.get("id").asText());

        // The upload response carries the block: the commit normalized
        // lowercase, the instant canonicalized UTC, every claim the
        // client asserted and nothing it did not.
        JsonNode provenance = artifact.get("provenance");
        assertThat(provenance.get("gitCommit").asText()).isEqualTo(SHA1);
        assertThat(provenance.get("gitBranch").asText()).isEqualTo("release/7.12.0");
        assertThat(provenance.get("pipeline").asText()).isEqualTo("mobile-nightly");
        assertThat(provenance.get("ciRunUrl").asText()).isEqualTo("https://ci.acme.dev/pipelines/mobile/runs/842");
        assertThat(provenance.get("buildTimestamp").asText()).isEqualTo("2026-10-04T12:30:05Z");

        // AC3: GET /api/v1/artifacts/{id} returns the provenance block.
        JsonNode read =
                get(uploaded.token(), "/api/v1/artifacts/" + artifactId + "?version_id=" + uploaded.versionId(), 200);
        assertThat(read.get("provenance")).isEqualTo(provenance);

        // The row stores exactly the frozen five (only the asserted
        // members) as a jsonb object — V17's shape.
        String document =
                jdbc.queryForObject("SELECT provenance::text FROM artifacts WHERE id = ?", String.class, artifactId);
        JsonNode stored = mapper.readTree(document);
        assertThat(stored.propertyNames())
                .containsExactlyInAnyOrder("gitCommit", "gitBranch", "pipeline", "ciRunUrl", "buildTimestamp");
        assertThat(stored.get("gitCommit").asText()).isEqualTo(SHA1);

        // The version views carry the block too (the story's
        // "surfaced in artifact/version APIs").
        JsonNode detail = get(uploaded.token(), versionUri(uploaded.appId(), uploaded.versionId()), 200);
        JsonNode slot = detail.get("artifactsByPlatform").get("IOS").get(0);
        assertThat(slot.get("provenance").get("pipeline").asText()).isEqualTo("mobile-nightly");
        JsonNode history = get(uploaded.token(), historyUri(uploaded.appId(), uploaded.versionId()), 200)
                .get("artifacts");
        assertThat(history.get(0).get("provenance").get("gitBranch").asText()).isEqualTo("release/7.12.0");

        // The incident-answer pair rides the audit trail.
        List<AuditEvent> events = audit.eventsOfType(AuditEventType.ARTIFACT_UPLOADED);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).detail()).containsEntry("gitCommit", SHA1);
        assertThat(events.get(0).detail()).containsEntry("pipeline", "mobile-nightly");
    }

    // -----------------------------------------------------------------------
    // AC2: optional at upload, never fabricated
    // -----------------------------------------------------------------------

    @Test
    void uploadWithoutProvenanceStoresNoneAndInventsNothing() throws Exception {
        Uploaded uploaded = uploadWorld("ap-bare");

        JsonNode artifact = uploadMultipart(
                uploaded.token(), uploaded.versionId(), "IOS", "bank.ipa", Map.of(), uploaded.bytes(), 201);
        UUID artifactId = UUID.fromString(artifact.get("id").asText());

        // The block itself is JSON null — not an empty object, not zeros.
        assertThat(artifact.path("provenance").isNull()).isTrue();
        JsonNode read =
                get(uploaded.token(), "/api/v1/artifacts/" + artifactId + "?version_id=" + uploaded.versionId(), 200);
        assertThat(read.path("provenance").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT provenance FROM artifacts WHERE id = ?", String.class, artifactId))
                .isNull();

        // The slot answers null too, and the audit trail carries no
        // fabricated pair.
        JsonNode detail = get(uploaded.token(), versionUri(uploaded.appId(), uploaded.versionId()), 200);
        assertThat(detail.get("artifactsByPlatform")
                        .get("IOS")
                        .get(0)
                        .path("provenance")
                        .isNull())
                .isTrue();
        assertThat(audit.eventsOfType(AuditEventType.ARTIFACT_UPLOADED).get(0).detail())
                .doesNotContainKeys("gitCommit", "pipeline");

        // Blank form fields are absence, not values.
        JsonNode blanked = uploadMultipart(
                uploaded.token(),
                uploaded.versionId(),
                "IOS",
                "bank-2.ipa",
                Map.of("git_branch", "  ", "pipeline", ""),
                uploaded.bytes(),
                201);
        assertThat(blanked.path("provenance").isNull()).isTrue();
    }

    // -----------------------------------------------------------------------
    // AC4: malformed claims reject 400 with field errors, pre-storage
    // -----------------------------------------------------------------------

    @Test
    void malformedProvenanceRejectsWithFieldErrorsAndStoresNothing() throws Exception {
        Uploaded uploaded = uploadWorld("ap-bad");

        Recording bad = uploadMultipartRaw(
                uploaded.token(),
                uploaded.versionId(),
                "IOS",
                "bank.ipa",
                Map.of("git_commit", "HEAD", "ci_run_url", "not a url", "git_branch", "ok"),
                uploaded.bytes());
        assertThat(bad.status).isEqualTo(400);
        assertThat(bad.contentType).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(bad.body.get("code").asText()).isEqualTo("INVALID_REQUEST");
        // Field errors name the offending wire fields — and only those.
        JsonNode errors = bad.body.get("errors");
        assertThat(errors.propertyNames()).containsExactlyInAnyOrder("git_commit", "ci_run_url");
        assertThat(errors.get("git_commit").asText()).contains("SHA-1");

        // Pre-storage means pre-storage: no row, and the session
        // surface answers the same shape before granting a session.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM artifacts WHERE version_id = ?", Integer.class, uploaded.versionId()))
                .isZero();
        Map<String, Object> body = sessionBody(uploaded.versionId(), "IOS", "bank.ipa");
        body.put("gitCommit", "zz-not-a-commit");
        Recording sessionBad = exchange("/api/v1/artifacts/upload-sessions", uploaded.token(), "POST", body);
        assertThat(sessionBad.status).isEqualTo(400);
        assertThat(sessionBad.body.get("errors").propertyNames()).containsExactly("git_commit");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM artifact_upload_sessions WHERE version_id = ?",
                        Integer.class,
                        uploaded.versionId()))
                .isZero();
    }

    // -----------------------------------------------------------------------
    // The resumable path: capture at session creation, replay at finalize
    // -----------------------------------------------------------------------

    @Test
    void sessionCreationCapturesProvenanceAndFinalizeReplaysIt() throws Exception {
        Uploaded uploaded = uploadWorld("ap-res");
        byte[] bytes = uploaded.bytes();

        Map<String, Object> body = sessionBody(uploaded.versionId(), "IOS", "big.ipa");
        body.put("gitCommit", SHA1);
        body.put("gitBranch", "main");
        body.put("pipeline", "release");
        body.put("ciRunUrl", "https://ci.acme.dev/run/843");
        body.put("buildTimestamp", "2026-10-04T09:00:00Z");
        JsonNode session = post(uploaded.token(), "/api/v1/artifacts/upload-sessions", body, 201);
        UUID artifactId = UUID.fromString(session.get("artifactId").asText());

        // The session row holds the captured document until finalize.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM artifact_upload_sessions WHERE provenance ? 'gitCommit'", Integer.class))
                .isEqualTo(1);

        pushBytes(session.get("uploadUrl").asText(), bytes);
        JsonNode finalize = post(
                uploaded.token(),
                finalizeUri(artifactId),
                Map.of("versionId", uploaded.versionId().toString()),
                201);
        assertThat(finalize.get("created").asBoolean()).isTrue();
        assertThat(finalize.get("artifact").get("provenance").get("gitCommit").asText())
                .isEqualTo(SHA1);
        assertThat(finalize.get("artifact")
                        .get("provenance")
                        .get("buildTimestamp")
                        .asText())
                .isEqualTo("2026-10-04T09:00:00Z");

        // A finalize retry replays the registered artifact WITH its
        // captured provenance — the session is gone, the claims are not.
        JsonNode replay = post(
                uploaded.token(),
                finalizeUri(artifactId),
                Map.of("versionId", uploaded.versionId().toString()),
                200);
        assertThat(replay.get("created").asBoolean()).isFalse();
        assertThat(replay.get("artifact").get("provenance").get("pipeline").asText())
                .isEqualTo("release");

        JsonNode read =
                get(uploaded.token(), "/api/v1/artifacts/" + artifactId + "?version_id=" + uploaded.versionId(), 200);
        assertThat(read.get("provenance").get("ciRunUrl").asText()).isEqualTo("https://ci.acme.dev/run/843");
    }

    // -----------------------------------------------------------------------
    // Adversarial (tester pass): immutability under replay, gates over capture
    // -----------------------------------------------------------------------

    @Test
    void idempotentReplayKeepsTheFirstCapturedProvenance() throws Exception {
        Uploaded uploaded = uploadWorld("ap-idem");

        // The designated-POST registry replays the FIRST answer for a
        // repeated key: a retry asserting DIFFERENT claims can never
        // rewrite what was captured (the claims are part of the
        // registered, immutable artifact).
        Recording first = multipartWithIdempotencyKey(
                uploaded.token(),
                "ap-idem-key-1",
                uploaded.versionId(),
                "IOS",
                "one.ipa",
                Map.of("git_commit", SHA1, "pipeline", "first"));
        assertThat(first.status).isEqualTo(201);
        String artifactId = first.body.get("id").asText();

        Recording replay = multipartWithIdempotencyKey(
                uploaded.token(),
                "ap-idem-key-1",
                uploaded.versionId(),
                "IOS",
                "one.ipa",
                Map.of("git_commit", "fedcba9876543210fedcba9876543210fedcba98", "pipeline", "impostor"));
        assertThat(replay.status).isEqualTo(201);
        assertThat(replay.body.get("id").asText()).isEqualTo(artifactId);
        assertThat(replay.body.get("provenance").get("pipeline").asText()).isEqualTo("first");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM artifacts WHERE version_id = ?", Integer.class, uploaded.versionId()))
                .isEqualTo(1);
    }

    @Test
    void theAllowlistGateWinsOverSessionCapture() throws Exception {
        Uploaded uploaded = uploadWorld("ap-gate");

        // Valid provenance behind a bad file name answers 415 with NO
        // session row and NO provenance stored anywhere: capture rides
        // the upload guards, it never bypasses them.
        Map<String, Object> body = sessionBody(uploaded.versionId(), "IOS", "notes.zip");
        body.put("gitCommit", SHA1);
        Recording refused = exchange("/api/v1/artifacts/upload-sessions", uploaded.token(), "POST", body);
        assertThat(refused.status).isEqualTo(415);
        assertThat(refused.body.get("code").asText()).isEqualTo("ARTIFACT_FILE_TYPE_REJECTED");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM artifact_upload_sessions WHERE version_id = ?",
                        Integer.class,
                        uploaded.versionId()))
                .isZero();
    }

    // -----------------------------------------------------------------------
    // Helpers (the upload suite's harness)
    // -----------------------------------------------------------------------

    private record Uploaded(
            UUID orgId,
            UUID tenantId,
            UUID appId,
            UUID versionId,
            String ownerToken,
            String uploaderToken,
            byte[] bytes) {

        /** The uploader's token: every artifact-surface call uses it. */
        String token() {
            return uploaderToken;
        }
    }

    /** The world every provenance test starts from (the upload suite's shape). */
    private Uploaded uploadWorld(String suffix) throws java.io.IOException {
        UUID orgId = createOrg("Ap " + suffix + " Corp");
        String ownerToken = loginOfOrgRoleIn(orgId, "ap-" + suffix + "-owner@example.com", "APP_OWNER");
        String adminToken = loginOfOrgRoleIn(orgId, "ap-" + suffix + "-admin@example.com", "ORG_ADMIN");
        String uploaderToken = loginOfOrgRoleIn(orgId, "ap-" + suffix + "-dev@example.com", "DEVELOPER");
        UUID tenantId = createTenant(adminToken, orgId, "Ap " + suffix + " Bank", "crm-ap-" + suffix);
        UUID appId = createTenantApp(
                ownerToken,
                tenantId,
                "Ap " + suffix + " App",
                "com.ap." + suffix.replace("-", ""),
                List.of("IOS", "ANDROID"));
        UUID versionId = createVersion(ownerToken, appId, "1.0.0");
        // Since S5-02 the stored bytes are parsed asynchronously; the
        // suite's assertions hold deterministically only for a REAL
        // (parseable) IPA, so the shared fixture is one.
        return new Uploaded(orgId, tenantId, appId, versionId, ownerToken, uploaderToken, IpaFixtures.unsignedIpa());
    }

    private static String versionsUri(UUID applicationId) {
        return "/api/v1/applications/" + applicationId + "/versions";
    }

    private static String versionUri(UUID applicationId, UUID versionId) {
        return versionsUri(applicationId) + "/" + versionId;
    }

    private static String historyUri(UUID applicationId, UUID versionId) {
        return versionUri(applicationId, versionId) + "/history";
    }

    private static String finalizeUri(UUID artifactId) {
        return "/api/v1/artifacts/" + artifactId + "/finalize";
    }

    private Map<String, Object> sessionBody(UUID versionId, String platform, String fileName) {
        Map<String, Object> body = new HashMap<>();
        body.put("versionId", versionId.toString());
        body.put("platform", platform);
        body.put("fileName", fileName);
        return body;
    }

    private UUID createVersion(String token, UUID applicationId, String versionName) {
        return UUID.fromString(post(token, versionsUri(applicationId), Map.of("versionName", versionName), 201)
                .get("id")
                .asText());
    }

    private JsonNode uploadMultipart(
            String token,
            UUID versionId,
            String platform,
            String fileName,
            Map<String, String> provenance,
            byte[] bytes,
            int expected) {
        Recording recording = uploadMultipartRaw(token, versionId, platform, fileName, provenance, bytes);
        assertThat(recording.status).as("multipart status: %s", recording.body).isEqualTo(expected);
        return recording.body;
    }

    private Recording uploadMultipartRaw(
            String token,
            UUID versionId,
            String platform,
            String fileName,
            Map<String, String> provenance,
            byte[] bytes) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("version_id", versionId.toString());
        parts.add("platform", platform);
        provenance.forEach(parts::add);
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

    /** The multipart leg under a designated-POST key (the replay probe). */
    private Recording multipartWithIdempotencyKey(
            String token, String key, UUID versionId, String platform, String fileName, Map<String, String> provenance)
            throws java.io.IOException {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("version_id", versionId.toString());
        parts.add("platform", platform);
        provenance.forEach(parts::add);
        parts.add("file", new ByteArrayResource(IpaFixtures.unsignedIpa()) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        return client.post()
                .uri("/api/v1/artifacts")
                .headers(headers -> {
                    headers.setBearerAuth(token);
                    headers.set("Idempotency-Key", key);
                })
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .exchange((request, response) -> {
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
                .uri(java.net.URI.create(uploadUrl))
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

    private UUID createOrg(String orgName) {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        return UUID.fromString(post(devToken, "/api/v1/organizations", Map.of("name", orgName), 201)
                .get("id")
                .asText());
    }

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
                        "Provenance " + role))
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

    private record Recording(int status, String contentType, JsonNode body) {

        JsonNode expect(int expectedStatus) {
            assertThat(status).as("HTTP status for %s", body.toString()).isEqualTo(expectedStatus);
            return body;
        }
    }
}
