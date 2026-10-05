package com.shipperdog.api.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shipperdog.api.config.CorrelationIdFilter;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
 * End-to-end acceptance of the audit framework (issue #6) against a real
 * PostgreSQL and HTTP stack: transactional outbox semantics (audit rows
 * commit and roll back with the business transaction), immediate
 * persistence off-transaction, correlation-id propagation from request
 * start into the audit record, the admin query API with its filters and
 * pagination cap, opaque 403s for non-admins whose denials are themselves
 * audited, and before/after JSON snapshots persisted redacted.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
class AuditFrameworkIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";
    private static final String MEMBER_PASSWORD = "a-perfectly-valid-password";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @LocalServerPort
    int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AuditLogger audit;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private ObjectMapper mapper;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** Transactional outbox: the audit row commits with the business change. */
    @Test
    void auditRowCommitsWithTheBusinessTransaction() {
        UUID marker = UUID.randomUUID();
        TransactionTemplate transaction = new TransactionTemplate(transactions);

        transaction.executeWithoutResult(
                status -> publish(AuditEventType.ORGANIZATION_UPDATED, marker, null, Instant.now()));

        assertThat(countByMarker(marker))
                .as("committed transaction persists its audit row")
                .isEqualTo(1);
    }

    /** Transactional outbox: a rolled-back change leaves no audit row. */
    @Test
    void auditRowRollsBackWithTheBusinessTransaction() {
        UUID marker = UUID.randomUUID();
        TransactionTemplate transaction = new TransactionTemplate(transactions);

        transaction.executeWithoutResult(status -> {
            publish(AuditEventType.ORGANIZATION_UPDATED, marker, null, Instant.now());
            status.setRollbackOnly();
        });

        assertThat(countByMarker(marker))
                .as("rolled-back transaction persists no audit row")
                .isZero();
    }

    /**
     * Same-tx failure semantics: an audit insert that the database rejects
     * (reason longer than the column) fails the whole transaction — the
     * business change and its audit trail cannot diverge.
     */
    @Test
    void auditInsertFailureRollsBackTheWholeTransaction() {
        UUID marker = UUID.randomUUID();
        TransactionTemplate transaction = new TransactionTemplate(transactions);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                    publish(AuditEventType.ORGANIZATION_UPDATED, marker, null, Instant.now());
                    audit.record(AuditEvent.builder(AuditEventType.ORGANIZATION_UPDATED)
                            .occurredAt(Instant.now())
                            .userId(marker)
                            .success(true)
                            .detail(Map.of("marker", marker.toString()))
                            .reason("r".repeat(600))
                            .build());
                }))
                .isInstanceOf(RuntimeException.class);

        assertThat(countByMarker(marker))
                .as("the failed insert rolls back the valid event committed alongside it")
                .isZero();
    }

    /** Off-transaction publishes (denials, login flows) persist immediately. */
    @Test
    void publishOutsideAnyTransactionPersistsImmediately() {
        UUID marker = UUID.randomUUID();

        publish(AuditEventType.TOKEN_ISSUED, marker, null, Instant.now());

        assertThat(countByMarker(marker))
                .as("fallback execution persists without a transaction")
                .isEqualTo(1);
    }

    @Test
    void correlationIdIsPropagatedFromRequestStartIntoAuditRows() {
        String inbound = "it-framework-" + UUID.randomUUID().toString().substring(0, 8);

        int status = postForStatus(
                "/api/v1/auth/login", Map.of("email", DEV_ADMIN_EMAIL, "password", DEV_ADMIN_PASSWORD), inbound);

        assertThat(status).isEqualTo(200);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM audit_events WHERE correlation_id = ?", Integer.class, inbound))
                .as("the request's audit rows carry its correlation id")
                .isPositive();
    }

    @Test
    void missingCorrelationIdIsGeneratedAndHostileValuesAreReplaced() {
        String hostile = "steal-me<script>";

        int status = postForStatus(
                "/api/v1/auth/login", Map.of("email", DEV_ADMIN_EMAIL, "password", DEV_ADMIN_PASSWORD), hostile);

        assertThat(status).isEqualTo(200);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM audit_events WHERE correlation_id = ?", Integer.class, hostile))
                .as("hostile values never reach the audit store")
                .isZero();
    }

    /** Filter ordering: the correlation filter wraps the security chains. */
    @Test
    void securityDenialsCarryACorrelationId() {
        String[] correlation = new String[1];

        client.get().uri("/api/v1/audit").exchange((request, response) -> {
            assertThat(response.getStatusCode().value())
                    .as("no token is an authentication failure")
                    .isEqualTo(401);
            correlation[0] = response.getHeaders().getFirst(CorrelationIdFilter.HEADER);
            return 401;
        });

        assertThat(correlation[0]).as("401s are correlated too").isNotBlank();
    }

    @Test
    void adminQueryFiltersByActorTenantTypeAndTimeRange() {
        UUID actor = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        Instant old = Instant.now().minus(10, ChronoUnit.HOURS);
        Instant recent = Instant.now().minus(30, ChronoUnit.MINUTES);

        publish(AuditEventType.TENANT_MEMBER_ROLE_CHANGED, actor, tenant, old);
        publish(AuditEventType.TENANT_MEMBER_REMOVED, actor, tenant, recent);
        publish(AuditEventType.TOKEN_ISSUED, UUID.randomUUID(), null, recent);

        String uri = "/api/v1/audit?actor=%s&tenant=%s&eventType=TENANT_MEMBER_REMOVED&from=%s"
                .formatted(actor, tenant, Instant.now().minus(1, ChronoUnit.HOURS));
        JsonNode page = getJson(uri, accessToken(), 200);

        assertThat(page.get("items").size()).isEqualTo(1);
        assertThat(page.hasNonNull("next_cursor"))
                .as("a single match is the last page")
                .isFalse();
        JsonNode event = page.get("items").get(0);
        assertThat(event.get("eventType").asText()).isEqualTo("TENANT_MEMBER_REMOVED");
        assertThat(event.get("tenantId").asText()).isEqualTo(tenant.toString());
        assertThat(event.get("occurredAt").asText())
                .as("the time-range filter excludes the ten-hour-old event")
                .isNotEqualTo(old.toString());
    }

    /** Conjunctive filters: an OR here would blend scopes in one response. */
    @Test
    void filtersAreConjunctiveAcrossOrganizationAndTenantScopes() {
        UUID organization = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();

        audit.record(AuditEvent.builder(AuditEventType.ORGANIZATION_UPDATED)
                .occurredAt(Instant.now())
                .orgId(organization)
                .success(true)
                .detail(Map.of("marker", organization.toString()))
                .build());
        audit.record(AuditEvent.builder(AuditEventType.TENANT_MEMBER_REMOVED)
                .occurredAt(Instant.now())
                .tenantId(tenant)
                .success(true)
                .detail(Map.of("marker", tenant.toString()))
                .build());

        JsonNode both =
                getJson("/api/v1/audit?organization=%s&tenant=%s".formatted(organization, tenant), accessToken(), 200);
        JsonNode orgOnly = getJson("/api/v1/audit?organization=" + organization, accessToken(), 200);

        assertThat(both.get("items").isEmpty())
                .as("no event is both organization- and tenant-scoped; combined filters match nothing")
                .isTrue();
        assertThat(orgOnly.get("items").size())
                .as("control: the organization filter alone matches the seeded event")
                .isEqualTo(1);
    }

    /** Typed filters reject garbage instead of ignoring or erroring opaquely. */
    @Test
    void malformedFilterValuesAreRejectedAsBadRequest() {
        getJson("/api/v1/audit?actor=not-a-uuid", accessToken(), 400);
        getJson("/api/v1/audit?eventType=NOT_AN_EVENT_TYPE", accessToken(), 400);
    }

    @Test
    void adminQueryOrdersNewestFirstAndRejectsUnboundedLimits() {
        publish(
                AuditEventType.TOKEN_ISSUED,
                UUID.randomUUID(),
                null,
                Instant.now().minus(5, ChronoUnit.MINUTES));
        publish(AuditEventType.TOKEN_ISSUED, UUID.randomUUID(), null, Instant.now());

        // No unbounded queries (issue #8): limits above the hard cap are
        // rejected, never silently clamped.
        JsonNode rejected = getJson("/api/v1/audit?limit=500", accessToken(), 400);
        assertThat(rejected.get("code").asText()).isEqualTo("INVALID_REQUEST");

        JsonNode page = getJson("/api/v1/audit?limit=100", accessToken(), 200);
        JsonNode events = page.get("items");
        assertThat(events.size()).isLessThanOrEqualTo(100);
        for (int i = 1; i < events.size(); i++) {
            assertThat(Instant.parse(events.get(i - 1).get("occurredAt").asText()))
                    .as("events are ordered newest first")
                    .isAfterOrEqualTo(
                            Instant.parse(events.get(i).get("occurredAt").asText()));
        }
    }

    /** Cursor paging: garbage cursors and malformed limits are 400s. */
    @Test
    void paginationRejectsInvalidCursorsAndLimits() {
        JsonNode badCursor = getJson("/api/v1/audit?cursor=%%%.not-a-cursor", accessToken(), 400);
        assertThat(badCursor.get("code").asText()).isEqualTo("INVALID_CURSOR");
        JsonNode badLimit = getJson("/api/v1/audit?limit=zero", accessToken(), 400);
        assertThat(badLimit.get("code").asText()).isEqualTo("INVALID_REQUEST");
        JsonNode zeroLimit = getJson("/api/v1/audit?limit=0", accessToken(), 400);
        assertThat(zeroLimit.get("code").asText()).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void nonAdminsGetAnOpaque403AndTheirDenialIsAudited() {
        registerUser("audit.viewer@example.com");
        String viewerToken = accessTokenFor("audit.viewer@example.com", MEMBER_PASSWORD);
        UUID viewerId =
                jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, "audit.viewer@example.com");
        int before = auditCountFor(viewerId, "ACCESS_DENIED");

        int status = client.get()
                .uri("/api/v1/audit")
                .headers(headers -> authorize(headers, viewerToken))
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(403);
                    JsonNode body = mapper.readTree(
                            new String(response.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                    assertThat(body.get("code").asText()).isEqualTo("FORBIDDEN");
                    assertThat(body.get("status").asInt()).isEqualTo(403);
                    assertThat(body.has("error"))
                            .as("legacy error-key must be gone")
                            .isFalse();
                    return 403;
                });

        assertThat(status).isEqualTo(403);
        assertThat(auditCountFor(viewerId, "ACCESS_DENIED") - before)
                .as("the denial itself is audited")
                .isEqualTo(1);
        String denial = jdbc.queryForObject(
                "SELECT detail::text FROM audit_events WHERE user_id = ? AND event_type = 'ACCESS_DENIED' "
                        + "ORDER BY occurred_at DESC LIMIT 1",
                String.class,
                viewerId);
        assertThat(denial).contains("platform:audit:read").contains("/api/v1/audit");
    }

    @Test
    void beforeAndAfterSnapshotsPersistAsRedactedJson() {
        UUID marker = UUID.randomUUID();

        audit.record(AuditEvent.builder(AuditEventType.ORGANIZATION_UPDATED)
                .occurredAt(Instant.now())
                .userId(UUID.randomUUID())
                .success(true)
                .detail(Map.of("marker", marker.toString()))
                .before("{\"name\":\"banking\",\"apiToken\":\"sk-secret\"}")
                .after("{\"name\":\"banking\",\"apiToken\":\"sk-rotated\"}")
                .reason("credential rotation")
                .build());

        JsonNode page = getJson("/api/v1/audit?eventType=ORGANIZATION_UPDATED", accessToken(), 200);
        JsonNode event = findEventByMarker(page, marker);

        assertThat(event).as("the event surfaces through the admin API").isNotNull();
        assertThat(event.get("before").get("name").asText()).isEqualTo("banking");
        assertThat(event.get("before").get("apiToken").asText()).isEqualTo(AuditRedaction.REDACTED);
        assertThat(event.get("after").get("apiToken").asText()).isEqualTo(AuditRedaction.REDACTED);
        assertThat(event.get("reason").asText()).isEqualTo("credential rotation");
        assertThat(event.toString()).doesNotContain("sk-secret");
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private void publish(AuditEventType type, UUID actorMarker, UUID tenantId, Instant occurredAt) {
        audit.record(AuditEvent.builder(type)
                .occurredAt(occurredAt)
                .userId(actorMarker)
                .tenantId(tenantId)
                .success(true)
                .detail(Map.of("marker", actorMarker.toString()))
                .build());
    }

    private int countByMarker(UUID marker) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM audit_events WHERE detail ->> 'marker' = ?", Integer.class, marker.toString());
    }

    private int auditCountFor(UUID userId, String eventType) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM audit_events WHERE user_id = ? AND event_type = ?",
                Integer.class,
                userId,
                eventType);
    }

    private JsonNode findEventByMarker(JsonNode page, UUID marker) {
        for (JsonNode event : page.get("items")) {
            if (event.get("detail").hasNonNull("marker")
                    && marker.toString()
                            .equals(event.get("detail").get("marker").asText())) {
                return event;
            }
        }
        return null;
    }

    /** Posts the login flow with a correlation header; returns the status. */
    private int postForStatus(String uri, Map<String, Object> body, String correlationId) {
        return client.post()
                .uri(uri)
                .headers(headers -> headers.set(CorrelationIdFilter.HEADER, correlationId))
                .body(body)
                .exchange((request, response) -> response.getStatusCode().value());
    }

    private String accessToken() {
        return accessTokenFor(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD);
    }

    private String accessTokenFor(String email, String password) {
        String tokens = client.post()
                .uri("/api/v1/auth/login")
                .body(Map.of("email", email, "password", password))
                .retrieve()
                .body(String.class);
        try {
            return mapper.readTree(tokens).get("accessToken").asText();
        } catch (RuntimeException e) {
            throw new AssertionError("login response did not carry an accessToken: " + tokens, e);
        }
    }

    private void registerUser(String email) {
        String invitation = client.post()
                .uri("/api/v1/auth/invitations")
                .headers(headers -> authorize(headers, accessToken()))
                .body(Map.of("email", email))
                .retrieve()
                .body(String.class);
        try {
            String token = mapper.readTree(invitation).get("invitationToken").asText();
            client.post()
                    .uri("/api/v1/auth/register")
                    .body(Map.of(
                            "invitationToken", token,
                            "email", email,
                            "password", MEMBER_PASSWORD,
                            "fullName", "Audit Viewer"))
                    .retrieve()
                    .body(String.class);
        } catch (RuntimeException e) {
            throw new AssertionError("registration flow failed: " + invitation, e);
        }
    }

    private JsonNode getJson(String uri, String bearer, int expectedStatus) {
        return client.get()
                .uri(uri)
                .headers(headers -> authorize(headers, bearer))
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(expectedStatus);
                    return mapper.readValue(response.getBody(), JsonNode.class);
                });
    }

    private static void authorize(HttpHeaders headers, String bearer) {
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
    }
}
