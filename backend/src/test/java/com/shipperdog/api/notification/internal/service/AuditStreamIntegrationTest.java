package com.shipperdog.api.notification.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.environment.SecretReferenceResolver;
import com.shipperdog.api.identity.AuditCapture;
import com.shipperdog.api.notification.internal.bus.OutboxRelay;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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
 * The SIEM export acceptance flow (issue #71, S14-05, the story's six
 * ACs end to end): a real audited API action bridges onto the outbox,
 * the relay drains it, and the audit-stream dispatcher delivers the
 * versioned envelope to a local collector over a real wire -- HMAC
 * verified here exactly the way docs/siem.md tells a receiver to
 * (AC1/AC6) -- in the Splunk HEC wrapper with the collector's auth
 * header when the export asks for it; a collector that refuses
 * answers the retry/backoff ledger until the ceiling's visible
 * dead-letter, and the replay action re-enqueues it without loss
 * (AC3); the bucket batcher turns a backfill watermark into
 * versioned, bounded JSONL batches plus signed manifests whose
 * counts reconcile against the audit table (AC2); the configuration
 * surface is the organization administrator's alone and carries
 * secret REFERENCES, never material (AC5); and organizations without
 * exports never bridge at all (the outbox bound).
 *
 * <p>The receiver is the JDK's built-in HTTP server -- a real wire,
 * no new dependency (the notification delivery suite's pattern);
 * retries run with a near-zero backoff and a three-attempt ceiling so
 * exhaustion is a deterministic pass; the bucket writes go to the
 * compose GCS emulator image in a container (ADR-0003); secret
 * references resolve through the test resolver the production GCP
 * wiring replaces. Each test cleans its exports behind itself so the
 * shared world's stream never bleeds between scenarios.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef",
            "shipperdog.notifications.relay-enabled=false",
            "shipperdog.notifications.relay-fixed-delay=PT1H",
            "shipperdog.notifications.delivery-backoff=PT0.01S",
            "shipperdog.notifications.delivery-max-attempts=3",
            "shipperdog.notifications.audit-stream.batch-enabled=false",
            "shipperdog.notifications.audit-stream.batch-fixed-delay=PT1H",
            "shipperdog.notifications.audit-stream.batch-max-rows=2"
        })
