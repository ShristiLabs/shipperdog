package com.shipperdog.api.notification.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.feedback.FeedbackMarkedCriticalEvent;
import com.shipperdog.api.feedback.FeedbackReceivedEvent;
import com.shipperdog.api.identity.AuditCapture;
import com.shipperdog.api.notification.DomainEventName;
import com.shipperdog.api.notification.NotificationException;
import com.shipperdog.api.notification.internal.bus.OutboxRelay;
import com.shipperdog.api.release.ReleasePromotedEvent;
import com.shipperdog.api.tester.CapturingEmailSender;
import com.shipperdog.api.tester.TesterInvitedEvent;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The delivery acceptance flow (issue #51, S12-01, AC4/AC5/AC6): a
 * relayed event fans out to the organization's subscriptions -- the
 * in-app inbox write (terminal at birth, visible through the
 * self-scoped inbox route), the email channel through the capturing
 * {@code EmailSender} fixture, and the webhook channel as a signed
 * POST (HMAC-SHA256 over timestamp + body, verified here the way
 * docs/notifications.md tells receivers to) with retry/backoff to a
 * local HTTP receiver and a terminal FAILED row at the attempt
 * ceiling, failures visible in the delivery log all the while. A
 * webhook nothing answers fails ONLY in its own ledger -- the relay
 * and the event sail past (AC6).
 *
 * <p>The receiver is the JDK's built-in HTTP server -- a real wire,
 * no new dependency. Delivery retries run with a near-zero backoff
 * and a three-attempt ceiling so exhaustion is a deterministic pass,
 * and the whole schedule is disabled (drains are driven through the
 * beans, the analytics suite's pattern). The world's entity ids are
 * real rows (the outbox carries hard foreign keys), the release
 * suite's fixture recipe.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef",
            "shipperdog.notifications.relay-enabled=false",
            "shipperdog.notifications.relay-fixed-delay=PT1H",
            "shipperdog.notifications.delivery-backoff=PT0.01S",
            "shipperdog.notifications.delivery-max-attempts=3"
        })
