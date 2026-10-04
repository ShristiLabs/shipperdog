package com.shipperdog.api.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The event record of the audit framework (issue #6): the S1-03
 * seven-argument constructor stays source-compatible with every existing
 * caller (the FR-024 extension fields arrive as null and are enriched by
 * the binding), the builder populates the full shape, and the record
 * stays immutable and defensive.
 */
class AuditEventTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-10-04T12:00:00Z");
    private static final UUID USER_ID = UUID.randomUUID();

    @Test
    void legacyConstructorLeavesExtensionFieldsNull() {
        AuditEvent event =
                new AuditEvent(AuditEventType.LOGIN_SUCCESS, OCCURRED_AT, USER_ID, null, null, true, Map.of());

        assertThat(event.type()).isEqualTo(AuditEventType.LOGIN_SUCCESS);
        assertThat(event.occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(event.userId()).isEqualTo(USER_ID);
        assertThat(event.detail()).isEmpty();
        assertThat(event.ipAddress())
                .as("FR-024 extension fields are attached by the publisher binding")
                .isNull();
        assertThat(event.applicationId()).isNull();
        assertThat(event.versionId()).isNull();
        assertThat(event.artifactId()).isNull();
        assertThat(event.before()).isNull();
        assertThat(event.after()).isNull();
        assertThat(event.reason()).isNull();
        assertThat(event.correlationId()).isNull();
    }

    @Test
    void legacyConstructorAcceptsNullDetail() {
        AuditEvent event = new AuditEvent(AuditEventType.LOGIN_FAILURE, OCCURRED_AT, USER_ID, null, null, false, null);

        assertThat(event.detail()).isEmpty();
    }

    @Test
    void builderPopulatesEveryFrd024Field() {
        UUID org = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        UUID application = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        UUID artifact = UUID.randomUUID();

        AuditEvent event = AuditEvent.builder(AuditEventType.ORGANIZATION_UPDATED)
                .occurredAt(OCCURRED_AT)
                .userId(USER_ID)
                .orgId(org)
                .tenantId(tenant)
                .applicationId(application)
                .versionId(version)
                .artifactId(artifact)
                .before("{\"status\":\"READY\"}")
                .after("{\"status\":\"ACTIVE\"}")
                .reason("gates passed")
                .ipAddress("203.0.113.7")
                .correlationId("corr-12345678")
                .success(true)
                .detail(Map.of("release", version.toString()))
                .build();

        assertThat(event.type()).isEqualTo(AuditEventType.ORGANIZATION_UPDATED);
        assertThat(event.occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(event.userId()).isEqualTo(USER_ID);
        assertThat(event.orgId()).isEqualTo(org);
        assertThat(event.tenantId()).isEqualTo(tenant);
        assertThat(event.applicationId()).isEqualTo(application);
        assertThat(event.versionId()).isEqualTo(version);
        assertThat(event.artifactId()).isEqualTo(artifact);
        assertThat(event.before()).isEqualTo("{\"status\":\"READY\"}");
        assertThat(event.after()).isEqualTo("{\"status\":\"ACTIVE\"}");
        assertThat(event.reason()).isEqualTo("gates passed");
        assertThat(event.ipAddress()).isEqualTo("203.0.113.7");
        assertThat(event.correlationId()).isEqualTo("corr-12345678");
        assertThat(event.success()).isTrue();
        assertThat(event.detail()).containsEntry("release", version.toString());
    }

    @Test
    void typeIsRequired() {
        assertThatThrownBy(() -> AuditEvent.builder(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AuditEvent(null, OCCURRED_AT, USER_ID, null, null, true, Map.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void detailMapIsDefensivelyCopied() {
        Map<String, String> source = new HashMap<>();
        source.put("userId", USER_ID.toString());
        AuditEvent event = new AuditEvent(AuditEventType.TOKEN_ISSUED, OCCURRED_AT, USER_ID, null, null, true, source);

        source.put("userId", "tampered");

        assertThat(event.detail()).containsEntry("userId", USER_ID.toString());
        assertThatThrownBy(() -> event.detail().put("userId", "tampered"))
                .as("the detail map is immutable")
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
