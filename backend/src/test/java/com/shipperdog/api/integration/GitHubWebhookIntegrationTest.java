package com.shipperdog.api.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.AuditCapture;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
import tools.jackson.databind.node.ObjectNode;

/**
 * The inbound GitHub receiver's acceptance flow (issue #53, S12-03,
 * FR-022, PRD 19): the signed-delivery contract end to end -- the
 * uniform 401 of every authenticity failure (bad key, absent header,
 * malformed header -- indistinguishable, and never a hint that a
 * secret is or is not configured), the 400 of a verified sender's
 * unreadable body, the 200 receipts of unknown events (GitHub's
 * 2xx-or-retry contract), the delivery-ledger idempotency (a replayed
 * X-GitHub-Delivery answers DUPLICATE without reprocessing), and the
 * workflow_run enrichment (a completed run backfills ci_run_url and
 * pipeline onto the artifacts its head_sha built, backfill-only --
 * asserted values are never overwritten).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef",
            "shipperdog.webhooks.github-secrets=fixture-webhook-secret-one,fixture-webhook-secret-two"
        })
@Testcontainers
@Import(AuditCapture.class)
class GitHubWebhookIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";
    private static final String PASSWORD = "a-perfectly-valid-password";

    private static final String SECRET = "fixture-webhook-secret-one";
    private static final String WRONG_SECRET = "a-secret-the-sender-never-had";
    private static final String SHA1 = "0123456789abcdef0123456789abcdef01234567";

    /** The compose GCS emulator image, in a container (ADR-0003) -- the multipart upload path stores bytes. */
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
        // The ledger is append-only history by design; the test class
        // shares one context, so each method starts from an empty
        // ledger (the count assertions are per-delivery, not global).
        jdbc.execute("DELETE FROM inbound_webhook_deliveries");
    }

    // -----------------------------------------------------------------------
    // AC: the receiver verifies GitHub signatures; unauthenticated
    // posts are rejected -- uniformly, never a hint
    // -----------------------------------------------------------------------

    @Test
    void wrongKeyAbsentHeaderAndMalformedHeaderAllAnswerTheSame401() {
        ObjectNode payload = workflowRun("completed", SHA1, "https://github.com/acme/app/actions/runs/842");
        byte[] body = bodyOf(payload);
        for (String header : Arrays.asList(signature(WRONG_SECRET, body), null, "sha256=zz")) {
            Recording answer = deliver(body, header, "d-" + UUID.randomUUID(), "workflow_run");
            assertThat(answer.status).as("header [%s]", header).isEqualTo(401);
            assertThat(answer.contentType).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            assertThat(answer.body.get("code").asText()).isEqualTo("WEBHOOK_SIGNATURE_REJECTED");
        }
        assertThat(audit.eventsOfType(AuditEventType.INBOUND_WEBHOOK_REJECTED)).hasSize(3);
        // Nothing processed: no ledger row, no artifact was touched.
        assertThat(ledgerCount()).isZero();
    }

    // -----------------------------------------------------------------------
    // AC: processed events are idempotent (the delivery ledger)
    // -----------------------------------------------------------------------

    @Test
    void replayedDeliveryIdAnswersDuplicateWithoutReprocessing() {
        byte[] body = bodyOf(workflowRun("completed", SHA1, "https://github.com/acme/app/actions/runs/842"));
        String delivery = "11111111-2222-4333-8444-555555555555";
        Recording first = deliver(body, signature(SECRET, body), delivery, "workflow_run");
        assertThat(first.status).isEqualTo(200);
        Recording replay = deliver(body, signature(SECRET, body), delivery, "workflow_run");
        assertThat(replay.status).isEqualTo(200);
        assertThat(replay.body.get("status").asText()).isEqualTo("DUPLICATE");
        assertThat(ledgerCount()).isEqualTo(1);
        assertThat(audit.eventsOfType(AuditEventType.INBOUND_WEBHOOK_DUPLICATE)).hasSize(1);
        // A different delivery id carrying the same run is NOT a
        // duplicate (GitHub re-delivers under new ids after sender
        // retries): it processes -- and the enrichment's own
        // backfill-only rule is the no-op it was before.
        Recording other =
                deliver(body, signature(SECRET, body), "99999999-8888-4777-8666-555555555555", "workflow_run");
        assertThat(other.status).isEqualTo(200);
        assertThat(ledgerCount()).isEqualTo(2);
    }

    // -----------------------------------------------------------------------
    // Unknown events are acknowledged (2xx) and ignored -- GitHub's
    // contract -- and a verified sender's unreadable body answers 400
    // -----------------------------------------------------------------------

    @Test
    void unknownEventIsAcknowledgedAndIgnored() {
        byte[] body = bodyOf(mapper.createObjectNode().put("ref", "refs/heads/main"));
        Recording answer = deliver(body, signature(SECRET, body), "d-" + UUID.randomUUID(), "push");
        assertThat(answer.status).isEqualTo(200);
        assertThat(answer.body.get("status").asText()).isEqualTo("IGNORED");
        assertThat(jdbc.queryForObject(
                        "SELECT outcome FROM inbound_webhook_deliveries WHERE event_type = 'push'", String.class))
                .isEqualTo("IGNORED");
        assertThat(audit.eventsOfType(AuditEventType.INBOUND_WEBHOOK_IGNORED)).hasSize(1);
    }

    @Test
    void verifiedSendersUnreadableBodyAnswers400() {
        byte[] body = "{not-json".getBytes(StandardCharsets.UTF_8);
        Recording answer = deliver(body, signature(SECRET, body), "d-" + UUID.randomUUID(), "workflow_run");
        assertThat(answer.status).isEqualTo(400);
        assertThat(answer.body.get("code").asText()).isEqualTo("WEBHOOK_PAYLOAD_MALFORMED");
        assertThat(ledgerCount()).isZero();
    }

    // -----------------------------------------------------------------------
    // AC: workflow_run (completed) enriches artifact provenance --
    // backfill-only, matched by the frozen git_commit claim
    // -----------------------------------------------------------------------

    @Test
    void completedRunBackfillsRunUrlAndPipelineOntoMatchingArtifacts() throws Exception {
        String commit = uniqueCommit();
        Uploaded uploaded = uploadWorldWithCommitOnly(commit);
        awaitSettled(uploaded);
        byte[] body = bodyOf(workflowRun("completed", commit, "https://github.com/acme/app/actions/runs/842"));
        Recording answer = deliver(body, signature(SECRET, body), "d-" + UUID.randomUUID(), "workflow_run");
        assertThat(answer.status).isEqualTo(200);
        assertThat(answer.body.get("status").asText()).isEqualTo("ACCEPTED");

        JsonNode read = get(
                uploaded.token(),
                "/api/v1/artifacts/" + uploaded.artifactId() + "?version_id=" + uploaded.versionId(),
                200);
        JsonNode provenance = read.get("provenance");
        assertThat(provenance.get("ciRunUrl").asText()).isEqualTo("https://github.com/acme/app/actions/runs/842");
        assertThat(provenance.get("pipeline").asText()).isEqualTo("mobile-release");
        // Backfill-only: the uploader's own claims are untouched.
        assertThat(provenance.get("gitCommit").asText()).isEqualTo(commit);

        assertThat(audit.eventsOfType(AuditEventType.ARTIFACT_PROVENANCE_ENRICHED))
                .hasSize(1);
        assertThat(audit.eventsOfType(AuditEventType.ARTIFACT_PROVENANCE_ENRICHED)
                        .get(0)
                        .detail()
                        .toString())
                .doesNotContain("fixture-webhook-secret");
    }

    @Test
    void enrichmentNeverOverwritesAssertedValuesAndIgnoresForeignCommits() throws Exception {
        // The uploader asserted its own pipeline: the run's name must
        // NOT replace it; the missing ci_run_url IS filled.
        String commit = uniqueCommit();
        Uploaded uploaded = uploadWorld(Map.of("git_commit", commit, "pipeline", "our-internal-nightly"));
        awaitSettled(uploaded);
        byte[] body = bodyOf(workflowRun("completed", commit, "https://github.com/acme/app/actions/runs/900"));
        Recording answer = deliver(body, signature(SECRET, body), "d-" + UUID.randomUUID(), "workflow_run");
        assertThat(answer.status).isEqualTo(200);

        JsonNode read = get(
                uploaded.token(),
                "/api/v1/artifacts/" + uploaded.artifactId() + "?version_id=" + uploaded.versionId(),
                200);
        assertThat(read.get("provenance").get("pipeline").asText()).isEqualTo("our-internal-nightly");
        assertThat(read.get("provenance").get("ciRunUrl").asText())
                .isEqualTo("https://github.com/acme/app/actions/runs/900");

        // A run for a commit nothing was built from, and a
        // non-completed action, are acknowledged no-ops (IGNORED).
        byte[] foreign = bodyOf(workflowRun(
                "completed",
                "fedcba9876543210fedcba9876543210fedcba98",
                "https://github.com/acme/app/actions/runs/901"));
        assertThat(deliver(foreign, signature(SECRET, foreign), "d-" + UUID.randomUUID(), "workflow_run")
                        .body
                        .get("status")
                        .asText())
                .isEqualTo("IGNORED");
        byte[] requested = bodyOf(workflowRun("requested", commit, "https://github.com/acme/app/actions/runs/902"));
        assertThat(deliver(requested, signature(SECRET, requested), "d-" + UUID.randomUUID(), "workflow_run")
                        .body
                        .get("status")
                        .asText())
                .isEqualTo("IGNORED");
    }

    @Test
    void degradedWorkflowRunShapesAreIgnoredAndNeverPoisonTheDocument() throws Exception {
        String commit = uniqueCommit();
        Uploaded uploaded = uploadWorldWithCommitOnly(commit);
        awaitSettled(uploaded);
        String uri = "/api/v1/artifacts/" + uploaded.artifactId() + "?version_id=" + uploaded.versionId();

        // Each degraded shape degrades to IGNORED -- a logged no-op,
        // never a failure the sender would retry, never a partial
        // write into the frozen document.
        byte[] badSha = bodyOf(workflowRun("completed", "not-a-sha", "https://github.com/acme/app/actions/runs/910"));
        byte[] badUrl = bodyOf(workflowRun("completed", commit, "not a url"));
        ObjectNode noRun = mapper.createObjectNode();
        noRun.put("action", "completed");
        byte[] missingRun = bodyOf(noRun);
        for (byte[] body : List.of(badSha, badUrl, missingRun)) {
            Recording answer = deliver(body, signature(SECRET, body), "d-" + UUID.randomUUID(), "workflow_run");
            assertThat(answer.status).isEqualTo(200);
            assertThat(answer.body.get("status").asText()).isEqualTo("IGNORED");
        }
        JsonNode untouched = get(uploaded.token(), uri, 200);
        assertThat(untouched.get("provenance").get("ciRunUrl").isNull()).isTrue();
        assertThat(untouched.get("provenance").get("pipeline").isNull()).isTrue();

        // An unusable workflow NAME (beyond the 255 printable ceiling)
        // degrades to "no pipeline backfill" -- the run URL still
        // lands: the two fields are independent backfills.
        ObjectNode longName = workflowRun("completed", commit, "https://github.com/acme/app/actions/runs/911");
        ((ObjectNode) longName.get("workflow_run")).put("name", "x".repeat(300));
        byte[] longNameBody = bodyOf(longName);
        assertThat(deliver(longNameBody, signature(SECRET, longNameBody), "d-" + UUID.randomUUID(), "workflow_run")
                        .body
                        .get("status")
                        .asText())
                .isEqualTo("ACCEPTED");
        JsonNode partial = get(uploaded.token(), uri, 200);
        assertThat(partial.get("provenance").get("ciRunUrl").asText())
                .isEqualTo("https://github.com/acme/app/actions/runs/911");
        assertThat(partial.get("provenance").get("pipeline").isNull()).isTrue();

        // The world was healthy all along: a well-formed delivery for
        // the same commit fills the still-absent pipeline (proves the
        // IGNORED answers above were the payloads' shapes, not a
        // broken fixture).
        byte[] good = bodyOf(workflowRun("completed", commit, "https://github.com/acme/app/actions/runs/912"));
        assertThat(deliver(good, signature(SECRET, good), "d-" + UUID.randomUUID(), "workflow_run")
                        .body
                        .get("status")
                        .asText())
                .isEqualTo("ACCEPTED");
        JsonNode completed = get(uploaded.token(), uri, 200);
        assertThat(completed.get("provenance").get("pipeline").asText()).isEqualTo("mobile-release");
        assertThat(completed.get("provenance").get("ciRunUrl").asText())
                .isEqualTo("https://github.com/acme/app/actions/runs/911"); // the first valid URL stays
    }

    // -----------------------------------------------------------------------
    // Helpers (the provenance suite's harness shape)
    // -----------------------------------------------------------------------

    private record Uploaded(UUID versionId, UUID artifactId, String token) {}

    /** Uploads one unsigned IPA carrying exactly the provenance claims given. */
    private Uploaded uploadWorld(Map<String, String> provenance) throws java.io.IOException {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        UUID orgId = UUID.fromString(
                post(devToken, "/api/v1/organizations", Map.of("name", "Webhook Corp " + UUID.randomUUID()), 201)
                        .get("id")
                        .asText());
        UUID tenantId = UUID.fromString(post(
                        devToken,
                        "/api/v1/tenants",
                        Map.of("organizationId", orgId.toString(), "name", "Acme", "externalId", "crm-" + orgId),
                        201)
                .get("id")
                .asText());
        Map<String, Object> appBody = new HashMap<>();
        appBody.put("tenantId", tenantId.toString());
        appBody.put("name", "Banking");
        appBody.put("platforms", List.of("IOS"));
        // The fixture plist's identifier (IpaFixtures.BUNDLE_ID): the
        // validation pipeline's registry stage refuses a mismatch.
        appBody.put("bundleId", com.shipperdog.api.artifact.IpaFixtures.BUNDLE_ID);
        appBody.put("packageName", null);
        UUID appId = UUID.fromString(
                post(devToken, "/api/v1/applications", appBody, 201).get("id").asText());
        UUID versionId = UUID.fromString(
                post(devToken, "/api/v1/applications/" + appId + "/versions", Map.of("versionName", "7.12.0"), 201)
                        .get("id")
                        .asText());

        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("version_id", versionId.toString());
        parts.add("platform", "IOS");
        provenance.forEach(parts::add);
        parts.add("file", new ByteArrayResource(com.shipperdog.api.artifact.IpaFixtures.releaseIpa()) {
            @Override
            public String getFilename() {
                return "bank.ipa";
            }
        });
        JsonNode artifact = client.post()
                .uri("/api/v1/artifacts")
                .headers(headers -> headers.setBearerAuth(devToken))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .exchange((request, response) ->
                        mapper.readTree(response.getBody().readAllBytes()));
        return new Uploaded(versionId, UUID.fromString(artifact.get("id").asText()), devToken);
    }

    private Uploaded uploadWorldWithCommitOnly(String commit) throws Exception {
        return uploadWorld(Map.of("git_commit", commit));
    }

    /**
     * Polls the artifact read until validation settles -- the real CI
     * shape (the upload action and CLI poll to settlement), and the
     * precondition of the enrichment itself: a backfill racing the
     * async extraction pipeline's writes would be clobbered by its
     * entity merges, so the enrichment targets settled artifacts only.
     */
    private void awaitSettled(Uploaded uploaded) {
        String uri = "/api/v1/artifacts/" + uploaded.artifactId() + "?version_id=" + uploaded.versionId();
        for (int attempt = 0; attempt < 120; attempt++) {
            String status = get(uploaded.token(), uri, 200).get("status").asText();
            if (!"UPLOADING".equals(status) && !"VALIDATING".equals(status)) {
                assertThat(status).isEqualTo("READY");
                return;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("artifact did not settle within 60s");
    }

    /**
     * A unique well-shaped SHA-1 per caller: the backfill match is by
     * commit across every organization (the webhook is not scoped), so
     * each test's artifacts answer only its own delivery.
     */
    private static String uniqueCommit() {
        return (UUID.randomUUID().toString().replace("-", "") + "0000000000000000000000000000000000000000")
                .substring(0, 40);
    }

    /** The workflow_run payload shape: a completed run of the mobile-release workflow. */
    private ObjectNode workflowRun(String action, String headSha, String htmlUrl) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("action", action);
        ObjectNode run = payload.putObject("workflow_run");
        run.put("head_sha", headSha);
        run.put("html_url", htmlUrl);
        run.put("name", "mobile-release");
        payload.putObject("workflow").put("name", "mobile-release");
        return payload;
    }

    private byte[] bodyOf(ObjectNode payload) {
        return payload.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Posts one delivery to the receiver; a null signature header is the absent-header leg. */
    private Recording deliver(byte[] body, String signatureHeader, String deliveryId, String event) {
        RestClient.RequestBodySpec spec = client.post().uri("/webhooks/github");
        if (signatureHeader != null) {
            spec.header("X-Hub-Signature-256", signatureHeader);
        }
        return spec.header("X-GitHub-Delivery", deliveryId)
                .header("X-GitHub-Event", event)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) -> {
                    byte[] raw = response.getBody().readAllBytes();
                    return new Recording(
                            response.getStatusCode().value(),
                            response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                            raw.length == 0 ? mapper.createObjectNode() : mapper.readTree(raw));
                });
    }

    private int ledgerCount() {
        return jdbc.queryForObject("SELECT count(*) FROM inbound_webhook_deliveries", Integer.class);
    }

    private static String signature(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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

    private JsonNode post(String bearer, String uri, Map<String, Object> body, int expectedStatus) {
        Recording answer = client.method(HttpMethod.POST)
                .uri(uri)
                .headers(headers -> headers.setBearerAuth(bearer))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) -> {
                    byte[] bytes = response.getBody().readAllBytes();
                    return new Recording(
                            response.getStatusCode().value(),
                            response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                            bytes.length == 0 ? mapper.createObjectNode() : mapper.readTree(bytes));
                });
        assertThat(answer.status).as("POST %s -> %s", uri, answer.body).isEqualTo(expectedStatus);
        return answer.body;
    }

    private JsonNode get(String bearer, String uri, int expectedStatus) {
        Recording answer = client.get()
                .uri(uri)
                .headers(headers -> headers.setBearerAuth(bearer))
                .exchange((request, response) -> {
                    byte[] bytes = response.getBody().readAllBytes();
                    return new Recording(
                            response.getStatusCode().value(),
                            response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                            bytes.length == 0 ? mapper.createObjectNode() : mapper.readTree(bytes));
                });
        assertThat(answer.status).as("GET %s -> %s", uri, answer.body).isEqualTo(expectedStatus);
        return answer.body;
    }

    private record Recording(int status, String contentType, JsonNode body) {}
}