@Testcontainers
@Import({AuditCapture.class, CapturingEmailSender.class})
class NotificationDeliveryIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";
    private static final String PASSWORD = "a-perfectly-valid-password";
    private static final String WEBHOOK_SECRET = "delivery-test-signing-secret";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @LocalServerPort
    int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private NotificationDispatcher dispatcher;

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private CapturingEmailSender email;

    @Autowired
    private AuditCapture audit;

    @Autowired
    private NotificationSubscriptionService routing;

    /** The jackson 2 mapper the test side already uses for wire JSON (the fixtures' choice). */
    private final ObjectMapper wire = new ObjectMapper();

    private RestClient client;

    private TransactionTemplate inTransaction;

    private static World world;

    private HttpServer receiver;

    private final List<ReceivedWebhook> received = new CopyOnWriteArrayList<>();

    private final AtomicInteger respondStatus = new AtomicInteger(200);

    /** One received POST: the body bytes and the three signature headers. */
    record ReceivedWebhook(byte[] body, String event, String timestamp, String signature) {}

    private static class World {
        UUID org;
        String adminToken;
        UUID adminUserId;
        String qaToken;
        UUID tenant;
        UUID app;
        UUID version;
        UUID artifact;
        UUID releaseId;
        String releaseRef;
    }

    @BeforeEach
    void setUp() throws Exception {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        inTransaction = new TransactionTemplate(transactions);
        email.reset();
        audit.reset();
        received.clear();
        respondStatus.set(200);
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpHandler handler = (HttpExchange exchange) -> {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            exchange.getRequestBody().transferTo(buffer);
            received.add(new ReceivedWebhook(
                    buffer.toByteArray(),
                    exchange.getRequestHeaders().getFirst("X-Shipperdog-Event"),
                    exchange.getRequestHeaders().getFirst("X-Shipperdog-Timestamp"),
                    exchange.getRequestHeaders().getFirst("X-Shipperdog-Signature")));
            byte[] answer = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(respondStatus.get(), answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        };
        receiver.createContext("/hook", handler);
        receiver.start();
        if (world == null) {
            world = releasedWorld();
        }
    }

    @AfterEach
    void tearDown() {
        receiver.stop(0);
    }

    // -----------------------------------------------------------------------
    // The in-app inbox and the email channel
    // -----------------------------------------------------------------------

    @Test
    void inAppDeliveriesLandInTheRecipientsInbox() {
        createSubscription(Map.of(
                "eventTypes", List.of("FEEDBACK_MARKED_CRITICAL"),
                "channel", "IN_APP",
                "recipientRoles", List.of("QA_ENGINEER")));
        publish(new FeedbackMarkedCriticalEvent(
                UUID.randomUUID(),
                world.releaseId,
                world.releaseRef,
                world.app,
                world.org,
                world.tenant,
                UUID.randomUUID(),
                "BUG",
                Instant.now()));
        relay.drainOnce();

        JsonNode inbox = get(world.qaToken, "/api/v1/notification-inbox?limit=10", 200);
        assertThat(inbox.get("items").size()).isEqualTo(1);
        assertThat(inbox.get("items").get(0).get("eventName").asText()).isEqualTo("FEEDBACK_MARKED_CRITICAL");
        // The admin (not a QA subscriber) sees nothing.
        JsonNode adminInbox = get(world.adminToken, "/api/v1/notification-inbox?limit=10", 200);
        assertThat(adminInbox.get("items").size()).isZero();
    }

    @Test
    void emailChannelDeliversThroughTheSenderSeam() {
        // The explicit recipient pins the fan-out to exactly this user
        // (role resolution is the in-app test's proof).
        createSubscription(Map.of(
                "eventTypes", List.of("RELEASE_PROMOTED"),
                "channel", "EMAIL",
                "recipientUserIds", List.of(world.adminUserId.toString())));
        publish(new ReleasePromotedEvent(
                world.releaseId,
                world.releaseRef,
                world.app,
                world.version,
                world.org,
                null,
                "qa",
                "beta",
                List.of(),
                false,
                UUID.randomUUID(),
                Instant.now()));
        relay.drainOnce();

        assertThat(email.sent()).hasSize(1);
        assertThat(email.sent().get(0).to()).isEqualTo("dlv-admin@example.com");
        assertThat(email.sent().get(0).subject()).contains("RELEASE_PROMOTED");
        Integer delivered = jdbc.queryForObject(
                "SELECT count(*) FROM notification_deliveries d "
                        + "JOIN notification_subscriptions s ON s.id = d.subscription_id "
                        + "WHERE s.organization_id = ? AND d.channel = 'EMAIL' AND d.status = 'DELIVERED'",
                Integer.class,
                world.org);
        assertThat(delivered).isEqualTo(1);
    }

    @Test
    void inboxPagesNewestFirstThroughOnlyTheCallersOwnRows() {
        // A dedicated recipient: the class's world is shared, and this
        // subscription's event types overlap other tests' publications,
        // so the paging assertions must own their recipient's inbox.
        String pagerToken = loginOfOrgRoleIn(world.org, "pg-pager", "ORG_ADMIN");
        UUID pagerUserId = userIdOf(pagerToken);
        createSubscription(Map.of(
                "eventTypes",
                List.of("TESTER_INVITED", "FEEDBACK_MARKED_CRITICAL", "FEEDBACK_RECEIVED"),
                "channel",
                "IN_APP",
                "recipientUserIds",
                List.of(pagerUserId.toString())));
        Instant base = Instant.now();
        // Published oldest to newest: the relay drains in occurredAt
        // order, so the inbox's newest-first is the reverse of this.
        publish(new TesterInvitedEvent(UUID.randomUUID(), world.org, UUID.randomUUID(), false, base.minusSeconds(40)));
        publish(new FeedbackMarkedCriticalEvent(
                UUID.randomUUID(),
                world.releaseId,
                world.releaseRef,
                world.app,
                world.org,
                world.tenant,
                UUID.randomUUID(),
                "BUG",
                base.minusSeconds(30)));
        publish(new FeedbackReceivedEvent(
                UUID.randomUUID(),
                world.releaseId,
                world.releaseRef,
                world.app,
                world.version,
                world.artifact,
                world.org,
                world.tenant,
                UUID.randomUUID(),
                "BUG",
                0,
                base.minusSeconds(20)));
        relay.drainOnce();

        JsonNode pageOne = get(pagerToken, "/api/v1/notification-inbox?limit=2", 200);
        assertThat(pageOne.get("items").size()).isEqualTo(2);
        assertThat(pageOne.get("items").get(0).get("eventName").asText()).isEqualTo("FEEDBACK_RECEIVED");
        assertThat(pageOne.get("items").get(1).get("eventName").asText()).isEqualTo("FEEDBACK_MARKED_CRITICAL");
        assertThat(pageOne.get("next_cursor")).isNotNull();

        JsonNode pageTwo = get(
                pagerToken,
                "/api/v1/notification-inbox?limit=2&cursor="
                        + pageOne.get("next_cursor").asText(),
                200);
        assertThat(pageTwo.get("items").size()).isEqualTo(1);
        assertThat(pageTwo.get("items").get(0).get("eventName").asText()).isEqualTo("TESTER_INVITED");
        assertThat(pageTwo.has("next_cursor")).isFalse();

        // Self-scoping holds under paging: the world's admin (not a
        // recipient of this subscription) pages nothing.
        JsonNode adminInbox = get(world.adminToken, "/api/v1/notification-inbox?limit=2", 200);
        assertThat(adminInbox.get("items").size()).isZero();
        assertThat(adminInbox.has("next_cursor")).isFalse();
    }

    // -----------------------------------------------------------------------
    // AC5: the signed webhook, its retry, and its exhaustion
    // -----------------------------------------------------------------------

    @Test
    void webhookDeliversSignedAndVerifiable() throws Exception {
        UUID subscription = createSubscription(Map.of(
                "eventTypes",
                List.of("RELEASE_PROMOTED"),
                "channel",
                "WEBHOOK",
                "webhookUrl",
                "http://127.0.0.1:" + receiver.getAddress().getPort() + "/hook",
                "webhookSecret",
                WEBHOOK_SECRET));
        publish(new ReleasePromotedEvent(
                world.releaseId,
                world.releaseRef,
                world.app,
                world.version,
                world.org,
                null,
                "qa",
                "beta",
                List.of(),
                true,
                UUID.randomUUID(),
                Instant.now()));
        relay.drainOnce();

        assertThat(received).hasSize(1);
        ReceivedWebhook hook = received.get(0);
        assertThat(hook.event()).isEqualTo("RELEASE_PROMOTED");
        // The receiver's verification recipe (docs/notifications.md):
        // HMAC-SHA256(secret, timestamp + "." + body), hex-encoded.
        // The rendering is fixed-width (HexFormat, every digest byte
        // two characters): a BigInteger formatter drops the leading
        // zero nibble whenever the first byte is under 0x10 and the
        // assertion flakes one run in sixteen.
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update(hook.timestamp().getBytes(StandardCharsets.UTF_8));
        mac.update((byte) '.');
        String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(hook.body()));
        assertThat(hook.signature()).isEqualTo(expected);

        JsonNode body = wire.readTree(hook.body());
        assertThat(body.get("name").asText()).isEqualTo("RELEASE_PROMOTED");
        assertThat(body.get("release_id").asText()).isEqualTo(world.releaseId.toString());
        assertThat(body.get("payload").get("toRing").asText()).isEqualTo("beta");

        Map<String, Object> row = deliveryRow(subscription);
        assertThat(row.get("status")).isEqualTo("DELIVERED");
    }

    @Test
    void webhookSignatureBindsSecretBodyAndTimestampSoForgeryAndReplayFail() throws Exception {
        createSubscription(Map.of(
                "eventTypes",
                List.of("RELEASE_PROMOTED"),
                "channel",
                "WEBHOOK",
                "webhookUrl",
                "http://127.0.0.1:" + receiver.getAddress().getPort() + "/hook",
                "webhookSecret",
                WEBHOOK_SECRET));
        publish(new ReleasePromotedEvent(
                world.releaseId,
                world.releaseRef,
                world.app,
                world.version,
                world.org,
                null,
                "qa",
                "beta",
                List.of(),
                true,
                UUID.randomUUID(),
                Instant.now()));
        relay.drainOnce();
        assertThat(received).hasSize(1);
        ReceivedWebhook hook = received.get(0);

        // Forgery: an attacker without the secret cannot reproduce the
        // signature, whatever they know about body and timestamp.
        assertThat(WebhookDeliveryClient.sign("an-attacker-guessed-secret", hook.timestamp(), hook.body()))
                .isNotEqualTo(hook.signature());

        // Tamper: one rewritten payload byte breaks the signature -- the
        // MAC is over the exact delivered body, not a re-serialization.
        byte[] tampered = new String(hook.body(), StandardCharsets.UTF_8)
                .replace("\"toRing\":\"beta\"", "\"toRing\":\"prod\"")
                .getBytes(StandardCharsets.UTF_8);
        assertThat(WebhookDeliveryClient.sign(WEBHOOK_SECRET, hook.timestamp(), tampered))
                .isNotEqualTo(hook.signature());

        // Replay: the timestamp is inside the MAC, so a replayed POST
        // cannot be refreshed to re-enter a receiver's acceptance window
        // without the secret; and the sent timestamp is fresh
        // epoch-seconds -- the window check has real input.
        assertThat(WebhookDeliveryClient.sign(
                        WEBHOOK_SECRET, Long.toString(Instant.now().getEpochSecond() - 600), hook.body()))
                .isNotEqualTo(hook.signature());
        long sentAt = Long.parseLong(hook.timestamp());
        assertThat(Math.abs(Instant.now().getEpochSecond() - sentAt)).isLessThan(60);
    }

    @Test
    void webhookRetriesWithBackoffThenSucceeds() throws Exception {
        UUID subscription = createSubscription(Map.of(
                "eventTypes",
                List.of("TESTER_INVITED"),
                "channel",
                "WEBHOOK",
                "webhookUrl",
                "http://127.0.0.1:" + receiver.getAddress().getPort() + "/hook",
                "webhookSecret",
                WEBHOOK_SECRET));
        publish(new TesterInvitedEvent(UUID.randomUUID(), world.org, UUID.randomUUID(), false, Instant.now()));
        respondStatus.set(500);
        relay.drainOnce();

        // The first attempt failed: scheduled for retry with the error.
        Map<String, Object> row = deliveryRow(subscription);
        assertThat(row.get("status")).isEqualTo("SCHEDULED");
        assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(1);
        assertThat(String.valueOf(row.get("last_error"))).contains("500");

        // The receiver heals; the retry lands (the near-zero backoff is due).
        respondStatus.set(200);
        Thread.sleep(50);
        dispatcher.retryDue();
        Map<String, Object> healed = deliveryRow(subscription);
        assertThat(healed.get("status")).isEqualTo("DELIVERED");
        assertThat(((Number) healed.get("attempts")).intValue()).isEqualTo(2);
    }

    @Test
    void webhookExhaustsIntoTheVisibleFailedRow() throws Exception {
        UUID subscription = createSubscription(Map.of(
                "eventTypes",
                List.of("FEEDBACK_RECEIVED"),
                "channel",
                "WEBHOOK",
                "webhookUrl",
                "http://127.0.0.1:" + receiver.getAddress().getPort() + "/hook",
                "webhookSecret",
                WEBHOOK_SECRET));
        publish(new FeedbackReceivedEvent(
                UUID.randomUUID(),
                world.releaseId,
                world.releaseRef,
                world.app,
                world.version,
                world.artifact,
                world.org,
                world.tenant,
                UUID.randomUUID(),
                "BUG",
                0,
                Instant.now()));
        respondStatus.set(503);
        relay.drainOnce();
        // The three-attempt ceiling: first attempt above, two retries.
        Thread.sleep(50);
        dispatcher.retryDue();
        Thread.sleep(50);
        dispatcher.retryDue();

        Map<String, Object> row = deliveryRow(subscription);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(3);
        assertThat(String.valueOf(row.get("last_error"))).contains("503");

        // The failure is visible in the delivery log (AC5), through the API.
        // The exact-count assertion is scoped to this subscription: the
        // class's suites share one organization, and the SCHEDULED rows
        // other tests legitimately leave standing must not capsize an
        // exhaustion count (org-wide, the FAILED set includes this row).
        JsonNode failures = get(
                world.adminToken,
                "/api/v1/organizations/" + world.org + "/notification-deliveries?subscriptionId=" + subscription
                        + "&status=FAILED",
                200);
        assertThat(failures.get("items").size()).isEqualTo(1);
        assertThat(failures.get("items").get(0).get("status").asText()).isEqualTo("FAILED");
        assertThat(failures.get("items").get(0).get("eventName").asText())
                .isEqualTo(DomainEventName.FEEDBACK_RECEIVED.name());
    }

    // -----------------------------------------------------------------------
    // S12-02: the chat channels -- Slack blocks, the Teams card, and
    // the test action (issue #52, AC1/AC2/AC3/AC5)
    // -----------------------------------------------------------------------

    @Test
    void slackChannelDeliversFormattedBlocksSigned() throws Exception {
        UUID subscription = createSubscription(Map.of(
                "eventTypes",
                List.of("RELEASE_PROMOTED"),
                "channel",
                "SLACK",
                "webhookUrl",
                "http://127.0.0.1:" + receiver.getAddress().getPort() + "/hook",
                "webhookSecret",
                WEBHOOK_SECRET));
        publish(new ReleasePromotedEvent(
                world.releaseId,
                world.releaseRef,
                world.app,
                world.version,
                world.org,
                null,
                "qa",
                "beta",
                List.of(),
                false,
                UUID.randomUUID(),
                Instant.now()));
        relay.drainOnce();

        assertThat(received).hasSize(1);
        ReceivedWebhook hook = received.get(0);
        assertThat(hook.event()).isEqualTo("RELEASE_PROMOTED");
        // The chat channels ride the same signed wire: the signature
        // verifies over the exact provider-shaped body bytes.
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update(hook.timestamp().getBytes(StandardCharsets.UTF_8));
        mac.update((byte) '.');
        assertThat(hook.signature()).isEqualTo("sha256=" + HexFormat.of().formatHex(mac.doFinal(hook.body())));

        JsonNode body = wire.readTree(hook.body());
        assertThat(body.get("text").asText()).isEqualTo("Release promoted — " + world.releaseRef);
        JsonNode blocks = body.get("blocks");
        assertThat(blocks.get(0).get("type").asText()).isEqualTo("header");
        String fields = blocks.get(1).get("fields").toString();
        assertThat(fields).contains(world.releaseRef);
        assertThat(fields).contains("From ring").contains("To ring").contains("beta");
        // The deep link (AC1): back to the console's release page.
        String buttonUrl = blocks.get(3).get("elements").get(0).get("url").asText();
        assertThat(buttonUrl).endsWith("/releases/" + world.app + "/" + world.releaseId);

        Map<String, Object> row = deliveryRow(subscription);
        assertThat(row.get("status")).isEqualTo("DELIVERED");
        assertThat(String.valueOf(row.get("recipient_key"))).startsWith("slack:");
    }

    @Test
    void teamsChannelDeliversTheConnectorCard() throws Exception {
        UUID subscription = createSubscription(Map.of(
                "eventTypes",
                List.of("FEEDBACK_MARKED_CRITICAL"),
                "channel",
                "TEAMS",
                "webhookUrl",
                "http://127.0.0.1:" + receiver.getAddress().getPort() + "/hook",
                "webhookSecret",
                WEBHOOK_SECRET));
        publish(new FeedbackMarkedCriticalEvent(
                UUID.randomUUID(),
                world.releaseId,
                world.releaseRef,
                world.app,
                world.org,
                world.tenant,
                UUID.randomUUID(),
                "BUG",
                Instant.now()));
        relay.drainOnce();

        assertThat(received).hasSize(1);
        JsonNode card = wire.readTree(received.get(0).body());
        assertThat(card.get("@type").asText()).isEqualTo("MessageCard");
        assertThat(card.get("themeColor").asText()).isEqualTo("CC0000");
        assertThat(card.get("summary").asText()).isEqualTo("Feedback marked critical — " + world.releaseRef);
        JsonNode section = card.get("sections").get(0);
        assertThat(section.get("facts").toString()).contains("Type").contains("BUG");
        assertThat(section.get("potentialAction")
                        .get(0)
                        .get("targets")
                        .get(0)
                        .get("url")
                        .asText())
                .endsWith("/releases/" + world.app + "/" + world.releaseId);

        Map<String, Object> row = deliveryRow(subscription);
        assertThat(row.get("status")).isEqualTo("DELIVERED");
        assertThat(String.valueOf(row.get("recipient_key"))).startsWith("teams:");
    }

    @Test
    void chatDeliveryFailuresRetryVisiblyThroughTheLog() throws Exception {
        // AC5 for the chat channels: a refused Teams card is a
        // scheduled ledger row the retry drain keeps working, visible
        // through the delivery log API exactly like a webhook's.
        UUID subscription = createSubscription(Map.of(
                "eventTypes",
                List.of("TESTER_INVITED"),
                "channel",
                "TEAMS",
                "webhookUrl",
                "http://127.0.0.1:" + receiver.getAddress().getPort() + "/hook",
                "webhookSecret",
                WEBHOOK_SECRET));
        publish(new TesterInvitedEvent(UUID.randomUUID(), world.org, UUID.randomUUID(), false, Instant.now()));
        respondStatus.set(500);
        relay.drainOnce();

        Map<String, Object> row = deliveryRow(subscription);
        assertThat(row.get("status")).isEqualTo("SCHEDULED");
        assertThat(String.valueOf(row.get("last_error"))).contains("500");
        JsonNode scheduled = get(
                world.adminToken,
                "/api/v1/organizations/" + world.org + "/notification-deliveries?status=SCHEDULED",
                200);
        assertThat(scheduled.get("items").toString()).contains("teams:");

        respondStatus.set(200);
        Thread.sleep(50);
        dispatcher.retryDue();
        Map<String, Object> healed = deliveryRow(subscription);
        assertThat(healed.get("status")).isEqualTo("DELIVERED");
        assertThat(((Number) healed.get("attempts")).intValue()).isEqualTo(2);
    }

    @Test
    void testMessageVerifiesSlackConfigurationEndToEnd() throws Exception {
        UUID subscription = createSubscription(Map.of(
                "eventTypes",
                List.of("RELEASE_PROMOTED"),
                "channel",
                "SLACK",
                "webhookUrl",
                "http://127.0.0.1:" + receiver.getAddress().getPort() + "/hook",
                "webhookSecret",
                WEBHOOK_SECRET));
        int receivedBefore = received.size();

        JsonNode outcome = post(
                world.adminToken,
                "/api/v1/organizations/" + world.org + "/notification-subscriptions/" + subscription + "/test",
                Map.of(),
                200);

        assertThat(outcome.get("delivered").asBoolean()).isTrue();
        assertThat(outcome.get("channel").asText()).isEqualTo("SLACK");
        assertThat(outcome.hasNonNull("error")).isFalse();
        // The card the receiver got is the test template, rendered
        // through the same formatter a real event uses.
        assertThat(received).hasSize(receivedBefore + 1);
        JsonNode card = wire.readTree(received.get(received.size() - 1).body());
        assertThat(card.get("text").asText()).isEqualTo("ShipperDog test notification");
        assertThat(card.get("blocks").toString()).contains("/notifications");
        assertThat(received.get(received.size() - 1).event()).isEqualTo("NOTIFICATION_TEST_SENT");
        // The verification is audited (without the URL or secret).
        assertThat(audit.eventsOfType(AuditEventType.NOTIFICATION_SUBSCRIPTION_TESTED))
                .hasSize(1);
    }

    @Test
    void testMessageOfAFailingChannelReportsAndLedgers() throws Exception {
        UUID subscription = createSubscription(Map.of(
                "eventTypes",
                List.of("RELEASE_PROMOTED"),
                "channel",
                "TEAMS",
                "webhookUrl",
                "http://127.0.0.1:" + receiver.getAddress().getPort() + "/hook",
                "webhookSecret",
                WEBHOOK_SECRET));
        respondStatus.set(503);

        JsonNode outcome = post(
                world.adminToken,
                "/api/v1/organizations/" + world.org + "/notification-subscriptions/" + subscription + "/test",
                Map.of(),
                200);

        // Honest synchronous answer AND the same ledger posture a real
        // delivery gets: scheduled for retry, visible in the log.
        assertThat(outcome.get("delivered").asBoolean()).isFalse();
        assertThat(outcome.get("error").asText()).contains("503");
        Map<String, Object> row = deliveryRow(subscription);
        assertThat(row.get("status")).isEqualTo("SCHEDULED");
        assertThat(String.valueOf(row.get("recipient_key"))).startsWith("teams:");

        // Heal before leaving: the class's suites share one
        // organization, and a standing SCHEDULED row would exhaust
        // under a later test's retry pass (the receiver here dies with
        // the test) -- the ledger ends this test DELIVERED, not
        // leaking exhaustion into another test's counts.
        respondStatus.set(200);
        Thread.sleep(50);
        dispatcher.retryDue();
        assertThat(deliveryRow(subscription).get("status")).isEqualTo("DELIVERED");
    }

    @Test
    void testMessageOfAnInAppChannelLandsInTheRecipientsInbox() {
        // A dedicated recipient (the pager pattern): the class's
        // in-box count assertions stay owned by their tests.
        String testerToken = loginOfOrgRoleIn(world.org, "chat-tester", "RELEASE_MANAGER");
        UUID testerUserId = userIdOf(testerToken);
        UUID subscription = createSubscription(Map.of(
                "eventTypes", List.of("RELEASE_PROMOTED"),
                "channel", "IN_APP",
                "recipientUserIds", List.of(testerUserId.toString())));

        JsonNode outcome = post(
                world.adminToken,
                "/api/v1/organizations/" + world.org + "/notification-subscriptions/" + subscription + "/test",
                Map.of(),
                200);

        assertThat(outcome.get("delivered").asBoolean()).isTrue();
        assertThat(outcome.get("channel").asText()).isEqualTo("IN_APP");
        JsonNode inbox = get(testerToken, "/api/v1/notification-inbox?limit=10", 200);
        assertThat(inbox.get("items").size()).isEqualTo(1);
        assertThat(inbox.get("items").get(0).get("eventName").asText()).isEqualTo("NOTIFICATION_TEST_SENT");
    }

    @Test
    void theTestActionConcealsAndGuardsLikeTheRestOfTheSurface() {
        UUID subscription = createSubscription(Map.of(
                "eventTypes",
                List.of("RELEASE_PROMOTED"),
                "channel",
                "SLACK",
                "webhookUrl",
                "http://127.0.0.1:" + receiver.getAddress().getPort() + "/hook",
                "webhookSecret",
                WEBHOOK_SECRET));
        // A foreign organization's id conceals as the miss.
        client.post()
                .uri("/api/v1/organizations/" + UUID.randomUUID() + "/notification-subscriptions/" + subscription
                        + "/test")
                .headers(headers -> headers.setBearerAuth(world.adminToken))
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(404);
                    return null;
                });
        // The manage permission gates the action the surface's own
        // way: a caller without org:notifications:manage (QA holds
        // read only) is conceal-denied as the miss, exactly like the
        // CRUD routes beside this one.
        client.post()
                .uri("/api/v1/organizations/" + world.org + "/notification-subscriptions/" + subscription + "/test")
                .headers(headers -> headers.setBearerAuth(world.qaToken))
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(404);
                    return null;
                });
    }

    // -----------------------------------------------------------------------
    // AC6: notification failures never break the business flow
    // -----------------------------------------------------------------------

    @Test
    void aConcurrentEditRefusesWithThePublicConflict() {
        // The review's race finding: a stale writer must never
        // silently resurrect the rotated secret or replaced URL of a
        // concurrent winner. The stale transaction here is built
        // deterministically -- load, advance the row behind the
        // loaded snapshot's back, write -- and the write must refuse
        // with the public 409 shape, the whole stale transaction
        // rolling back with it.
        UUID subscription = createSubscription(Map.of(
                "eventTypes",
                List.of("RELEASE_PROMOTED"),
                "channel",
                "WEBHOOK",
                "webhookUrl",
                "https://hooks.example.test/conflict",
                "webhookSecret",
                WEBHOOK_SECRET));
        assertThatThrownBy(() -> inTransaction.executeWithoutResult(tx -> {
                    // This transaction's snapshot loads the row at its
                    // standing version.
                    routing.get(world.adminUserId, world.org, subscription);
                    // The concurrent winner's commit lands while the snapshot
                    // above stays open (same connection: a real row advance).
                    jdbc.update(
                            "UPDATE notification_subscriptions SET version = version + 1, "
                                    + "webhook_secret = 'rotated-by-the-winner-32-chars' WHERE id = ?",
                            subscription);
                    // The stale write refuses with the public conflict shape.
                    assertThatThrownBy(() -> routing.update(
                                    world.adminUserId,
                                    world.org,
                                    subscription,
                                    new NotificationSubscriptionService.UpdateCommand(
                                            List.of("FEEDBACK_MARKED_CRITICAL"), null, null, null, null, null)))
                            .isInstanceOf(NotificationException.class)
                            .satisfies(e -> assertThat(((NotificationException) e).code())
                                    .isEqualTo(NotificationException.Code.CONFLICT));
                }))
                .isInstanceOf(UnexpectedRollbackException.class);
        // Nothing the stale writer touched survived its refusal.
        assertThat(jdbc.queryForObject(
                        "SELECT event_types FROM notification_subscriptions WHERE id = ?", String.class, subscription))
                .contains("RELEASE_PROMOTED");
    }

    @Test
    void aSinkingWebhookNeverFailsTheRelayOrTheEvent() {
        // A URL nothing answers: every attempt fails, and the drain
        // still marks the row RELAYED -- the event happened; only the
        // notification is failing, in its own ledger.
        UUID subscription = createSubscription(Map.of(
                "eventTypes",
                List.of("TESTER_INVITED"),
                "channel",
                "WEBHOOK",
                "webhookUrl",
                "http://127.0.0.1:9/never-answers",
                "webhookSecret",
                WEBHOOK_SECRET));
        publish(new TesterInvitedEvent(UUID.randomUUID(), world.org, UUID.randomUUID(), false, Instant.now()));
        relay.drainOnce();

        String relayState = jdbc.queryForObject(
                "SELECT relay_state FROM notification_outbox WHERE event_name = 'TESTER_INVITED' "
                        + "ORDER BY created_at DESC LIMIT 1",
                String.class);
        assertThat(relayState).isEqualTo("RELAYED");
        Map<String, Object> row = deliveryRow(subscription);
        assertThat(row.get("status")).isEqualTo("SCHEDULED");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private World releasedWorld() {
        World built = new World();
        built.org = createOrg();
        built.adminToken = loginOfOrgRoleIn(built.org, "dlv-admin", "ORG_ADMIN");
        built.adminUserId = userIdOf(built.adminToken);
        built.qaToken = loginOfOrgRoleIn(built.org, "dlv-qa", "QA_ENGINEER");
        String ownerToken = loginOfOrgRoleIn(built.org, "dlv-owner", "APP_OWNER");
        built.tenant = createTenant(built.adminToken, built.org, "Delivery Bank", "dlv-a");
        built.app = createSharedApp(built.adminToken, built.org, "Delivery Banking", "com.dlvbanking");
        built.version = createVersion(ownerToken, built.app, "1.0.0");
        built.artifact = insertArtifact(built.version, "IOS", 1);
        authorize(built.adminToken, built.app, built.tenant);
        Map<String, Object> release = new LinkedHashMap<>();
        release.put("applicationId", built.app.toString());
        release.put("versionId", built.version.toString());
        release.put("ring", "qa");
        release.put("artifacts", List.of(Map.of("platform", "IOS", "artifactId", built.artifact.toString())));
        release.put("targets", List.of(Map.of("tenantId", built.tenant.toString(), "testerGroupIds", List.of())));
        JsonNode created =
                post(loginOfOrgRoleIn(built.org, "dlv-manager", "RELEASE_MANAGER"), "/api/v1/releases", release, 201);
        built.releaseId = UUID.fromString(created.get("id").asText());
        built.releaseRef = created.get("releaseRef").asText();
        return built;
    }

    private void publish(Object event) {
        inTransaction.executeWithoutResult(tx -> publisher.publishEvent(event));
    }

    private Map<String, Object> deliveryRow(UUID subscriptionId) {
        return jdbc.queryForMap(
                "SELECT * FROM notification_deliveries WHERE subscription_id = ? " + "ORDER BY created_at DESC LIMIT 1",
                subscriptionId);
    }

    private UUID createSubscription(Map<String, Object> body) {
        JsonNode created = client.post()
                .uri("/api/v1/organizations/" + world.org + "/notification-subscriptions")
                .headers(headers -> {
                    headers.setBearerAuth(world.adminToken);
                    headers.set(HttpHeaders.CONTENT_TYPE, "application/json");
                })
                .body(body)
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode().value())
                            .as("create subscription %s", body.get("channel"))
                            .isEqualTo(201);
                    return wire.readTree(response.getBody().readAllBytes());
                });
        return UUID.fromString(created.get("id").asText());
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
                    return bytes.length == 0 ? wire.createObjectNode() : wire.readTree(bytes);
                });
    }

    /** Inserts an artifact row directly (the release fixture's binary-free shortcut). */
    private UUID insertArtifact(UUID versionId, String platform, long build) {
        UUID artifactId = UUID.randomUUID();
        Map<String, Object> owner =
                jdbc.queryForMap("SELECT organization_id, tenant_id FROM versions WHERE id = ?", versionId);
        String extension = "ANDROID".equals(platform) ? "apk" : "ipa";
        jdbc.update(
                "INSERT INTO artifacts (id, version_id, platform, file_name, storage_key, sha256, size, "
                        + "build_number, status, organization_id, tenant_id) "
                        + "VALUES (?, ?, ?::platform, ?, ?, ?, ?, ?, ?::artifact_status, ?, ?)",
                artifactId,
                versionId,
                platform,
                platform.toLowerCase(java.util.Locale.ROOT) + "-" + build + "." + extension,
                "fixture/" + artifactId + "/artifact",
                "a1".repeat(32),
                1024L,
                build,
                "READY",
                owner.get("organization_id"),
                owner.get("tenant_id"));
        return artifactId;
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

    private UUID createSharedApp(String token, UUID organizationId, String name, String bundleId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ownershipScope", "ORGANIZATION");
        body.put("organizationId", organizationId.toString());
        body.put("name", name);
        body.put("platforms", List.of("IOS", "ANDROID"));
        body.put("bundleId", bundleId);
        body.put("packageName", bundleId);
        return UUID.fromString(
                post(token, "/api/v1/applications", body, 201).get("id").asText());
    }

    private void authorize(String token, UUID applicationId, UUID tenantId) {
        post(token, "/api/v1/applications/" + applicationId + "/tenants/" + tenantId, Map.of(), 201);
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

    private UUID createOrg() {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        return UUID.fromString(
                post(devToken, "/api/v1/organizations", Map.of("name", "Delivery Notifications Corp"), 201)
                        .get("id")
                        .asText());
    }

    private JsonNode post(String bearer, String uri, Map<String, Object> body, int expectedStatus) {
        return client.post()
                .uri(uri)
                .headers(headers -> {
                    headers.setBearerAuth(bearer);
                    headers.set(HttpHeaders.CONTENT_TYPE, "application/json");
                })
                .body(body)
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode().value())
                            .as("POST %s -> %d", uri, expectedStatus)
                            .isEqualTo(expectedStatus);
                    return wire.readTree(response.getBody().readAllBytes());
                });
    }

    private String loginOfOrgRoleIn(UUID orgId, String slug, String role) {
        String email = slug + "@example.com";
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        JsonNode invitation = client.post()
                .uri("/api/v1/auth/invitations")
                .headers(headers -> headers.setBearerAuth(devToken))
                .body(Map.of("email", email, "organizationId", orgId.toString(), "role", role))
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(201);
                    return wire.readTree(response.getBody().readAllBytes());
                });
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
                        "Delivery " + role))
                .retrieve()
                .body(String.class);
        return accessToken(login(email, PASSWORD));
    }

    private UUID userIdOf(String bearer) {
        String claims = bearer.split("\\.")[1];
        try {
            JsonNode decoded = wire.readTree(Base64.getUrlDecoder().decode(claims));
            return UUID.fromString(decoded.get("sub").asText());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not decode the token", e);
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
            return wire.readTree(tokensJson).get("accessToken").asText();
        } catch (Exception e) {
            throw new IllegalStateException("could not parse the token pair", e);
        }
    }
}
