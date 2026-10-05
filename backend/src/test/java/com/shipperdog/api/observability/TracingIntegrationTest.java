package com.shipperdog.api.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Tracing acceptance for issue #7 against the real HTTP stack: every
 * request produces an OpenTelemetry span (AC1), the correlation id
 * issued by the request filter appears on the span, in the logs, and on
 * the request's audit events with the same value (AC2), and business
 * flows stamp the entity ids they operate on onto the request span
 * (AC1's entity attributes, PRD section 28).
 *
 * <p>Spans are captured by registering a {@link SpanProcessor} bean —
 * the extension point Spring Boot wires into the tracer provider — so
 * the assertions run against the real finished spans the SDK produced,
 * not a test double.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
@Import(TracingIntegrationTest.SpanCapture.class)
@ExtendWith(OutputCaptureExtension.class)
class TracingIntegrationTest {

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
    private ObjectMapper mapper;

    @Autowired
    private SpanCapture spans;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        spans.reset();
    }

    @Test
    void correlationIdJoinsTraceLogsAndAuditForTheSameRequest(CapturedOutput output) {
        String correlationId = "corr-trace-logs-audit-0001";

        int status = client.post()
                .uri("/api/v1/auth/login")
                .headers(headers -> headers.set("X-Correlation-Id", correlationId))
                .body(Map.of("email", DEV_ADMIN_EMAIL, "password", DEV_ADMIN_PASSWORD))
                .exchange((req, res) -> res.getStatusCode().value());

        assertThat(status).isEqualTo(200);

        // Trace: the request's server span carries the same id.
        ReadableSpan span = awaitSpan(correlationId);
        assertThat(span.getKind()).isEqualTo(SpanKind.SERVER);
        assertThat(span.toSpanData().getResource().getAttribute(AttributeKey.stringKey("service.name")))
                .isEqualTo("shipperdog-api");

        // Logs: the request's log lines render the id.
        assertThat(output.toString()).as("log lines carry the correlation id").contains(correlationId);

        // Audit: the request's audit events persisted the same id. (A
        // successful login commits, so its LOGIN_SUCCESS/TOKEN_ISSUED
        // rows are durable; a failed login rolls its transaction back
        // and, by the outbox design, leaves no row.)
        await().atMost(Duration.ofSeconds(5))
                .until(() -> jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_events WHERE correlation_id = ?",
                                Integer.class,
                                correlationId)
                        > 0);
    }

    @Test
    void businessFlowsStampEntityIdsOntoTheRequestSpan() throws Exception {
        String correlationId = "corr-entity-attrs-0001";
        HttpHeaders correlation = new HttpHeaders();
        correlation.set("X-Correlation-Id", correlationId);

        // Creation path: the service stamps organization_id after insert.
        String token = devAdminToken();
        JsonNode created = client.post()
                .uri("/api/v1/organizations")
                .headers(headers -> {
                    headers.setBearerAuth(token);
                    headers.addAll(correlation);
                })
                .body(Map.of("name", "Traced Industries"))
                .exchange((req, res) -> {
                    assertThat(res.getStatusCode().value()).isEqualTo(201);
                    return mapper.readValue(res.getBody(), JsonNode.class);
                });
        UUID organizationId = UUID.fromString(created.get("id").asText());
        awaitEntityAttribute(correlationId, "organization_id", organizationId);

        // Guarded path: the authorization target id lands on the request's
        // trace via the RBAC enforcement point (tenant read).
        spans.reset();
        UUID tenantId = insertTenant(organizationId, "traced");
        String tenantToken = devAdminToken();
        int tenantListStatus = client.get()
                .uri("/api/v1/tenants/" + tenantId + "/members")
                .headers(headers -> {
                    headers.setBearerAuth(tenantToken);
                    headers.set("X-Correlation-Id", correlationId);
                })
                .exchange((req, res) -> res.getStatusCode().value());
        assertThat(tenantListStatus).isEqualTo(200);
        awaitEntityAttribute(correlationId, "tenant_id", tenantId);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private String devAdminToken() throws Exception {
        String tokens = client.post()
                .uri("/api/v1/auth/login")
                .body(Map.of("email", DEV_ADMIN_EMAIL, "password", DEV_ADMIN_PASSWORD))
                .retrieve()
                .body(String.class);
        return mapper.readTree(tokens).get("accessToken").asText();
    }

    private UUID insertTenant(UUID orgId, String name) {
        return jdbc.queryForObject(
                "INSERT INTO tenants (organization_id, name, external_id) VALUES (?, ?, ?) RETURNING id",
                UUID.class,
                orgId,
                name,
                "ext-" + UUID.randomUUID());
    }

    private ReadableSpan awaitSpan(String correlationId) {
        await().atMost(Duration.ofSeconds(5)).until(() -> spanWithCorrelationId(correlationId) != null);
        return spanWithCorrelationId(correlationId);
    }

    /**
     * Entity attributes ride on the request's active child span (Spring
     * Security wraps dispatch in a "secured request" observation), so the
     * assertion is: some span of the request's trace — matched by the
     * correlation id every span of the request carries — holds the
     * entity attribute.
     */
    private void awaitEntityAttribute(String correlationId, String key, UUID entityId) {
        await().atMost(Duration.ofSeconds(5))
                .until(() -> spans.finished().stream()
                        .anyMatch(span ->
                                correlationId.equals(span.getAttribute(AttributeKey.stringKey("correlation_id")))
                                        && entityId.toString().equals(span.getAttribute(AttributeKey.stringKey(key)))));
    }

    private ReadableSpan spanWithCorrelationId(String correlationId) {
        return spans.finished().stream()
                .filter(span -> span.getKind() == SpanKind.SERVER)
                .filter(span -> correlationId.equals(span.getAttribute(AttributeKey.stringKey("correlation_id"))))
                .findFirst()
                .orElse(null);
    }

    /**
     * Collects every finished span of the application's real tracer
     * provider; Spring Boot composes {@link SpanProcessor} beans into the
     * SDK, so no exporter is needed to assert on spans.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class SpanCapture implements SpanProcessor {

        private final List<ReadableSpan> finished = new CopyOnWriteArrayList<>();

        List<ReadableSpan> finished() {
            return finished;
        }

        void reset() {
            finished.clear();
        }

        @Override
        public void onStart(Context parentContext, ReadWriteSpan span) {
            // capture happens at end
        }

        @Override
        public boolean isStartRequired() {
            return false;
        }

        @Override
        public void onEnd(ReadableSpan span) {
            finished.add(span);
        }

        @Override
        public boolean isEndRequired() {
            return true;
        }
    }
}
