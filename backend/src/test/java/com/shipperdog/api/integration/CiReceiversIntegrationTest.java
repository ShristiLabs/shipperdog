package com.shipperdog.api.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.config.MetricNames;
import com.shipperdog.api.identity.AuditCapture;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
 * The S12-04 CI receivers' acceptance flow (issue #54, FR-022, PRD
 * 19): Jenkins (the receiver-defined HMAC contract) and GitLab (the
 * provider's token protocol) end to end -- the shared-secret family's
 * uniform 401 over every authenticity failure, the 400s of a verified
 * sender's unreadable body or missing delivery id, the stale-
 * timestamp replay rejection, the delivery-ledger idempotency, and
 * the SUCCESS-build/successful-pipeline provenance backfill by
 * commit (backfill-only, the GitHub receiver's exact contract), with
 * the S12-04 hardening observable: a metric increment per answered
 * delivery and an audit event per outcome.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef",
            "shipperdog.webhooks.jenkins-secrets=fixture-jenkins-secret",
            "shipperdog.webhooks.gitlab-secrets=fixture-gitlab-token"
        })
@Testcontainers
@Import(AuditCapture.class)
class CiReceiversIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";

    private static final String JENKINS_SECRET = "fixture-jenkins-secret";
    private static final String GITLAB_TOKEN = "fixture-gitlab-token";

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
    private MeterRegistry meters;

    @Autowired
    private ObjectMapper mapper;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        audit.reset();
        jdbc.execute("DELETE FROM inbound_webhook_deliveries");
        jdbc.execute("DELETE FROM store_status_events");
    }

    // -----------------------------------------------------------------------
    // Jenkins: the receiver-defined HMAC contract
    // -----------------------------------------------------------------------

    @Test
    void jenkinsAuthenticityFailuresAllAnswerTheSame401() {
        ObjectNode payload = jenkinsBuild("SUCCESS", uniqueCommit(), "now");
        byte[] body = bodyOf(payload);
        for (String header : Arrays.asList(signature("a-secret-the-sender-never-had", body), null, "sha256=zz")) {
            Recording answer = deliverJenkins(body, header);
            assertThat(answer.status).as("header [%s]", header).isEqualTo(401);
            assertThat(answer.body.get("code").asText()).isEqualTo("WEBHOOK_SIGNATURE_REJECTED");
        }
        assertThat(audit.eventsOfType(AuditEventType.INBOUND_WEBHOOK_REJECTED))
                .as("one rejection audit per answered delivery")
                .hasSize(3);
        assertThat(ledgerCount("jenkins")).isZero();
    }

    @Test
    void jenkinsVerifiedSendersUnreadableBodyOrMissingDeliveryIdAnswers400() {
        byte[] unreadable = "{not-json".getBytes(StandardCharsets.UTF_8);
        assertThat(deliverJenkins(unreadable, signature(JENKINS_SECRET, unreadable)).status)
                .isEqualTo(400);
        // A verified, readable delivery without its deliveryId field
        // is the same 400 family (the sender needs the diagnostic).
        byte[] idless = bodyOf(mapper.createObjectNode().put("result", "SUCCESS"));
        Recording answer = deliverJenkins(idless, signature(JENKINS_SECRET, idless));
        assertThat(answer.status).isEqualTo(400);
        assertThat(answer.body.get("code").asText()).isEqualTo("WEBHOOK_PAYLOAD_MALFORMED");
        assertThat(ledgerCount("jenkins")).isZero();
    }

    @Test
    void jenkinsStaleBuildTimestampIsTheUniform401AndNothingIsLedgered() {
        byte[] stale = bodyOf(jenkinsBuild("SUCCESS", uniqueCommit(), "now-600"));
        Recording answer = deliverJenkins(stale, signature(JENKINS_SECRET, stale));
        assertThat(answer.status).isEqualTo(401);
        assertThat(answer.body.get("code").asText()).isEqualTo("WEBHOOK_SIGNATURE_REJECTED");
        assertThat(ledgerCount("jenkins")).isZero();
    }

    @Test
    void jenkinsReplayedDeliveryIdAnswersDuplicateWithoutReprocessing() {
        byte[] body = bodyOf(jenkinsBuild("SUCCESS", "fedcba9876543210fedcba9876543210fedcba98", "now"));
        String id = "jenkins-" + UUID.randomUUID();
        assertThat(deliverJenkins(body, signature(JENKINS_SECRET, body), id).status)
                .isEqualTo(200);
        Recording replay = deliverJenkins(body, signature(JENKINS_SECRET, body), id);
        assertThat(replay.body.get("status").asText()).isEqualTo("DUPLICATE");
        assertThat(ledgerCount("jenkins")).isEqualTo(1);
        assertThat(audit.eventsOfType(AuditEventType.INBOUND_WEBHOOK_DUPLICATE)).hasSize(1);
    }

    @Test
    void jenkinsNonSuccessBuildIsAcknowledgedAndIgnored() {
        byte[] body = bodyOf(jenkinsBuild("FAILURE", uniqueCommit(), "now"));
        Recording answer = deliverJenkins(body, signature(JENKINS_SECRET, body));
        assertThat(answer.status).isEqualTo(200);
        assertThat(answer.body.get("status").asText()).isEqualTo("IGNORED");
        assertThat(audit.eventsOfType(AuditEventType.INBOUND_WEBHOOK_IGNORED)).hasSize(1);
    }

    @Test
    void jenkinsSuccessWithADegradedCommitShapeIsIgnoredNeverPoisonsTheDocument() {
        byte[] body = bodyOf(jenkinsBuild("SUCCESS", "not-a-sha", "now"));
        Recording answer = deliverJenkins(body, signature(JENKINS_SECRET, body));
        assertThat(answer.status).isEqualTo(200);
        assertThat(answer.body.get("status").asText()).isEqualTo("IGNORED");
        // The degraded build never ledgered as acted-on.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM inbound_webhook_deliveries WHERE provider = 'jenkins' "
                                + "AND outcome = 'ACCEPTED'",
                        Integer.class))
                .isZero();
    }

    @Test
    void jenkinsSuccessBuildBackfillsProvenanceByCommit() throws Exception {
        String commit = uniqueCommit();
        Uploaded uploaded = uploadWorldWithCommitOnly(commit);
        awaitSettled(uploaded);
        byte[] body = bodyOf(jenkinsBuild("SUCCESS", commit, "now"));
        Recording answer = deliverJenkins(body, signature(JENKINS_SECRET, body));
        assertThat(answer.status).isEqualTo(200);
        assertThat(answer.body.get("status").asText()).isEqualTo("ACCEPTED");

        JsonNode read = get(
                uploaded.token(),
                "/api/v1/artifacts/" + uploaded.artifactId() + "?version_id=" + uploaded.versionId(),
                200);
        assertThat(read.get("provenance").get("ciRunUrl").asText())
                .isEqualTo("https://jenkins.example.com/job/mobile/42/");
        assertThat(read.get("provenance").get("pipeline").asText()).isEqualTo("mobile");
        assertThat(read.get("provenance").get("gitCommit").asText()).isEqualTo(commit);
        assertThat(audit.eventsOfType(AuditEventType.ARTIFACT_PROVENANCE_ENRICHED))
                .hasSize(1);
        // The S12-04 hardening: the answered delivery is metered.
        assertThat(counted("jenkins", "accepted")).isEqualTo(1.0);
    }

    // -----------------------------------------------------------------------
    // GitLab: the provider's token protocol
    // -----------------------------------------------------------------------

    @Test
    void gitlabTokenFailuresAllAnswerTheSame401() {
        byte[] body = bodyOf(gitlabPipeline("success", uniqueCommit(), "now"));
        for (String token : Arrays.asList("a-token-the-sender-never-had", null, "  ")) {
            Recording answer = deliverGitlab(body, token, "g-" + UUID.randomUUID(), "Pipeline Hook");
            assertThat(answer.status).as("token [%s]", token).isEqualTo(401);
            assertThat(answer.body.get("code").asText()).isEqualTo("WEBHOOK_SIGNATURE_REJECTED");
        }
        assertThat(audit.eventsOfType(AuditEventType.INBOUND_WEBHOOK_REJECTED)).hasSize(3);
        assertThat(ledgerCount("gitlab")).isZero();
    }

    @Test
    void gitlabVerifiedSendersUnreadableBodyOrMissingDeliveryHeaderAnswers400() {
        byte[] unreadable = "{not-json".getBytes(StandardCharsets.UTF_8);
        assertThat(deliverGitlab(unreadable, GITLAB_TOKEN, "g-1", "Pipeline Hook").status)
                .isEqualTo(400);
        byte[] readable = bodyOf(gitlabPipeline("success", uniqueCommit(), "now"));
        Recording answer = deliverGitlab(readable, GITLAB_TOKEN, null, "Pipeline Hook");
        assertThat(answer.status).isEqualTo(400);
        assertThat(answer.body.get("code").asText()).isEqualTo("WEBHOOK_PAYLOAD_MALFORMED");
    }

    @Test
    void gitlabStalePipelineTimestampIsTheUniform401() {
        byte[] stale = bodyOf(gitlabPipeline("success", uniqueCommit(), "now-600"));
        Recording answer = deliverGitlab(stale, GITLAB_TOKEN, "g-2", "Pipeline Hook");
        assertThat(answer.status).isEqualTo(401);
        assertThat(ledgerCount("gitlab")).isZero();
    }

    @Test
    void gitlabNonPipelineEventsAreAcknowledgedAndIgnored() {
        ObjectNode push = mapper.createObjectNode();
        push.put("object_kind", "push");
        push.put("before", "0000000000000000000000000000000000000000");
        byte[] body = bodyOf(push);
        Recording answer = deliverGitlab(body, GITLAB_TOKEN, "g-" + UUID.randomUUID(), "Push Hook");
        assertThat(answer.status).isEqualTo(200);
        assertThat(answer.body.get("status").asText()).isEqualTo("IGNORED");
    }

    @Test
    void gitlabSuccessfulPipelineBackfillsProvenanceByCommitAndReplayIsDuplicate() throws Exception {
        String commit = uniqueCommit();
        Uploaded uploaded = uploadWorldWithCommitOnly(commit);
        awaitSettled(uploaded);
        byte[] body = bodyOf(gitlabPipeline("success", commit, "now"));
        String delivery = "g-" + UUID.randomUUID();
        Recording answer = deliverGitlab(body, GITLAB_TOKEN, delivery, "Pipeline Hook");
        assertThat(answer.status).isEqualTo(200);
        assertThat(answer.body.get("status").asText()).isEqualTo("ACCEPTED");

        JsonNode read = get(
                uploaded.token(),
                "/api/v1/artifacts/" + uploaded.artifactId() + "?version_id=" + uploaded.versionId(),
                200);
        assertThat(read.get("provenance").get("ciRunUrl").asText())
                .isEqualTo("https://gitlab.example.com/acme/mobile/-/pipelines/77");
        assertThat(read.get("provenance").get("pipeline").asText()).isEqualTo("acme/mobile");

        // GitLab redelivers under the SAME webhook uuid: DUPLICATE.
        Recording replay = deliverGitlab(body, GITLAB_TOKEN, delivery, "Pipeline Hook");
        assertThat(replay.body.get("status").asText()).isEqualTo("DUPLICATE");
        assertThat(counted("gitlab", "duplicate")).isEqualTo(1.0);
    }

    /**
     * The documented posture for timestamps GitLab stopped carrying
     * (a shape-evolved pipeline event): the token authenticates the
     * WHOLE body, so the ledger alone is the replay defense and a
     * well-shaped pipeline-success still enriches -- pinned here so
     * the assumption is a contract, not an accident.
     */
    @Test
    void gitlabPipelineEventWithoutEvaluableTimestampsStillProcesses() throws Exception {
        String commit = uniqueCommit();
        Uploaded uploaded = uploadWorldWithCommitOnly(commit);
        awaitSettled(uploaded);
        ObjectNode payload = gitlabPipeline("success", commit, "now");
        payload.remove("object_attributes");
        ObjectNode attributes = payload.putObject("object_attributes");
        attributes.put("status", "success");
        attributes.put("sha", commit);
        attributes.put("pipeline_url", "https://gitlab.example.com/acme/mobile/-/pipelines/78");
        byte[] body = bodyOf(payload);
        Recording answer = deliverGitlab(body, GITLAB_TOKEN, "g-" + UUID.randomUUID(), "Pipeline Hook");
        assertThat(answer.status).isEqualTo(200);
        assertThat(answer.body.get("status").asText()).isEqualTo("ACCEPTED");
        JsonNode read = get(
                uploaded.token(),
                "/api/v1/artifacts/" + uploaded.artifactId() + "?version_id=" + uploaded.versionId(),
                200);
        assertThat(read.get("provenance").get("ciRunUrl").asText())
                .isEqualTo("https://gitlab.example.com/acme/mobile/-/pipelines/78");
    }

    // -----------------------------------------------------------------------
    // Helpers (the GitHub receiver suite's harness shape, shared)
    // -----------------------------------------------------------------------

    private record Uploaded(UUID versionId, UUID artifactId, String token) {}

    /** The Jenkins contract's delivery: "now" (fresh) or "now-600" (stale). */
    private ObjectNode jenkinsBuild(String result, String commit, String timestamp) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("deliveryId", "jenkins-" + UUID.randomUUID());
        payload.put("result", result);
        payload.put("gitCommit", commit);
        payload.put("gitBranch", "main");
        payload.put("pipeline", "mobile");
        payload.put("buildUrl", "https://jenkins.example.com/job/mobile/42/");
        payload.put("buildTimestamp", epochSeconds(timestamp));
        return payload;
    }

    /** The GitLab pipeline hook's shape: "now" (fresh) or "now-600" (stale). */
    private ObjectNode gitlabPipeline(String status, String sha, String timestamp) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("object_kind", "pipeline");
        ObjectNode attributes = payload.putObject("object_attributes");
        attributes.put("status", status);
        attributes.put("sha", sha);
        attributes.put("ref", "main");
        attributes.put("pipeline_url", "https://gitlab.example.com/acme/mobile/-/pipelines/77");
        attributes.put("created_at", Instant.now().toString());
        attributes.put("finished_at", finishedAt(timestamp));
        payload.putObject("project").put("path_with_namespace", "acme/mobile");
        return payload;
    }

    private static String epochSeconds(String when) {
        return Long.toString(Instant.now().getEpochSecond() + offsetOf(when));
    }

    private static String finishedAt(String when) {
        return Instant.ofEpochSecond(Instant.now().getEpochSecond() + offsetOf(when))
                .toString();
    }

    /** "now" -> 0 seconds from the fixed base; "now-600" -> -600. */
    private static long offsetOf(String when) {
        return "now".equals(when) ? 0 : Long.parseLong(when.substring(4));
    }

    private byte[] bodyOf(ObjectNode payload) {
        return payload.toString().getBytes(StandardCharsets.UTF_8);
    }

    private Recording deliverJenkins(byte[] body, String signatureHeader) {
        return deliverJenkins(body, signatureHeader, null);
    }

    /**
     * Posts one Jenkins delivery. A non-null explicitDeliveryId pins
     * the payload's own deliveryId field (the replay test's grain) --
     * the body is edited and re-signed, because the HMAC covers the
     * exact bytes.
     */
    private Recording deliverJenkins(byte[] body, String signatureHeader, String explicitDeliveryId) {
        byte[] finalBody = body;
        String finalSignature = signatureHeader;
        if (explicitDeliveryId != null) {
            try {
                ObjectNode parsed = (ObjectNode) mapper.readTree(body);
                parsed.put("deliveryId", explicitDeliveryId);
                finalBody = bodyOf(parsed);
                finalSignature = signature(JENKINS_SECRET, finalBody);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        RestClient.RequestBodySpec spec = client.post().uri("/webhooks/jenkins");
        if (finalSignature != null) {
            spec.header("X-Shipperdog-Signature", finalSignature);
        }
        return spec.contentType(MediaType.APPLICATION_JSON)
                .body(finalBody)
                .exchange((request, response) ->
                        recording(response.getStatusCode().value(), response));
    }

    private Recording deliverGitlab(byte[] body, String token, String deliveryId, String event) {
        RestClient.RequestBodySpec spec = client.post().uri("/webhooks/gitlab");
        if (token != null) {
            spec.header("X-Gitlab-Token", token);
        }
        if (deliveryId != null) {
            spec.header("X-Gitlab-Webhook-UUID", deliveryId);
        }
        return spec.header("X-Gitlab-Event", event)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) ->
                        recording(response.getStatusCode().value(), response));
    }

    private Recording recording(int status, org.springframework.http.client.ClientHttpResponse response)
            throws java.io.IOException {
        byte[] raw = response.getBody().readAllBytes();
        return new Recording(
                status,
                response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                raw.length == 0 ? mapper.createObjectNode() : mapper.readTree(raw));
    }

    private int ledgerCount(String provider) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM inbound_webhook_deliveries WHERE provider = ?", Integer.class, provider);
        return count == null ? 0 : count;
    }

    private double counted(String provider, String outcome) {
        return meters.counter(MetricNames.WEBHOOK_DELIVERIES, "provider", provider, "outcome", outcome)
                .count();
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

    /** Uploads one unsigned IPA carrying exactly the provenance claims given. */
    private Uploaded uploadWorldWithCommitOnly(String commit) throws java.io.IOException {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        UUID orgId = UUID.fromString(
                post(devToken, "/api/v1/organizations", Map.of("name", "CI Corp " + UUID.randomUUID()), 201)
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
        parts.add("git_commit", commit);
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

    private static String uniqueCommit() {
        return (UUID.randomUUID().toString().replace("-", "") + "0000000000000000000000000000000000000000")
                .substring(0, 40);
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
                .exchange((request, response) ->
                        recording(response.getStatusCode().value(), response));
        assertThat(answer.status).as("POST %s -> %s", uri, answer.body).isEqualTo(expectedStatus);
        return answer.body;
    }

    private JsonNode get(String bearer, String uri, int expectedStatus) {
        Recording answer = client.get()
                .uri(uri)
                .headers(headers -> headers.setBearerAuth(bearer))
                .exchange((request, response) ->
                        recording(response.getStatusCode().value(), response));
        assertThat(answer.status).as("GET %s -> %s", uri, answer.body).isEqualTo(expectedStatus);
        return answer.body;
    }

    private record Recording(int status, String contentType, JsonNode body) {}
}