@Testcontainers
@Import({AuditCapture.class, AuditStreamIntegrationTest.ResolvingSecrets.class})
class AuditStreamIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";
    private static final String PASSWORD = "a-perfectly-valid-password";

    static final String SIGNING_REF = "sm://test/audit-stream-signing";
    static final String SIGNING_SECRET = "siem-test-signing-secret-32-chars";
    static final String AUTH_REF = "sm://test/collector-token";
    static final String AUTH_VALUE = "Bearer the-hec-token-value";

    /** The compose GCS emulator image, in a container (ADR-0003) -- the bucket batches land here. */
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
    static void auditStreamStorage(DynamicPropertyRegistry registry) {
        registry.add(
                "shipperdog.notifications.audit-stream.storage.host",
                () -> "http://" + GCS.getHost() + ":" + GCS.getMappedPort(4443));
    }

    /** The production GCP Secret Manager wiring's stand-in: the references the fixtures use. */
    @TestConfiguration
    static class ResolvingSecrets {

        // Primary so the by-type injections everywhere resolve to the
        // fixture (the distribution module's store-backed resolver
        // also registers in this context; no probe here depends on it).
        @org.springframework.context.annotation.Primary
        @Bean
        SecretReferenceResolver auditStreamTestResolver() {
            return reference -> {
                if (SIGNING_REF.equals(reference)) {
                    return Optional.of(SIGNING_SECRET);
                }
                if (AUTH_REF.equals(reference)) {
                    return Optional.of(AUTH_VALUE);
                }
                return Optional.empty();
            };
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private AuditStreamDispatcher dispatcher;

    @Autowired
    private AuditStreamBucketExporter batcher;

    @Autowired
    private AuditCapture audit;

    @Autowired
    @Qualifier("auditStreamBucketStorage") private com.google.cloud.storage.Storage storage;

    private final ObjectMapper mapper = new ObjectMapper();

    private RestClient client;

    private HttpServer collector;

    private final List<Received> received = new CopyOnWriteArrayList<>();

    private final List<Instant> receivedAt = new CopyOnWriteArrayList<>();

    private final AtomicInteger respondStatus = new AtomicInteger(200);

    /** One received POST: the body bytes, the signature headers, and the header snapshot for the auth assertions. */
    record Received(byte[] body, String event, String timestamp, String signature, Map<String, List<String>> headers) {}

    private static UUID org;

    private static UUID otherOrg;

    private static String adminToken;

    private static String developerToken;

    @BeforeEach
    void setUp() throws Exception {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        audit.reset();
        received.clear();
        receivedAt.clear();
        respondStatus.set(200);
        collector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        collector.createContext("/collect", exchange -> {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            exchange.getRequestBody().transferTo(buffer);
            // The JDK server normalizes header-name casing; the
            // snapshot keeps a case-insensitive view so the auth and
            // signature assertions read what the wire carried.
            Map<String, List<String>> headers = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            headers.putAll(exchange.getRequestHeaders());
            received.add(new Received(
                    buffer.toByteArray(),
                    headers.getOrDefault("X-Shipperdog-Event", List.of()).get(0),
                    headers.getOrDefault("X-Shipperdog-Timestamp", List.of()).get(0),
                    headers.getOrDefault("X-Shipperdog-Signature", List.of()).get(0),
                    headers));
            receivedAt.add(Instant.now());
            byte[] answer = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(respondStatus.get(), answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        collector.start();
        if (org == null) {
            org = createOrg("Siem Stream Corp");
            otherOrg = createOrg("Siem Stream Foreign Corp");
            adminToken = orgRoleTokenIn(org, "siem-admin", "ORG_ADMIN");
            developerToken = orgRoleTokenIn(org, "siem-dev", "DEVELOPER");
        }
    }

    @AfterEach
    void tearDown() {
        // The scenario's exports never outlive it: the shared world's
        // stream stays silent between tests.
        JsonNode page = get(adminToken, exportsPath() + "?limit=50", 200);
        for (JsonNode item : page.get("items")) {
            exchange(HttpMethod.DELETE, exportsPath() + "/" + item.get("id").asString(), null, 204, adminToken);
        }
        relay.drainOnce();
        collector.stop(0);
    }

    // -----------------------------------------------------------------------
    // AC1 + AC6: the real-time signed stream, event -> outbox -> collector
    // -----------------------------------------------------------------------

    @Test
    void auditedActionStreamsToTheCollectorSignedAndInsideTheLatencyBound() {
        UUID exportId = createHttpsExport(Map.of(
                "kind",
                "HTTPS",
                "format",
                "SHIPPERDOG_JSON",
                "destinationUrl",
                collectorUrl(),
                "signingSecretRef",
                SIGNING_REF));
        // The export's own creation event streams too (the bridge
        // reads the new row inside the creating transaction); drain it
        // so the scenario below observes only its own events.
        relay.drainOnce();
        received.clear();

        Instant before = Instant.now();
        renameOrg("Siem Stream Corp " + UUID.randomUUID());
        relay.drainOnce();
        System.err.println("PROBE rows="
                + jdbc.queryForList(
                        "select relay_state, relay_attempts, event_name from notification_outbox where organization_id = '"
                                + org
                                + "'"));
        System.err.println("PROBE deliveries="
                + jdbc.queryForList("select status, attempts, last_error from audit_stream_deliveries"));
        assertThat(received).hasSize(1);
        Received delivery = received.get(0);
        assertThat(delivery.event()).isEqualTo("AUDIT_EVENT");
        assertThat(delivery.signature()).isEqualTo(hmac(delivery.timestamp(), delivery.body()));
        JsonNode envelope = mapper.readTree(delivery.body());
        assertThat(envelope.get("schema_version").asString()).isEqualTo("1.0");
        assertThat(envelope.get("event_type").asString()).isEqualTo("ORGANIZATION_UPDATED");
        assertThat(envelope.get("organization_id").asString()).isEqualTo(org.toString());
        assertThat(envelope.get("event_id")).isNotNull();
        assertThat(envelope.properties().iterator().next().getKey()).isEqualTo("event_id");
        // The latency bound (AC1): the observed end-to-end latency from
        // the audited action to the collector's receipt.
        assertThat(Duration.between(before, receivedAt.get(0)).toMillis()).isLessThan(5000);

        // The ledger's idempotency rows: the export's own creation
        // event and the rename, both DELIVERED -- and a replayed
        // drain does not double-deliver either.
        JsonNode log = deliveries(exportId, null);
        assertThat(log.get("items").size()).isEqualTo(2);
        assertThat(log.get("items").get(0).get("status").asString()).isEqualTo("DELIVERED");
        relay.drainOnce();
        assertThat(received).hasSize(1);
    }

    /**
     * The p95 of the latency bound (AC1): ten audited actions, each
     * observed from its API answer to the collector's receipt; the
     * 95th percentile must sit under five seconds -- the wire,
     * serialization, and delivery dominate (the drain is driven
     * immediately, the same work the scheduled relay does).
     */
    @Test
    void realTimeLatencyP95StaysUnderFiveSeconds() {
        createHttpsExport(Map.of(
                "kind",
                "HTTPS",
                "format",
                "SHIPPERDOG_JSON",
                "destinationUrl",
                collectorUrl(),
                "signingSecretRef",
                SIGNING_REF));
        relay.drainOnce();
        received.clear();
        List<Long> latencies = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            long started = System.nanoTime();
            renameOrg("Latency Probe " + i + " " + UUID.randomUUID());
            relay.drainOnce();
            assertThat(received.size()).as("probe %d delivered", i).isEqualTo(i + 1);
            latencies.add((System.nanoTime() - started) / 1_000_000);
        }
        latencies.sort(Long::compareTo);
        long p95 = latencies.get((int) Math.ceil(0.95 * latencies.size()) - 1);
        assertThat(p95).as("p95 latency ms over %d probes", latencies.size()).isLessThan(5000);
    }

    // -----------------------------------------------------------------------
    // The format helper and the collector's header auth (the scope's
    // generic event collector; the Splunk HEC optional helper)
    // -----------------------------------------------------------------------

    @Test
    void splunkHecFormatWrapsTheEnvelopeAndCarriesTheAuthHeader() {
        UUID exportId = createHttpsExport(Map.of(
                "kind",
                "HTTPS",
                "format",
                "SPLUNK_HEC",
                "destinationUrl",
                collectorUrl(),
                "signingSecretRef",
                SIGNING_REF,
                "authHeaderName",
                "Authorization",
                "authSecretRef",
                AUTH_REF));
        relay.drainOnce();
        received.clear();

        renameOrg("Splunk Probe " + UUID.randomUUID());
        relay.drainOnce();

        assertThat(received).hasSize(1);
        Received delivery = received.get(0);
        // The collector's auth header rides the resolved reference
        // (AC5: the value exists only on the wire, never in storage).
        assertThat(delivery.headers().get("Authorization")).containsExactly(AUTH_VALUE);
        assertThat(delivery.signature()).isEqualTo(hmac(delivery.timestamp(), delivery.body()));
        JsonNode hec = mapper.readTree(delivery.body());
        assertThat(hec.get("sourcetype").asString()).isEqualTo("shipperdog:audit:v1");
        assertThat(hec.get("host").asString()).isEqualTo(org.toString());
        assertThat(hec.get("time").asLong()).isPositive();
        assertThat(hec.get("event").get("schema_version").asString()).isEqualTo("1.0");
        assertThat(hec.get("event").get("event_type").asString()).isEqualTo("ORGANIZATION_UPDATED");
        assertThat(deliveries(exportId, "DELIVERED").get("items").size()).isEqualTo(2);
    }

    @Test
    void unresolvableSigningReferenceIsAnHonestFailedAttemptNotADrop() {
        UUID exportId = createHttpsExport(Map.of(
                "kind", "HTTPS",
                "format", "SHIPPERDOG_JSON",
                "destinationUrl", collectorUrl(),
                "signingSecretRef", "sm://test/nothing-resolves"));
        relay.drainOnce();
        received.clear();

        renameOrg("Unresolvable Probe " + UUID.randomUUID());
        relay.drainOnce();

        assertThat(received).isEmpty();
        JsonNode log = deliveries(exportId, null);
        assertThat(log.get("items").size()).isEqualTo(2);
        assertThat(log.get("items").get(0).get("status").asString()).isEqualTo("SCHEDULED");
        assertThat(log.get("items").get(0).get("lastError").asString()).contains("did not resolve");
        // The retry drain keeps the schedule honest to the ceiling:
        // the visible dead-letter of a standing misconfiguration
        // (both the creation event and the rename under the same
        // unresolvable reference).
        retryToCeiling(exportId, 2);
    }

    // -----------------------------------------------------------------------
    // AC3: downtime buffering, the visible dead-letter, the replay
    // -----------------------------------------------------------------------

    @Test
    void consumerDowntimeBuffersRetriesDeadLettersAndReplayRestoresWithoutLoss() {
        UUID exportId = createHttpsExport(Map.of(
                "kind",
                "HTTPS",
                "format",
                "SHIPPERDOG_JSON",
                "destinationUrl",
                collectorUrl(),
                "signingSecretRef",
                SIGNING_REF));
        relay.drainOnce();
        received.clear();

        // The collector goes down before the events exist.
        respondStatus.set(500);
        renameOrg("Outage Probe A " + UUID.randomUUID());
        renameOrg("Outage Probe B " + UUID.randomUUID());
        relay.drainOnce();

        // Buffered, not lost: both deliveries are SCHEDULED, nothing delivered.
        assertThat(deliveries(exportId, "SCHEDULED").get("items").size()).isEqualTo(2);
        assertThat(received).hasSize(2); // the wire was called; the collector refused

        // The retry drain runs the ceiling: three attempts, then the
        // visible dead-letter (the creation event stays DELIVERED
        // beside them -- it ran while the collector was up).
        retryToCeiling(exportId, 2);
        assertThat(deliveries(exportId, "FAILED").get("items").size()).isEqualTo(2);
        assertThat(deliveries(exportId, "DELIVERED").get("items").size()).isEqualTo(1);

        // The collector returns; the replay re-enqueues the dead-letter...
        respondStatus.set(200);
        JsonNode replay = post(adminToken, exportsPath() + "/" + exportId + "/replay", Map.of(), 200);
        assertThat(replay.get("replayed").asInt()).isEqualTo(2);
        // ...and the next retry passes deliver both. The wire saw every
        // attempt (at-least-once: a receiver deduplicates on the
        // envelope's event_id, the docs' recipe); the LEDGER is the
        // exactly-once answer -- each (event, export) one DELIVERED row.
        for (int i = 0; i < 40; i++) {
            dispatcher.retryDue();
            if (deliveries(exportId, "DELIVERED").get("items").size() >= 3) {
                break;
            }
        }
        assertThat(received.size()).isGreaterThanOrEqualTo(4); // 2 refused attempts + 2 delivered
        JsonNode delivered = deliveries(exportId, "DELIVERED");
        assertThat(delivered.get("items").size()).isEqualTo(3);
        assertThat(deliveries(exportId, "FAILED").get("items").size()).isZero();
        // Exactly the scenario's two events ever reached the wire under
        // these deliveries (the creation event's receipt predates the
        // clear) -- dedup by event id collapses the attempt retries.
        List<String> eventIds = new ArrayList<>();
        for (Received post : received) {
            eventIds.add(mapper.readTree(post.body()).get("event_id").asString());
        }
        assertThat(eventIds.stream().distinct().count()).isEqualTo(2);
        // The replay is audited.
        assertThat(audit.eventsOfType(AuditEventType.AUDIT_STREAM_REPLAYED)).hasSize(1);
    }

    // -----------------------------------------------------------------------
    // AC2: the bucket batches and the signed, verifiable manifest
    // -----------------------------------------------------------------------

    @Test
    void bucketExportBackfillsInBoundedBatchesWithSignedManifestsAndCompleteness() {
        // A fresh organization whose entire trail is this scenario's
        // own facts (the completeness answer is exact, not sampled).
        UUID bucketOrg =
                createOrg("Siem Bucket Corp " + UUID.randomUUID().toString().substring(0, 8));
        String bucketAdmin = orgRoleTokenIn(bucketOrg, "bucket-admin", "ORG_ADMIN");
        String bucketExports = "/api/v1/organizations/" + bucketOrg + "/audit-stream-exports";
        renameOrgAs(bucketAdmin, bucketOrg, "Batch Probe 1 " + UUID.randomUUID());
        renameOrgAs(bucketAdmin, bucketOrg, "Batch Probe 2 " + UUID.randomUUID());
        renameOrgAs(bucketAdmin, bucketOrg, "Batch Probe 3 " + UUID.randomUUID());

        String bucket = "siem-export-test-" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode created = exchange(
                HttpMethod.POST,
                bucketExports,
                Map.of(
                        "kind",
                        "BUCKET",
                        "bucketUri",
                        "gs://" + bucket + "/audit",
                        "signingSecretRef",
                        SIGNING_REF,
                        "initialWatermark",
                        "1970-01-01T00:00:00Z"),
                201,
                bucketAdmin);
        UUID exportId = UUID.fromString(created.get("id").asString());

        // Drain in bounded passes until the watermark catches the
        // trail's end (batch-max-rows=2: five-plus facts over several
        // passes -- the bound that keeps every pass finite).
        int previousBatches = 0;
        for (int i = 0; i < 10; i++) {
            batcher.exportOnce();
            int current = get(bucketAdmin, bucketExports + "/" + exportId + "/batches?limit=50", 200)
                    .get("items")
                    .size();
            if (current == previousBatches) {
                break;
            }
            previousBatches = current;
        }

        JsonNode batches = get(bucketAdmin, bucketExports + "/" + exportId + "/batches?limit=50", 200);
        assertThat(batches.get("items").size()).isGreaterThanOrEqualTo(3);
        // Completeness vs the audit table (AC2): the batches' counts
        // reconcile exactly with the trail for the window they cover.
        int trailCount =
                jdbc.queryForObject("select count(*) from audit_events where org_id = ?", Integer.class, bucketOrg);
        int batched = 0;
        for (JsonNode batch : batches.get("items")) {
            assertThat(batch.get("eventCount").asInt()).isLessThanOrEqualTo(2); // the bound
            batched += batch.get("eventCount").asInt();
        }
        assertThat(batched).isEqualTo(trailCount);
        // Sequence numbers are dense and versioned from 1.
        long maxSeq = batches.get("items").get(0).get("seq").asLong();
        assertThat(maxSeq).isEqualTo(batches.get("items").size());

        // The newest batch object: JSONL envelopes under the frozen schema.
        JsonNode newest = batches.get("items").get(0);
        byte[] batchBytes = storage.readAllBytes(com.google.cloud.storage.BlobId.fromGsUtilUri(
                newest.get("objectUri").asString()));
        String[] lines = new String(batchBytes, StandardCharsets.UTF_8).strip().split("\n");
        assertThat(lines.length).isEqualTo(newest.get("eventCount").asInt());
        JsonNode line = mapper.readTree(lines[0]);
        assertThat(line.get("schema_version").asString()).isEqualTo("1.0");
        assertThat(line.get("event_id")).isNotNull();
        assertThat(line.get("organization_id").asString()).isEqualTo(bucketOrg.toString());

        // The manifest: signed over its own signature-less canonical
        // bytes, verified here the way docs/siem.md tells the SIEM to.
        byte[] manifestBytes = storage.readAllBytes(com.google.cloud.storage.BlobId.fromGsUtilUri(
                newest.get("manifestUri").asString()));
        JsonNode manifest = mapper.readTree(manifestBytes);
        assertThat(manifest.get("batch_seq").asLong())
                .isEqualTo(newest.get("seq").asLong());
        assertThat(manifest.get("event_count").asInt())
                .isEqualTo(newest.get("eventCount").asInt());
        assertThat(manifest.get("batch_sha256").asString())
                .isEqualTo(newest.get("batchSha256").asString());
        Map<String, Object> unsigned = mapper.convertValue(manifest, LinkedHashMap.class);
        Object signature = unsigned.remove("signature");
        assertThat(signature).isNotNull();
        String expected = "sha256=" + hmacRaw(mapper.writeValueAsBytes(unsigned), SIGNING_SECRET);
        assertThat(manifest.get("signature").asString()).isEqualTo(expected);

        // A pass at the caught-up watermark writes nothing new (the
        // drain's quiet state -- no echo, no duplicate batches).
        int beforeQuiet = batches.get("items").size();
        batcher.exportOnce();
        assertThat(get(bucketAdmin, bucketExports + "/" + exportId + "/batches?limit=50", 200)
                        .get("items")
                        .size())
                .isEqualTo(beforeQuiet);
    }

    // -----------------------------------------------------------------------
    // AC5: the configuration surface and the secrets-as-references posture
    // -----------------------------------------------------------------------

    @Test
    void configurationIsOrgAdminOnlyAndSecretsStayReferences() {
        // A developer of the owning organization holds neither
        // permission: the permission target conceals (the opaque 404).
        get(developerToken, exportsPath(), 404);
        post(developerToken, exportsPath(), Map.of("kind", "HTTPS"), 404);
        // The foreign organization conceals identically.
        get(adminToken, "/api/v1/organizations/" + otherOrg + "/audit-stream-exports", 404);
        // The org admin's own listing is an honest empty page.
        assertThat(get(adminToken, exportsPath() + "?limit=10", 200)
                        .get("items")
                        .size())
                .isZero();

        // Creation validates the shape, one 422 per offense.
        assertRefused(Map.of("kind", "CARRIER_PIGEON", "signingSecretRef", SIGNING_REF), "kind");
        assertRefused(
                Map.of("kind", "BUCKET", "bucketUri", "s3://customer-bucket/audit", "signingSecretRef", SIGNING_REF),
                "bucketUri");
        assertRefused(
                Map.of(
                        "kind",
                        "HTTPS",
                        "format",
                        "SHIPPERDOG_JSON",
                        "destinationUrl",
                        collectorUrl(),
                        "signingSecretRef",
                        SIGNING_REF,
                        "eventTypes",
                        List.of("NOT_AN_AUDIT_EVENT")),
                "eventTypes");
        assertRefused(Map.of("kind", "BUCKET", "bucketUri", "gs://b/audit"), "signingSecretRef");
        assertRefused(
                Map.of(
                        "kind",
                        "BUCKET",
                        "bucketUri",
                        "gs://b/audit",
                        "signingSecretRef",
                        SIGNING_REF,
                        "format",
                        "SPLUNK_HEC"),
                "format");

        // A created export's trail line carries the REFERENCE, never material.
        createHttpsExport(Map.of(
                "kind",
                "HTTPS",
                "format",
                "SHIPPERDOG_JSON",
                "destinationUrl",
                collectorUrl(),
                "signingSecretRef",
                SIGNING_REF));
        List<com.shipperdog.api.audit.AuditEvent> createdEvents =
                audit.eventsOfType(AuditEventType.AUDIT_STREAM_EXPORT_CREATED);
        assertThat(createdEvents).hasSize(1);
        // The trail's key-based scrubber redacts the reference member
        // itself (any key carrying "secret") -- the safe direction:
        // neither the material NOR the reference rides the audit
        // line; the configuration view serves the reference, the
        // trail serves only that it existed.
        assertThat(createdEvents.get(0).detail().get("signingSecretRef")).isEqualTo("[REDACTED]");
        assertThat(createdEvents.get(0).detail().toString()).doesNotContain(SIGNING_SECRET);
        assertThat(createdEvents.get(0).detail().get("kind")).isEqualTo("HTTPS");

        // The test action answers the collector's truth synchronously,
        // and its audit line carries the outcome.
        JsonNode page = get(adminToken, exportsPath() + "?limit=10", 200);
        UUID exportId = UUID.fromString(page.get("items").get(0).get("id").asString());
        JsonNode test = post(adminToken, exportsPath() + "/" + exportId + "/test", Map.of(), 200);
        assertThat(test.get("delivered").asBoolean()).isTrue();
        assertThat(test.get("kind").asString()).isEqualTo("HTTPS");
        assertThat(audit.eventsOfType(AuditEventType.AUDIT_STREAM_EXPORT_TESTED))
                .hasSize(1);
        // The synthetic test envelope reached the collector signed.
        assertThat(received).isNotEmpty();
        Received last = received.get(received.size() - 1);
        assertThat(last.signature()).isEqualTo(hmac(last.timestamp(), last.body()));
        assertThat(mapper.readTree(last.body()).get("event_type").asString()).isEqualTo("AUDIT_STREAM_TEST");
    }

    @Test
    void organizationsWithoutExportsNeverBridgeOntoTheOutbox() {
        // The foreign org has no export: its audited actions write no
        // AUDIT_EVENT outbox row (the bridge's org gate, the bound).
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        exchange(HttpMethod.PUT, "/api/v1/organizations/" + otherOrg, Map.of("name", "Gate Probe"), 200, devToken);
        Integer bridged = jdbc.queryForObject(
                "select count(*) from notification_outbox where organization_id = ? and event_name = 'AUDIT_EVENT'",
                Integer.class,
                otherOrg);
        assertThat(bridged).isZero();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private String exportsPath() {
        return "/api/v1/organizations/" + org + "/audit-stream-exports";
    }

    private UUID createHttpsExport(Map<String, Object> body) {
        return UUID.fromString(
                post(adminToken, exportsPath(), body, 201).get("id").asString());
    }

    /** Drives the retry drain until the export's dead-letter reaches the expected size (the backoff stamps race a naive double call). */
    private void retryToCeiling(UUID exportId, int expectedFailed) {
        for (int i = 0; i < 40; i++) {
            dispatcher.retryDue();
            if (deliveries(exportId, "FAILED").get("items").size() >= expectedFailed) {
                return;
            }
        }
    }

    private JsonNode deliveries(UUID exportId, String status) {
        String uri = exportsPath() + "/" + exportId + "/deliveries?limit=50";
        if (status != null) {
            uri += "&status=" + status;
        }
        return get(adminToken, uri, 200);
    }

    private void assertRefused(Map<String, Object> body, String field) {
        try {
            client.post()
                    .uri(exportsPath())
                    .headers(headers -> headers.setBearerAuth(adminToken))
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            throw new AssertionError("expected 422 for " + field);
        } catch (org.springframework.web.client.RestClientResponseException e) {
            assertThat(e.getStatusCode().value()).as("field %s refuses", field).isEqualTo(422);
            JsonNode problem = mapper.readTree(e.getResponseBodyAsByteArray());
            assertThat(problem.get("code").asString()).isEqualTo("AUDIT_STREAM_EXPORT_INVALID");
            assertThat(problem.get("errors").hasNonNull(field)).isTrue();
        }
    }

    /** The receiver's verification recipe (docs/siem.md): sha256=HMAC(secret, timestamp + "." + body). */
    private String hmac(String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SIGNING_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '.');
            StringBuilder hex = new StringBuilder();
            for (byte b : mac.doFinal(body)) {
                hex.append(String.format("%02x", b));
            }
            return "sha256=" + hex;
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The manifest's recipe (docs/siem.md): sha256=HMAC(secret, canonical signature-less bytes). */
    private String hmacRaw(byte[] content, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            StringBuilder hex = new StringBuilder();
            for (byte b : mac.doFinal(content)) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private String collectorUrl() {
        return "http://127.0.0.1:" + collector.getAddress().getPort() + "/collect";
    }

    private void renameOrg(String name) {
        renameOrgAs(adminToken, org, name);
    }

    private void renameOrgAs(String bearer, UUID organizationId, String name) {
        exchange(HttpMethod.PUT, "/api/v1/organizations/" + organizationId, Map.of("name", name), 200, bearer);
    }

    private JsonNode post(String bearer, String uri, Map<String, Object> body, int expectedStatus) {
        return exchange(HttpMethod.POST, uri, body, expectedStatus, bearer);
    }

    private JsonNode exchange(
            HttpMethod method, String uri, Map<String, Object> body, int expectedStatus, String bearer) {
        var requestSpec = client.method(method).uri(uri).headers(headers -> {
            if (bearer != null) {
                headers.setBearerAuth(bearer);
            }
            if (body != null) {
                headers.set(HttpHeaders.CONTENT_TYPE, "application/json");
            }
        });
        if (body != null) {
            requestSpec = requestSpec.body(body);
        }
        return requestSpec.exchange((request, response) -> {
            assertThat(response.getStatusCode().value())
                    .as("%s %s -> %d", method, uri, expectedStatus)
                    .isEqualTo(expectedStatus);
            byte[] bytes = response.getBody().readAllBytes();
            return bytes.length == 0 ? mapper.createObjectNode() : mapper.readTree(bytes);
        });
    }

    private JsonNode get(String bearer, String uri, int expectedStatus) {
        return client.get()
                .uri(uri)
                .headers(headers -> headers.setBearerAuth(bearer))
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode().value())
                            .as("GET %s -> %d", uri, expectedStatus)
                            .isEqualTo(expectedStatus);
                    byte[] bytes = response.getBody().readAllBytes();
                    return bytes.length == 0 ? mapper.createObjectNode() : mapper.readTree(bytes);
                });
    }

    private UUID createOrg(String name) {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        JsonNode created = exchange(HttpMethod.POST, "/api/v1/organizations", Map.of("name", name), 201, devToken);
        return UUID.fromString(created.get("id").asString());
    }

    private String orgRoleTokenIn(UUID organizationId, String slug, String role) {
        String email = slug + UUID.randomUUID().toString().substring(0, 6) + "@example.com";
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        JsonNode invitation = exchange(
                HttpMethod.POST,
                "/api/v1/auth/invitations",
                Map.of("email", email, "organizationId", organizationId.toString(), "role", role),
                201,
                devToken);
        exchange(
                HttpMethod.POST,
                "/api/v1/auth/register",
                Map.of(
                        "invitationToken",
                        invitation.get("invitationToken").asString(),
                        "email",
                        email,
                        "password",
                        PASSWORD,
                        "fullName",
                        "Siem " + slug),
                201,
                null);
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
        return mapper.readTree(tokensJson).get("accessToken").asString();
    }
}
