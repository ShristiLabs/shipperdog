package com.shipperdog.api.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.config.MetricNames;
import com.shipperdog.api.identity.AuditCapture;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The S12-04 store receivers' acceptance flow (issue #54, FR-022,
 * PRD 19/51): the App Store receiver (Apple's JWS signedPayload,
 * verified against a pinned root, then bundle-authorized) and the
 * Google Play receiver (a Pub/Sub push whose OIDC bearer is verified
 * against a served JWKS, its protobuf notification read thin) end to
 * end -- the store family's uniform 400 over every authenticity
 * failure, the stale-timestamp replay rejection, the idempotent
 * mapping to {@code store_status_events} (the ledger's DUPLICATE plus
 * the domain table's own unique key), and the S12-04 hardening: a
 * metric increment per answered delivery and an audit event per
 * mapped row. The fixtures are self-signed ShipperDog TEST
 * certificates standing in for Apple's chain and Google's JWKS (never
 * credentials).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef",
            "shipperdog.webhooks.appstore-bundle-ids=com.example.app",
            "shipperdog.webhooks.appstore-trusted-roots=classpath:webhooks/appstore-test-root.crt",
            "shipperdog.webhooks.google-play-audience=https://shipperdog.test/webhooks/google-play"
        })
@Testcontainers
@Import(AuditCapture.class)
class StoreReceiversIntegrationTest {

    private static final String AUDIENCE = "https://shipperdog.test/webhooks/google-play";

    /** The local JWKS stand-in for Google's endpoint, started before the context so the URI can be wired. */
    private static final HttpServer JWKS = startJwks();

