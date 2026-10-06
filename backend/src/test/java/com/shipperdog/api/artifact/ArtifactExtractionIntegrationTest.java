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
 * End-to-end acceptance flow of artifact metadata extraction (issue
 * #21, S5-02, FR-005): a real IPA (programmatic fixture) uploads
 * through the real API into the real emulator-backed object store,
 * the async extraction job parses it off the request path, and the
 * outcome becomes visible everywhere it must — the artifact read
 * (extraction status PENDING to SUCCESS, the S5-02 read-back surface),
 * the persisted V16 columns and jsonb documents, the version views'
 * extended artifact slots, the ARTIFACT_METADATA_EXTRACTED audit
 * trail, and the extraction-duration metric the PRD 42 SLO observes.
 *
 * <p>The failure leg (AC3) pins the VALIDATING to FAILED transition
 * with the actionable error — a non-zip renamed {@code .ipa}, exactly
 * the fixture-matrix leg — and the INV-003 corollary: the stored
 * bytes and the artifact row survive the failure. The resumable path
 * leg proves finalize triggers extraction too (both registration
 * points). The multipart threshold sits at 4 KiB so the padded
 * fixture forces the session path with kilobytes.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
@Import(AuditCapture.class)
class ArtifactExtractionIntegrationTest {

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

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        audit.reset();
    }

    // -----------------------------------------------------------------------
    // AC1 + AC2: async extraction lands every field, everywhere it must
    // -----------------------------------------------------------------------

    @Test
    void multipartUploadExtractsIpaMetadataAsynchronously() throws Exception {
        Uploaded uploaded = uploadWorld("ae-happy");
        byte[] ipa = IpaFixtures.releaseIpa();

        JsonNode response = uploadMultipart(uploaded.token(), uploaded.versionId(), "app.ipa", null, ipa, 201);
        UUID artifactId = UUID.fromString(response.get("id").asText());
        assertThat(response.get("extraction").get("status").asText())
                .as("parsing is async by the story's scope; the upload answer can only show PENDING")
                .isEqualTo("PENDING");
        assertThat(response.get("duplicateOfTruncated").asBoolean()).isFalse();

        JsonNode settled = awaitExtraction(uploaded, artifactId, "SUCCESS");
        assertThat(settled.get("bundleId").asText()).isEqualTo(IpaFixtures.BUNDLE_ID);
        assertThat(settled.get("versionName").asText()).isEqualTo(IpaFixtures.VERSION_NAME);
        assertThat(settled.get("minimumOs").asText()).isEqualTo(IpaFixtures.MINIMUM_OS);
        assertThat(settled.get("buildNumber").asLong()).isEqualTo(421L);
        assertThat(settled.get("status").asText()).isEqualTo("VALIDATING");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT bundle_id, version_name, minimum_os, build_number, extracted_metadata, extraction_error, status "
                        + "FROM artifacts WHERE id = ?",
                artifactId);
        assertThat(row.get("bundle_id")).isEqualTo(IpaFixtures.BUNDLE_ID);
        assertThat(row.get("version_name")).isEqualTo(IpaFixtures.VERSION_NAME);
        assertThat(row.get("minimum_os")).isEqualTo(IpaFixtures.MINIMUM_OS);
        assertThat(((Number) row.get("build_number")).longValue()).isEqualTo(421L);
        assertThat(row.get("extraction_error")).isNull();
        assertThat(row.get("status")).isEqualTo("VALIDATING");
        JsonNode document = mapper.readTree(String.valueOf(row.get("extracted_metadata")));
        assertThat(document.get("displayName").asText()).isEqualTo(IpaFixtures.DISPLAY_NAME);
        assertThat(document.get("parser").asText()).isEqualTo("ipa:dd-plist");
        assertThat(document.get("signing").get("profileName").asText()).isEqualTo(IpaFixtures.PROFILE_NAME);
        assertThat(document.get("signing").get("profileUuid").asText()).isEqualTo(IpaFixtures.PROFILE_UUID);
        assertThat(document.get("signing").get("teamIdentifiers").get(0).asText())
                .isEqualTo(IpaFixtures.TEAM_ID);
        assertThat(document.get("signing")
                        .get("entitlements")
                        .get("aps-environment")
                        .asText())
                .isEqualTo("production");
        assertThat(document.get("signing")
                        .get("entitlements")
                        .get("get-task-allow")
                        .asBoolean())
                .isFalse();

        JsonNode version = get(
                uploaded.token(),
                "/api/v1/applications/" + uploaded.applicationId() + "/versions/" + uploaded.versionId(),
                200);
        JsonNode slot = version.get("artifactsByPlatform").get("IOS").get(0);
        assertThat(slot.get("buildNumber").asLong()).isEqualTo(421L);
        assertThat(slot.get("bundleId").asText()).isEqualTo(IpaFixtures.BUNDLE_ID);
        assertThat(slot.get("versionName").asText()).isEqualTo(IpaFixtures.VERSION_NAME);
        assertThat(slot.get("minimumOs").asText()).isEqualTo(IpaFixtures.MINIMUM_OS);
        assertThat(slot.get("extraction").asText()).isEqualTo("SUCCESS");

        assertThat(audit.eventsOfType(AuditEventType.ARTIFACT_METADATA_EXTRACTED).stream()
                        .filter(event -> artifactId.equals(event.artifactId()))
                        .toList())
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.success()).isTrue();
                    assertThat(event.detail()).containsEntry("bundleId", IpaFixtures.BUNDLE_ID);
                });
        Timer timer = meters.get(MetricNames.ARTIFACT_EXTRACTION_DURATION)
                .tag("outcome", "success")
                .timer();
        assertThat(timer.count()).isGreaterThan(0);
    }

    // -----------------------------------------------------------------------
    // AC3: malformed IPA fails loudly, artifact FAILED, bytes kept
    // -----------------------------------------------------------------------

    @Test
    void malformedIpaFailsExtractionWithActionableError() throws Exception {
        Uploaded uploaded = uploadWorld("ae-fail");
        byte[] notZip = IpaFixtures.notAZip(4096);

        JsonNode response = uploadMultipart(uploaded.token(), uploaded.versionId(), "broken.ipa", null, notZip, 201);
        UUID artifactId = UUID.fromString(response.get("id").asText());

        JsonNode settled = awaitExtraction(uploaded, artifactId, "FAILED");
        assertThat(settled.get("status").asText()).isEqualTo("FAILED");
        assertThat(settled.get("extraction").get("errorCode").asText()).isEqualTo("NOT_A_ZIP");
        assertThat(settled.get("extraction").get("errorMessage").asText()).contains("ZIP");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, bundle_id, extracted_metadata, extraction_error, storage_key FROM artifacts WHERE id = ?",
                artifactId);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("bundle_id")).isNull();
        assertThat(row.get("extracted_metadata")).isNull();
        JsonNode error = mapper.readTree(String.valueOf(row.get("extraction_error")));
        assertThat(error.get("code").asText()).isEqualTo("NOT_A_ZIP");
        assertThat(error.get("message").asText()).contains("ZIP");
        assertThat(error.has("occurredAt")).isTrue();
        // INV-003's corollary: a failed extraction never removes bytes.
        assertThat(row.get("storage_key")).asString().isNotBlank();

        assertThat(audit.eventsOfType(AuditEventType.ARTIFACT_METADATA_EXTRACTED).stream()
                        .filter(event -> artifactId.equals(event.artifactId()))
                        .toList())
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.success()).isFalse();
                    assertThat(event.detail()).containsEntry("errorCode", "NOT_A_ZIP");
                });
        assertThat(meters.get(MetricNames.ARTIFACT_EXTRACTION_DURATION)
                        .tag("outcome", "failed-parse")
                        .timer()
                        .count())
                .isGreaterThan(0);
    }

    // -----------------------------------------------------------------------
    // V15's nullable build: the CI-pinned value wins over extraction
    // -----------------------------------------------------------------------

    @Test
    void pinnedBuildNumbersWinOverExtractedOnes() throws Exception {
        Uploaded uploaded = uploadWorld("ae-pinned");
        JsonNode response =
                uploadMultipart(uploaded.token(), uploaded.versionId(), "app.ipa", 777L, IpaFixtures.releaseIpa(), 201);
        UUID artifactId = UUID.fromString(response.get("id").asText());

        JsonNode settled = awaitExtraction(uploaded, artifactId, "SUCCESS");
        assertThat(settled.get("buildNumber").asLong()).isEqualTo(777L);
        Long pinned = jdbc.queryForObject("SELECT build_number FROM artifacts WHERE id = ?", Long.class, artifactId);
        assertThat(pinned).isEqualTo(777L);
    }

    // -----------------------------------------------------------------------
    // Both registration points trigger extraction: the resumable leg
    // -----------------------------------------------------------------------

    @Test
    void resumableUploadExtractsAtFinalize() throws Exception {
        Uploaded uploaded = uploadWorld("ae-resumable");
        byte[] padded = IpaFixtures.paddedIpa(64 * 1024);

        Map<String, Object> session = new HashMap<>();
        session.put("versionId", uploaded.versionId().toString());
        session.put("platform", "IOS");
        session.put("fileName", "padded.ipa");
        JsonNode created = post(uploaded.token(), "/api/v1/artifacts/upload-sessions", session, 201);
        UUID artifactId = UUID.fromString(created.get("artifactId").asText());
        pushBytes(created.get("uploadUrl").asText(), padded);
        JsonNode finalized = post(
                uploaded.token(),
                "/api/v1/artifacts/" + artifactId + "/finalize",
                Map.of("versionId", uploaded.versionId().toString()),
                201);
        assertThat(finalized.get("created").asBoolean()).isTrue();

        JsonNode settled = awaitExtraction(uploaded, artifactId, "SUCCESS");
        assertThat(settled.get("bundleId").asText()).isEqualTo(IpaFixtures.BUNDLE_ID);
    }

    // -----------------------------------------------------------------------
    // The read's concealed miss: wrong version is ARTIFACT_NOT_FOUND
    // -----------------------------------------------------------------------

    @Test
    void artifactReadConcealsForeignVersions() throws Exception {
        Uploaded uploaded = uploadWorld("ae-conceal");
        Uploaded other = uploadWorld("ae-conceal-other");
        JsonNode response =
                uploadMultipart(uploaded.token(), uploaded.versionId(), "app.ipa", null, IpaFixtures.releaseIpa(), 201);
        UUID artifactId = UUID.fromString(response.get("id").asText());
        // Drain the async tail INSIDE this test: the extraction job's
        // audit event must never land in a later test's capture window
        // (the CI-runner race this await closes).
        awaitExtraction(uploaded, artifactId, "SUCCESS");

        // A foreign version conceal-denies at the permission hop with the
        // version target's own not-found code; an unknown artifact under
        // the caller's own version is the artifact module's miss.
        JsonNode foreign =
                get(uploaded.token(), "/api/v1/artifacts/" + artifactId + "?version_id=" + other.versionId(), 404);
        assertThat(foreign.get("code").asText()).isEqualTo("VERSION_NOT_FOUND");
        JsonNode unknownArtifact = get(
                uploaded.token(),
                "/api/v1/artifacts/" + UUID.randomUUID() + "?version_id=" + uploaded.versionId(),
                404);
        assertThat(unknownArtifact.get("code").asText()).isEqualTo("ARTIFACT_NOT_FOUND");
    }

    // -----------------------------------------------------------------------
    // Adversarial legs (tester pass)
    // -----------------------------------------------------------------------

    @Test
    void oversizedIdentifierFailsExtractionEndToEnd() throws Exception {
        Uploaded uploaded = uploadWorld("ae-oversized");
        JsonNode response = uploadMultipart(
                uploaded.token(), uploaded.versionId(), "big.ipa", null, IpaFixtures.oversizedIdentifierIpa(), 201);
        UUID artifactId = UUID.fromString(response.get("id").asText());

        JsonNode settled = awaitExtraction(uploaded, artifactId, "FAILED");
        assertThat(settled.get("extraction").get("errorCode").asText()).isEqualTo("MALFORMED_PLIST");
        assertThat(settled.get("extraction").get("errorMessage").asText()).contains("255");
        assertThat(settled.get("bundleId").isNull()).isTrue();
    }

    @Test
    void artifactReadRequiresTheVersionParameter() {
        Uploaded uploaded = uploadWorld("ae-param");
        client.get()
                .uri("/api/v1/artifacts/" + UUID.randomUUID())
                .headers(headers -> headers.setBearerAuth(uploaded.token()))
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(400);
                    return null;
                });
    }

    @Test
    void sequentialExtractionsAcrossOrganizationsShareThePoolSafely() throws Exception {
        // Two artifacts of two different organizations, extracted
        // back-to-back on the shared bounded pool: the second job's
        // scope establishment must not collide with a leaked binding
        // from the first (the seam is establish-only per thread).
        Uploaded first = uploadWorld("ae-pool-1");
        Uploaded second = uploadWorld("ae-pool-2");
        JsonNode one = uploadMultipart(first.token(), first.versionId(), "a.ipa", null, IpaFixtures.releaseIpa(), 201);
        JsonNode two =
                uploadMultipart(second.token(), second.versionId(), "b.ipa", null, IpaFixtures.unsignedIpa(), 201);

        assertThat(awaitExtraction(first, UUID.fromString(one.get("id").asText()), "SUCCESS")
                        .get("bundleId")
                        .asText())
                .isEqualTo(IpaFixtures.BUNDLE_ID);
        assertThat(awaitExtraction(second, UUID.fromString(two.get("id").asText()), "SUCCESS")
                        .get("bundleId")
                        .asText())
                .isEqualTo(IpaFixtures.BUNDLE_ID);
    }

    // -----------------------------------------------------------------------
    // Harness
    // -----------------------------------------------------------------------

    private JsonNode awaitExtraction(Uploaded uploaded, UUID artifactId, String outcome) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        JsonNode last = null;
        while (System.currentTimeMillis() < deadline) {
            last = get(
                    uploaded.token(), "/api/v1/artifacts/" + artifactId + "?version_id=" + uploaded.versionId(), 200);
            if (outcome.equals(last.get("extraction").get("status").asText())) {
                return last;
            }
            Thread.sleep(100);
        }
        return last;
    }

    /** One organization, tenant, iOS application, and version; a DEVELOPER token. */
    private Uploaded uploadWorld(String suffix) {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        UUID organizationId =
                UUID.fromString(post(devToken, "/api/v1/organizations", Map.of("name", "extraction-" + suffix), 201)
                        .get("id")
                        .asText());
        UUID tenantId = UUID.fromString(post(
                        devToken,
                        "/api/v1/tenants",
                        Map.of(
                                "organizationId", organizationId.toString(),
                                "name", "Extraction " + suffix,
                                "externalId", "ext-" + suffix),
                        201)
                .get("id")
                .asText());
        UUID applicationId = UUID.fromString(post(
                        devToken,
                        "/api/v1/applications",
                        Map.of(
                                "tenantId",
                                tenantId.toString(),
                                "name",
                                "Banking " + suffix,
                                "platforms",
                                List.of("IOS"),
                                "bundleId",
                                IpaFixtures.BUNDLE_ID),
                        201)
                .get("id")
                .asText());
        UUID versionId = UUID.fromString(post(
                        devToken,
                        "/api/v1/applications/" + applicationId + "/versions",
                        Map.of("versionName", IpaFixtures.VERSION_NAME),
                        201)
                .get("id")
                .asText());
        String email = "dev-" + suffix + "@shipperdog.test";
        String developerToken = loginOfOrgRoleIn(organizationId, email, "DEVELOPER");
        return new Uploaded(developerToken, organizationId, tenantId, applicationId, versionId);
    }

    private JsonNode uploadMultipart(
            String token, UUID versionId, String fileName, Long buildNumber, byte[] bytes, int expected) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("version_id", versionId.toString());
        parts.add("platform", "IOS");
        if (buildNumber != null) {
            parts.add("build_number", Long.toString(buildNumber));
        }
        parts.add("file", new org.springframework.core.io.ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        JsonNode body = client.post()
                .uri("/api/v1/artifacts")
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(expected);
                    return mapper.readTree(response.getBody().readAllBytes());
                });
        return body;
    }

    private void pushBytes(String uploadUrl, byte[] bytes) {
        client.put()
                .uri(uploadUrl)
                .header("Content-Range", "bytes 0-" + (bytes.length - 1) + "/" + bytes.length)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(bytes)
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(200);
                    return null;
                });
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
                        "Extraction " + role))
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
        return client.post()
                .uri(uri)
                .headers(headers -> headers.setBearerAuth(bearer))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode().value())
                            .as("POST %s", uri)
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

    private record Uploaded(String token, UUID organizationId, UUID tenantId, UUID applicationId, UUID versionId) {}
}
