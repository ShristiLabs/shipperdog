package com.shipperdog.api.notification.internal.bus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shipperdog.api.artifact.ArtifactDownloadIssuedEvent;
import com.shipperdog.api.artifact.ArtifactUploadedEvent;
import com.shipperdog.api.artifact.ArtifactValidatedEvent;
import com.shipperdog.api.feedback.FeedbackMarkedCriticalEvent;
import com.shipperdog.api.feedback.FeedbackReceivedEvent;
import com.shipperdog.api.identity.AuditCapture;
import com.shipperdog.api.notification.DomainEvent;
import com.shipperdog.api.notification.DomainEventName;
import com.shipperdog.api.notification.internal.entity.NotificationOutboxEntity;
import com.shipperdog.api.notification.internal.repository.NotificationOutboxRepository;
import com.shipperdog.api.release.ReleasePromotedEvent;
import com.shipperdog.api.release.ReleaseTransitionedEvent;
import com.shipperdog.api.tester.TesterInvitedEvent;
import com.shipperdog.api.workflow.ApprovalRequestedEvent;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The bus acceptance flow (issue #51, S12-01, AC1/AC2/AC3): the wired
 * transitions land in the outbox with their full entity context and
 * the originating request's correlation id -- the tester invite is a
 * REAL API transition; the release-created leg rides the world's own
 * release creation; the remaining wired event types publish through
 * their typed records against the world's real entity ids (the outbox
 * carries hard foreign keys, so context is always real). The relay
 * drains PENDING rows onto the bus and marks them RELAYED, and a
 * replayed row (the crash-between-publish-and-mark of at-least-once)
 * delivers exactly once -- the ledger's idempotency by event id.
 *
 * <p>Drives the relay's drain directly with the schedule disabled
 * (the analytics rollup suite's pattern), so every assertion is
 * deterministic.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef",
            "shipperdog.notifications.relay-enabled=false",
            "shipperdog.notifications.relay-fixed-delay=PT1H"
        })