    private static HttpServer startJwks() {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            X509Certificate certificate;
            try (InputStream in = StoreReceiversIntegrationTest.class
                    .getClassLoader()
                    .getResourceAsStream("webhooks/google-play-test-rsa.crt")) {
                certificate = (X509Certificate) factory.generateCertificate(in);
            }
            RSAPublicKey publicKey = (RSAPublicKey) certificate.getPublicKey();
            String jwksJson = "{\"keys\":[{\"kty\":\"RSA\",\"alg\":\"RS256\",\"use\":\"sig\","
                    + "\"kid\":\"fixture-key-1\",\"n\":\""
                    + Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(unsigned(publicKey.getModulus().toByteArray()))
                    + "\",\"e\":\""
                    + Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(
                                    unsigned(publicKey.getPublicExponent().toByteArray()))
                    + "\"}]}";
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/certs", exchange -> {
                byte[] body = jwksJson.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return server;
        } catch (Exception e) {
            throw new IllegalStateException("cannot start the fixture JWKS server", e);
        }
    }

    @DynamicPropertySource
    static void jwksUri(DynamicPropertyRegistry registry) {
        registry.add(
                "shipperdog.webhooks.google-play-jwks-uri",
                () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/certs");
    }

    @AfterAll
    static void stopJwks() {
        JWKS.stop(0);
    }

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
    // App Store: JWS authenticity + bundle authorization + thin mapping
    // -----------------------------------------------------------------------

    @Test
    void appStoreWellFormedNotificationMapsIdempotently() throws Exception {
        Recording answer = deliverAppStore(appStorePayload("com.example.app", "SUBSCRIBED", "now", null));
        assertThat(answer.status).isEqualTo(200);
        assertThat(answer.body.get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(answer.body.get("deliveryId").asText()).isEqualTo("as-1");

        assertThat(storeEventCount("appstore", "as-1")).isEqualTo(1);
        assertThat(audit.eventsOfType(AuditEventType.STORE_EVENT_MAPPED)).hasSize(1);
        java.util.Map<String, Object> mappedRow =
                jdbc.queryForMap("SELECT event_type, bundle_id FROM store_status_events "
                        + "WHERE provider = 'appstore' AND provider_event_id = 'as-1'");
        assertThat(mappedRow.get("event_type") + "/" + mappedRow.get("bundle_id"))
                .isEqualTo("SUBSCRIBED:DID_RENEW/com.example.app");
        // The meter registry is cumulative across this class's tests
        // (one shared context); the floor proves THIS delivery metered.
        assertThat(counted("appstore", "accepted")).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void appStoreRedeliveryOfTheSameNotificationUUIDIsDuplicateAndNeverRemaps() throws Exception {
        deliverAppStore(appStorePayload("com.example.app", "SUBSCRIBED", "now", null));
        Recording replay = deliverAppStore(appStorePayload("com.example.app", "SUBSCRIBED", "now", null));
        assertThat(replay.status).isEqualTo(200);
        assertThat(replay.body.get("status").asText()).isEqualTo("DUPLICATE");
        assertThat(storeEventCount("appstore", "as-1")).isEqualTo(1);
        assertThat(audit.eventsOfType(AuditEventType.INBOUND_WEBHOOK_DUPLICATE)).hasSize(1);
    }

    @Test
    void appStoreEveryAuthenticityFailureAnswersTheSame400() throws Exception {
        // An unsigned body, a foreign-bundle TAMPERED payload (the
        // signature no longer matches), and a stale signedDate are
        // all the one uniform 400 -- never a hint which leg refused.
        byte[] unsigned = "{\"signedPayload\":\"not-a-jws\"}".getBytes(StandardCharsets.UTF_8);
        Recording badBody = post("/webhooks/appstore", unsigned, null);
        assertThat(badBody.status).isEqualTo(400);
        assertThat(badBody.body.get("code").asText()).isEqualTo("WEBHOOK_DELIVERY_REJECTED");

        Recording tampered =
                deliverAppStore(appStorePayload("com.example.app", "SUBSCRIBED", "now", "tamper-signature"));
        assertThat(tampered.status).isEqualTo(400);
        assertThat(tampered.body.get("code").asText()).isEqualTo("WEBHOOK_DELIVERY_REJECTED");

        Recording stale = deliverAppStore(appStorePayload("com.example.app", "SUBSCRIBED", "now-600000", null));
        assertThat(stale.status).isEqualTo(400);
        assertThat(stale.body.get("code").asText()).isEqualTo("WEBHOOK_DELIVERY_REJECTED");

        assertThat(audit.eventsOfType(AuditEventType.INBOUND_WEBHOOK_REJECTED)).hasSize(3);
        assertThat(storeEventCount("appstore", "%")).isZero();
        assertThat(counted("appstore", "rejected")).isEqualTo(3.0);
    }

    @Test
    void appStoreForeignBundleIsAcknowledgedAndIgnored() throws Exception {
        Recording answer = deliverAppStore(appStorePayload("com.somebody-else.app", "SUBSCRIBED", "now", null));
        assertThat(answer.status).isEqualTo(200);
        assertThat(answer.body.get("status").asText()).isEqualTo("IGNORED");
        assertThat(storeEventCount("appstore", "as-1")).isZero();
    }

    @Test
    void appStoreShapeDriftWithoutANotificationUUIDIsIgnoredNeverMapped() throws Exception {
        // A perfectly signed notification whose payload lost the
        // uuid field (Apple shape drift): an acknowledged no-op --
        // never a 400 Apple would retry, never a half-mapped row.
        Recording answer = deliverAppStore(appStorePayload("com.example.app", "SUBSCRIBED", "now", null, null));
        assertThat(answer.status).isEqualTo(200);
        assertThat(answer.body.get("status").asText()).isEqualTo("IGNORED");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM inbound_webhook_deliveries WHERE provider = 'appstore'", Integer.class))
                .isZero();
    }

    // -----------------------------------------------------------------------
    // Google Play: OIDC authenticity + thin protobuf mapping
    // -----------------------------------------------------------------------

    @Test
    void googlePlayWellFormedPushMapsIdempotently() throws Exception {
        Recording answer = deliverGooglePlay(subscriptionEnvelope("gp-1", "SUBSCRIPTION_RENEWED", "now"));
        assertThat(answer.status).isEqualTo(200);
        assertThat(answer.body.get("status").asText()).isEqualTo("ACCEPTED");

        assertThat(storeEventCount("google-play", "gp-1")).isEqualTo(1);
        assertThat(audit.eventsOfType(AuditEventType.STORE_EVENT_MAPPED)).hasSize(1);
        java.util.Map<String, Object> mappedRow =
                jdbc.queryForMap("SELECT event_type, purchase_token, external_ref FROM store_status_events "
                        + "WHERE provider = 'google-play' AND provider_event_id = 'gp-1'");
        assertThat(mappedRow.get("event_type") + "/" + mappedRow.get("purchase_token") + "/"
                        + mappedRow.get("external_ref"))
                .isEqualTo("subscription/2/purchase-token-1/sub-tier-gold");
    }

    @Test
    void googlePlayRedeliveryOfTheSameMessageIdIsDuplicate() throws Exception {
        deliverGooglePlay(subscriptionEnvelope("gp-1", "SUBSCRIPTION_RENEWED", "now"));
        Recording replay = deliverGooglePlay(subscriptionEnvelope("gp-1", "SUBSCRIPTION_RENEWED", "now"));
        assertThat(replay.body.get("status").asText()).isEqualTo("DUPLICATE");
        assertThat(storeEventCount("google-play", "gp-1")).isEqualTo(1);
    }

    @Test
    void googlePlayEveryAuthenticityFailureAnswersTheSame400() throws Exception {
        // No bearer, a wrong-audience bearer, and a stale publishTime:
        // the one uniform 400.
        byte[] body = bodyOf(subscriptionEnvelope("gp-2", "SUBSCRIPTION_RENEWED", "now"));
        Recording noBearer = post("/webhooks/google-play", body, null);
        assertThat(noBearer.status).isEqualTo(400);
        assertThat(noBearer.body.get("code").asText()).isEqualTo("WEBHOOK_DELIVERY_REJECTED");
        Recording wrongAudience =
                post("/webhooks/google-play", body, "Bearer " + googleToken("https://other.example.com/hook"));
        assertThat(wrongAudience.status).isEqualTo(400);
        assertThat(wrongAudience.body.get("code").asText()).isEqualTo("WEBHOOK_DELIVERY_REJECTED");
        Recording stale = deliverGooglePlay(subscriptionEnvelope("gp-2", "SUBSCRIPTION_RENEWED", "now-3600"));
        assertThat(stale.status).isEqualTo(400);
        assertThat(stale.body.get("code").asText()).isEqualTo("WEBHOOK_DELIVERY_REJECTED");
        assertThat(storeEventCount("google-play", "%")).isZero();
        assertThat(counted("google-play", "rejected")).isEqualTo(3.0);
    }

    @Test
    void googlePlayVerifiedSendersUnreadableEnvelopeAnswersMalformed400() throws Exception {
        byte[] unreadable = "{not-json".getBytes(StandardCharsets.UTF_8);
        Recording answer = post("/webhooks/google-play", unreadable, "Bearer " + googleToken(AUDIENCE));
        assertThat(answer.status).isEqualTo(400);
        assertThat(answer.body.get("code").asText()).isEqualTo("WEBHOOK_PAYLOAD_MALFORMED");
    }

    @Test
    void googlePlayShapeDriftInsideAVerifiedPushIsIgnoredNeverFailed() throws Exception {
        // A message whose data is not a notification protobuf: an
        // acknowledged no-op (Google retries non-2xx).
        ObjectNode envelope = mapper.createObjectNode();
        ObjectNode message = envelope.putObject("message");
        message.put("data", Base64.getEncoder().encodeToString(new byte[] {0x08, 0x01}));
        message.put("messageId", "gp-3");
        message.put("publishTime", Instant.now().toString());
        Recording answer = deliverGooglePlay(envelope);
        assertThat(answer.status).isEqualTo(200);
        assertThat(answer.body.get("status").asText()).isEqualTo("IGNORED");
        assertThat(storeEventCount("google-play", "gp-3")).isZero();
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    /**
     * The App Store body: {"signedPayload": jws}. "tamper-signature"
     * flips a signature byte; a null uuid omits the notificationUUID
     * field (the shape-drift leg).
     */
    private ObjectNode appStorePayload(String bundleId, String type, String when, String tamper) throws Exception {
        return appStorePayload(bundleId, type, when, tamper, "as-1");
    }

    private ObjectNode appStorePayload(String bundleId, String type, String when, String tamper, String uuid)
            throws Exception {
        long signedDate = Instant.now().toEpochMilli() + ("now".equals(when) ? 0 : Long.parseLong(when.substring(4)));
        ObjectNode payload = mapper.createObjectNode();
        payload.put("notificationType", type);
        payload.put("subtype", "DID_RENEW");
        if (uuid != null) {
            payload.put("notificationUUID", uuid);
        }
        payload.put("signedDate", Long.toString(signedDate));
        payload.putObject("data").put("bundleId", bundleId);
        String jws = appStoreJws(payload);
        if (tamper != null) {
            byte[] signatureBytes = Base64.getUrlDecoder().decode(jws.split("\\.")[2]);
            signatureBytes[3] ^= 0x01;
            jws = jws.split("\\.")[0] + "." + jws.split("\\.")[1] + "."
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(signatureBytes);
        }
        return mapper.createObjectNode().put("signedPayload", jws);
    }

    private String appStoreJws(ObjectNode payload) throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        X509Certificate leaf;
        X509Certificate root;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("webhooks/appstore-test-leaf.crt")) {
            leaf = (X509Certificate) factory.generateCertificate(in);
        }
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("webhooks/appstore-test-root.crt")) {
            root = (X509Certificate) factory.generateCertificate(in);
        }
        PrivateKey key = ecKey("webhooks/appstore-test-leaf.key");
        String header = "{\"alg\":\"ES256\",\"x5c\":[\""
                + Base64.getEncoder().encodeToString(leaf.getEncoded()) + "\",\""
                + Base64.getEncoder().encodeToString(root.getEncoded()) + "\"]}";
        Base64.Encoder url = Base64.getUrlEncoder().withoutPadding();
        String encodedHeader = url.encodeToString(header.getBytes(StandardCharsets.UTF_8));
        String encodedPayload = url.encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8));
        Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
        signer.initSign(key);
        signer.update((encodedHeader + "." + encodedPayload).getBytes(StandardCharsets.US_ASCII));
        return encodedHeader + "." + encodedPayload + "." + url.encodeToString(signer.sign());
    }

    /** The Pub/Sub push envelope carrying a subscription notification. */
    private ObjectNode subscriptionEnvelope(String messageId, String label, String when) {
        byte[] variant = wireConcat(
                wireVarintField(1, 2), // SubscriptionNotification.notification_type = 2 (RENEWED)
                wireStringField(2, "purchase-token-1"),
                wireStringField(3, "sub-tier-gold"));
        byte[] notification = wireStringField(4, variant);
        ObjectNode envelope = mapper.createObjectNode();
        ObjectNode message = envelope.putObject("message");
        message.put("data", Base64.getEncoder().encodeToString(notification));
        message.put("messageId", messageId);
        message.put(
                "publishTime",
                Instant.ofEpochSecond(Instant.now().getEpochSecond()
                                + ("now".equals(when) ? 0 : Long.parseLong(when.substring(4))))
                        .toString());
        envelope.put("subscription", "projects/fixture/subscriptions/shipperdog");
        return envelope;
    }

    /** The OIDC bearer, signed by the fixture RSA key the served JWKS publishes. */
    private String googleToken(String audience) throws Exception {
        ObjectNode claims = mapper.createObjectNode();
        claims.put("iss", "https://accounts.google.com");
        claims.putArray("aud").add(audience);
        long now = Instant.now().getEpochSecond();
        claims.put("iat", now);
        claims.put("exp", now + 3600);
        String header = "{\"alg\":\"RS256\",\"kid\":\"fixture-key-1\"}";
        Base64.Encoder url = Base64.getUrlEncoder().withoutPadding();
        String encodedHeader = url.encodeToString(header.getBytes(StandardCharsets.UTF_8));
        String encodedClaims = url.encodeToString(claims.toString().getBytes(StandardCharsets.UTF_8));
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(rsaKey());
        signer.update((encodedHeader + "." + encodedClaims).getBytes(StandardCharsets.US_ASCII));
        return encodedHeader + "." + encodedClaims + "." + url.encodeToString(signer.sign());
    }

    // -----------------------------------------------------------------------
    // Delivery + wire helpers
    // -----------------------------------------------------------------------

    private Recording deliverAppStore(ObjectNode payload) {
        return post("/webhooks/appstore", bodyOf(payload), null);
    }

    private Recording deliverGooglePlay(ObjectNode envelope) throws Exception {
        return post("/webhooks/google-play", bodyOf(envelope), "Bearer " + googleToken(AUDIENCE));
    }

    private Recording post(String uri, byte[] body, String bearer) {
        RestClient.RequestBodySpec spec = client.post().uri(uri);
        if (bearer != null) {
            spec.header("Authorization", bearer);
        }
        return spec.contentType(MediaType.APPLICATION_JSON).body(body).exchange((request, response) -> {
            byte[] raw = response.getBody().readAllBytes();
            return new Recording(
                    response.getStatusCode().value(),
                    raw.length == 0 ? mapper.createObjectNode() : mapper.readTree(raw));
        });
    }

    private byte[] bodyOf(ObjectNode payload) {
        return payload.toString().getBytes(StandardCharsets.UTF_8);
    }

    private int storeEventCount(String provider, String eventId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM store_status_events WHERE provider = ? AND provider_event_id LIKE ?",
                Integer.class,
                provider,
                eventId);
        return count == null ? 0 : count;
    }

    private double counted(String provider, String outcome) {
        return meters.counter(MetricNames.WEBHOOK_DELIVERIES, "provider", provider, "outcome", outcome)
                .count();
    }

    private PrivateKey ecKey(String resource) throws Exception {
        return key(resource, "EC");
    }

    private PrivateKey rsaKey() throws Exception {
        return key("webhooks/google-play-test-rsa.key", "RSA");
    }

    private PrivateKey key(String resource, String algorithm) throws Exception {
        String pem = Files.readString(
                Path.of(getClass().getClassLoader().getResource(resource).toURI()));
        String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        return KeyFactory.getInstance(algorithm)
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
    }

    /** JWKS integers are unsigned big-endian without ASN.1's leading-zero byte. */
    private static byte[] unsigned(byte[] twosComplement) {
        if (twosComplement.length > 1 && twosComplement[0] == 0) {
            byte[] trimmed = new byte[twosComplement.length - 1];
            System.arraycopy(twosComplement, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return twosComplement;
    }

    // Minimal protobuf wire writers (the test writes what the reader reads).

    private static byte[] wireConcat(byte[]... fields) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (byte[] field : fields) {
            out.writeBytes(field);
        }
        return out.toByteArray();
    }

    private static byte[] wireStringField(int field, String value) {
        return wireStringField(field, value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] wireStringField(int field, byte[] value) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        wireVarint(out, ((long) field << 3) | 2);
        wireVarint(out, value.length);
        out.writeBytes(value);
        return out.toByteArray();
    }

    private static byte[] wireVarintField(int field, long value) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        wireVarint(out, (long) field << 3);
        wireVarint(out, value);
        return out.toByteArray();
    }

    private static void wireVarint(java.io.ByteArrayOutputStream out, long value) {
        long remaining = value;
        while (true) {
            if ((remaining & ~0x7fL) == 0) {
                out.write((int) remaining);
                return;
            }
            out.write((int) ((remaining & 0x7fL) | 0x80));
            remaining >>>= 7;
        }
    }

    private record Recording(int status, JsonNode body) {}
}