@Testcontainers
@Import({AuditCapture.class, NotificationBusIntegrationTest.BusFixtures.class})
class NotificationBusIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";
    private static final String PASSWORD = "a-perfectly-valid-password";

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
    private ApplicationEventPublisher publisher;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private DomainEventCapture captured;

    @Autowired
    private ObjectMapper mapper;

    private RestClient client;

    private TransactionTemplate inTransaction;

    private static World world;

    /** The world: an organization, its personas, a shared app with a version, artifact, tenant, and release. */
    private static class World {
        UUID org;
        String adminToken;
        UUID adminUserId;
        UUID tenant;
        UUID app;
        UUID version;
        UUID artifact;
        UUID releaseId;
        String releaseRef;
    }

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        inTransaction = new TransactionTemplate(transactions);
        captured.events.clear();
        if (world == null) {
            world = releasedWorld();
        }
    }

    // -----------------------------------------------------------------------
    // AC1/AC2: the wired transitions, the context, the correlation id
    // -----------------------------------------------------------------------

    @Test
    void testerInviteIsABusEventWithRequestCorrelation() {
        String correlation = "bus-invite-correlation-0001";
        JsonNode invited = inviteTester("bus-tester@example.com", correlation);
        Map<String, Object> row = latestOutboxRowByCorrelation("TESTER_INVITED", correlation);
        assertThat(row.get("organization_id")).isEqualTo(world.org);
        assertThat(row.get("correlation_id")).isEqualTo(correlation);
        assertThat(row.get("payload").toString().replace(" ", "")).contains("\"reinvite\":false");
        // The relay publishes it and marks the row.
        relay.drainOnce();
        assertThat(captured.names()).contains(DomainEventName.TESTER_INVITED);
        assertThat(row.get("relay_state")).isEqualTo("PENDING"); // the snapshot predates the drain
        assertThat(relayStateOf(UUID.fromString(row.get("id").toString()))).isEqualTo("RELAYED");
        assertThat(invited.get("id")).isNotNull();
    }

    @Test
    void releaseCreationIsABusEventOffTheRealApiTransition() {
        Map<String, Object> row = latestOutboxRow("RELEASE_CREATED");
        assertThat(row.get("release_id")).isEqualTo(world.releaseId);
        assertThat(row.get("application_id")).isEqualTo(world.app);
        assertThat(row.get("version_id")).isEqualTo(world.version);
        assertThat(row.get("organization_id")).isEqualTo(world.org);
        assertThat(row.get("payload").toString().replace(" ", "")).contains("\"ring\":\"qa\"");
        // The API request carried a correlation id; the event carries it.
        assertThat(row.get("correlation_id")).isEqualTo("bus-release-correlation-0002");
    }

    @Test
    void eachWiredEventTypeMapsToItsBusNameWithContext() {
        // The recorder's mapping, asserted at the seam it owns: each
        // typed event becomes exactly one outbox append with its bus
        // name and entity context. Driven on the recorder directly --
        // the in-process publisher would fan every typed event into
        // every module's listeners (the workflow module's transition
        // consumer among them), which is the production coupling this
        // mapping must not depend on.
        NotificationOutboxRepository repository = org.mockito.Mockito.mock(NotificationOutboxRepository.class);
        org.mockito.ArgumentCaptor<NotificationOutboxEntity> appended =
                org.mockito.ArgumentCaptor.forClass(NotificationOutboxEntity.class);
        OutboxRecorder recorder = new OutboxRecorder(repository, java.time.Clock.systemUTC());
        UUID actor = world.adminUserId;
        UUID release = world.releaseId;

        recorder.on(new ArtifactUploadedEvent(
                world.artifact,
                world.version,
                world.app,
                world.org,
                null,
                "IOS",
                "app.ipa",
                1024L,
                "a1".repeat(32),
                actor,
                Instant.now()));
        recorder.on(new ArtifactValidatedEvent(
                world.artifact,
                world.version,
                world.app,
                world.org,
                null,
                "IOS",
                "PASSED",
                null,
                actor,
                Instant.now()));
        recorder.on(new ArtifactValidatedEvent(
                world.artifact,
                world.version,
                world.app,
                world.org,
                null,
                "ANDROID",
                "FAILED",
                "METADATA_FAILED",
                actor,
                Instant.now()));
        recorder.on(new ArtifactValidatedEvent(
                world.artifact,
                world.version,
                world.app,
                world.org,
                null,
                "ANDROID",
                "BLOCKED",
                null,
                actor,
                Instant.now()));
        recorder.on(new ArtifactDownloadIssuedEvent(
                world.artifact, world.version, world.app, world.org, null, "IOS", actor, Instant.now()));
        recorder.on(new ReleaseTransitionedEvent(
                release,
                world.releaseRef,
                world.app,
                world.version,
                world.org,
                null,
                "DRAFT",
                "PENDING_APPROVAL",
                null,
                actor,
                Instant.now()));
        recorder.on(new ReleaseTransitionedEvent(
                release,
                world.releaseRef,
                world.app,
                world.version,
                world.org,
                null,
                "PENDING_APPROVAL",
                "APPROVED",
                null,
                actor,
                Instant.now()));
        recorder.on(new ReleaseTransitionedEvent(
                release,
                world.releaseRef,
                world.app,
                world.version,
                world.org,
                null,
                "ACTIVE",
                "PROMOTED",
                null,
                actor,
                Instant.now()));
        recorder.on(new ApprovalRequestedEvent(
                release, world.releaseRef, world.app, world.org, null, 1, 1, "QA", actor, Instant.now()));
        recorder.on(new ReleasePromotedEvent(
                release,
                world.releaseRef,
                world.app,
                world.version,
                world.org,
                null,
                "qa",
                "beta",
                List.of(),
                false,
                actor,
                Instant.now()));
        recorder.on(new FeedbackReceivedEvent(
                UUID.randomUUID(),
                release,
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
        recorder.on(new FeedbackMarkedCriticalEvent(
                UUID.randomUUID(),
                release,
                world.releaseRef,
                world.app,
                world.org,
                world.tenant,
                UUID.randomUUID(),
                "BUG",
                Instant.now()));
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.times(10)).save(appended.capture());

        // The frozen mapping (docs/notifications.md): one name per
        // wired transition, BLOCKED suppressed, the PROMOTED crossing
        // suppressed in favor of the promotion hop's own event.
        Map<String, NotificationOutboxEntity> byName = new java.util.LinkedHashMap<>();
        for (NotificationOutboxEntity row : appended.getAllValues()) {
            NotificationOutboxEntity existing =
                    byName.putIfAbsent(row.getEventName().name(), row);
            assertThat(existing)
                    .as("one row per bus name: %s", row.getEventName())
                    .isNull();
        }
        assertThat(byName.keySet())
                .containsExactlyInAnyOrder(
                        "ARTIFACT_UPLOADED",
                        "ARTIFACT_VALIDATED",
                        "ARTIFACT_FAILED",
                        "TESTER_DOWNLOADED",
                        "RELEASE_TRANSITIONED",
                        "RELEASE_APPROVED",
                        "APPROVAL_REQUESTED",
                        "RELEASE_PROMOTED",
                        "FEEDBACK_RECEIVED",
                        "FEEDBACK_MARKED_CRITICAL");
        // No TESTER_INVITED row in this pass (its integration proof is
        // the invite test); the two suppressed events (the BLOCKED
        // outcome and the PROMOTED crossing) append nothing -- ten
        // saves for twelve events.
        NotificationOutboxEntity promotion = byName.get("RELEASE_PROMOTED");
        assertThat(promotion.getReleaseId()).isEqualTo(release);
        assertThat(promotion.getPayload()).containsEntry("toRing", "beta");
        NotificationOutboxEntity approved = byName.get("RELEASE_APPROVED");
        assertThat(approved.getPayload()).containsEntry("toStatus", "APPROVED");
    }

    @Test
    void eventsCarryTheMdcsCorrelationWhenOneExists() {
        MDC.put("correlationId", "direct-publish-correlation-42");
        try {
            publish(new TesterInvitedEvent(UUID.randomUUID(), world.org, UUID.randomUUID(), false, Instant.now()));
        } finally {
            MDC.remove("correlationId");
        }
        Map<String, Object> row = latestOutboxRowByCorrelation("TESTER_INVITED", "direct-publish-correlation-42");
        assertThat(row.get("correlation_id")).isEqualTo("direct-publish-correlation-42");
    }

    // -----------------------------------------------------------------------
    // AC3: at-least-once relay, idempotent consumers
    // -----------------------------------------------------------------------

    @Test
    void replayedOutboxRowDeliversOncePerRecipient() {
        JsonNode subscription = createSubscription(Map.of(
                "eventTypes", List.of("TESTER_INVITED"),
                "channel", "IN_APP",
                "recipientUserIds", List.of(world.adminUserId.toString())));
        UUID subscriptionId = UUID.fromString(subscription.get("id").asText());

        publish(new TesterInvitedEvent(UUID.randomUUID(), world.org, UUID.randomUUID(), false, Instant.now()));
        relay.drainOnce();
        UUID eventId = jdbc.queryForObject(
                "SELECT id FROM notification_outbox WHERE event_name = 'TESTER_INVITED' "
                        + "AND payload ->> 'reinvite' = 'false' ORDER BY created_at DESC LIMIT 1",
                UUID.class);
        assertThat(deliveryCount(eventId, subscriptionId)).isEqualTo(1);

        // The crash-between-publish-and-mark replay: the row re-enters
        // PENDING and drains again -- the same event id publishes
        // twice, the ledger collapses the second dispatch.
        jdbc.update(
                "UPDATE notification_outbox SET relay_state = 'PENDING', next_attempt_at = now(), "
                        + "relayed_at = NULL WHERE id = ?",
                eventId);
        relay.drainOnce();
        assertThat(deliveryCount(eventId, subscriptionId)).isEqualTo(1);
        assertThat(relayStateOf(eventId)).isEqualTo("RELAYED");
    }

    // -----------------------------------------------------------------------
    // AC1: the outbox append is the business transaction -- atomically
    // -----------------------------------------------------------------------

    @Test
    void aRolledBackBusinessChangeCancelsItsBusEvent() {
        int before = outboxCount("TESTER_INVITED");
        // The event publishes, then the surrounding transaction fails:
        // BEFORE_COMMIT made the insert part of that transaction, so the
        // rollback cancels the event with the business write.
        assertThatThrownBy(() -> inTransaction.executeWithoutResult(tx -> {
                    publisher.publishEvent(new TesterInvitedEvent(
                            UUID.randomUUID(), world.org, UUID.randomUUID(), false, Instant.now()));
                    throw new IllegalStateException("the business write fails late");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(outboxCount("TESTER_INVITED")).isEqualTo(before);

        // The committed twin lands -- the cancel was the rollback, not a
        // listener that stopped working.
        publish(new TesterInvitedEvent(UUID.randomUUID(), world.org, UUID.randomUUID(), false, Instant.now()));
        assertThat(outboxCount("TESTER_INVITED")).isEqualTo(before + 1);
    }

    // -----------------------------------------------------------------------
    // Fixtures and helpers
    // -----------------------------------------------------------------------

    /** Records every relayed bus event (the in-process consumer's observation point). */
    static class DomainEventCapture {
        final List<DomainEvent> events = new CopyOnWriteArrayList<>();

        @EventListener
        void on(DomainEvent event) {
            events.add(event);
        }

        List<DomainEventName> names() {
            return events.stream().map(DomainEvent::name).toList();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class BusFixtures {
        @Bean
        DomainEventCapture domainEventCapture() {
            return new DomainEventCapture();
        }
    }

    private World releasedWorld() {
        World built = new World();
        built.org = createOrg();
        built.adminToken = loginOfOrgRoleIn(built.org, "bus-admin", "ORG_ADMIN");
        built.adminUserId = userIdOf(built.adminToken);
        String ownerToken = loginOfOrgRoleIn(built.org, "bus-owner", "APP_OWNER");
        built.tenant = createTenant(built.adminToken, built.org, "Bus Bank", "bus-a");
        built.app = createSharedApp(built.adminToken, built.org, "Bus Banking", "com.busbanking");
        built.version = createVersion(ownerToken, built.app, "1.0.0");
        built.artifact = insertArtifact(built.version, "IOS", 1);
        authorize(built.adminToken, built.app, built.tenant);
        Map<String, Object> release = new LinkedHashMap<>();
        release.put("applicationId", built.app.toString());
        release.put("versionId", built.version.toString());
        release.put("ring", "qa");
        release.put("artifacts", List.of(Map.of("platform", "IOS", "artifactId", built.artifact.toString())));
        release.put("targets", List.of(Map.of("tenantId", built.tenant.toString(), "testerGroupIds", List.of())));
        JsonNode created = postWithCorrelation(
                loginOfOrgRoleIn(built.org, "bus-manager", "RELEASE_MANAGER"),
                "/api/v1/releases",
                release,
                201,
                "bus-release-correlation-0002");
        built.releaseId = UUID.fromString(created.get("id").asText());
        built.releaseRef = created.get("releaseRef").asText();
        return built;
    }

    private void publish(Object event) {
        inTransaction.executeWithoutResult(tx -> publisher.publishEvent(event));
    }

    private int outboxCount(String name) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM notification_outbox WHERE event_name = ?", Integer.class, name);
        return count == null ? 0 : count;
    }

    private Map<String, Object> latestOutboxRow(String name) {
        return jdbc.queryForMap(
                "SELECT * FROM notification_outbox WHERE event_name = ? ORDER BY created_at DESC LIMIT 1", name);
    }

    private Map<String, Object> latestOutboxRowByCorrelation(String name, String correlation) {
        return jdbc.queryForMap(
                "SELECT * FROM notification_outbox WHERE event_name = ? AND correlation_id = ? "
                        + "ORDER BY created_at DESC LIMIT 1",
                name,
                correlation);
    }

    private String relayStateOf(UUID eventId) {
        return jdbc.queryForObject("SELECT relay_state FROM notification_outbox WHERE id = ?", String.class, eventId);
    }

    private int deliveryCount(UUID eventId, UUID subscriptionId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM notification_deliveries WHERE event_id = ? AND subscription_id = ?",
                Integer.class,
                eventId,
                subscriptionId);
        return count == null ? 0 : count;
    }

    private JsonNode createSubscription(Map<String, Object> body) {
        return exchange(
                "POST",
                "/api/v1/organizations/" + world.org + "/notification-subscriptions",
                body,
                201,
                world.adminToken);
    }

    private JsonNode inviteTester(String email, String correlation) {
        return postWithCorrelation(
                world.adminToken,
                "/api/v1/testers",
                Map.of("organizationId", world.org.toString(), "email", email, "fullName", "Bus Tester"),
                201,
                correlation);
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
        return UUID.fromString(post(devToken, "/api/v1/organizations", Map.of("name", "Bus Notifications Corp"), 201)
                .get("id")
                .asText());
    }

    private JsonNode postWithCorrelation(
            String bearer, String uri, Map<String, Object> body, int expectedStatus, String correlation) {
        return client.post()
                .uri(uri)
                .headers(headers -> {
                    headers.setBearerAuth(bearer);
                    headers.set(HttpHeaders.CONTENT_TYPE, "application/json");
                    if (correlation != null) {
                        headers.set("X-Correlation-Id", correlation);
                    }
                })
                .body(body)
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode().value())
                            .as("POST %s -> %d", uri, expectedStatus)
                            .isEqualTo(expectedStatus);
                    return mapper.readTree(response.getBody().readAllBytes());
                });
    }

    private JsonNode post(String bearer, String uri, Map<String, Object> body, int expectedStatus) {
        return postWithCorrelation(bearer, uri, body, expectedStatus, null);
    }

    private JsonNode exchange(String method, String uri, Map<String, Object> body, int expectedStatus, String bearer) {
        return client.method(org.springframework.http.HttpMethod.valueOf(method))
                .uri(uri)
                .headers(headers -> {
                    headers.setBearerAuth(bearer);
                    headers.set(HttpHeaders.CONTENT_TYPE, "application/json");
                })
                .body(body)
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode().value())
                            .as("%s %s -> %d", method, uri, expectedStatus)
                            .isEqualTo(expectedStatus);
                    return mapper.readTree(response.getBody().readAllBytes());
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
                    return mapper.readTree(response.getBody().readAllBytes());
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
                        "Bus " + role))
                .retrieve()
                .body(String.class);
        return accessToken(login(email, PASSWORD));
    }

    private UUID userIdOf(String bearer) {
        String claims = bearer.split("\\.")[1];
        JsonNode decoded = mapper.readTree(Base64.getUrlDecoder().decode(claims));
        return UUID.fromString(decoded.get("sub").asText());
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
