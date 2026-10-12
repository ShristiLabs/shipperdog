package com.shipperdog.api.notification.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.notification.AuditStreamFormat;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The exported schema's contract tests (issue #71, S14-05, AC4): the
 * envelope is a versioned public API, so its member set and order are
 * pinned here -- a member that moves, renames, or vanishes fails this
 * test before any receiver ever sees it (the S1-08 gate's schema-tier
 * twin; breaking changes ride a new schema version and the docs).
 */
class SiemEventFormatsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static AuditEvent event() {
        return new AuditEvent(
                AuditEventType.ORGANIZATION_UPDATED,
                Instant.parse("2026-10-12T05:00:00Z"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                UUID.fromString("00000000-0000-0000-0000-000000000003"),
                false,
                Map.of("action", "update"),
                "203.0.113.9",
                null,
                null,
                null,
                "{\"name\":{\"before\":\"a\"}}",
                "{\"name\":{\"after\":\"b\"}}",
                "compliance rotation",
                "corr-12345678");
    }

    @Test
    void theEnvelopeMemberSetAndOrderAreFrozen() {
        byte[] body = SiemEventFormats.shipperdogJson(
                UUID.fromString("00000000-0000-0000-0000-00000000000a"), SiemEventFormats.bridgePayload(event()));
        JsonNode envelope = mapper.readTree(body);
        assertThat(envelope.size()).isEqualTo(SiemEventFormats.MEMBERS.length);
        for (String member : SiemEventFormats.MEMBERS) {
            assertThat(envelope.get(member)).as("member %s present", member).isNotNull();
        }
        // The members arrive in the frozen order (the bytes a receiver
        // re-reads for verification are the bytes that were signed).
        String json = new String(body, java.nio.charset.StandardCharsets.UTF_8);
        int cursor = -1;
        boolean ordered = true;
        for (String member : SiemEventFormats.MEMBERS) {
            int at = json.indexOf("\"" + member + "\":");
            if (at <= cursor) {
                ordered = false;
            }
            cursor = at;
        }
        assertThat(ordered).as("the members serialize in the frozen order").isTrue();
    }

    @Test
    void identicalFactsRenderIdenticalBytes() {
        Map<String, Object> facts = SiemEventFormats.bridgePayload(event());
        UUID id = UUID.randomUUID();
        byte[] first = SiemEventFormats.shipperdogJson(id, facts);
        byte[] second = SiemEventFormats.shipperdogJson(id, facts);
        assertThat(second).isEqualTo(first); // the replay determinism the HMAC recipe rides
    }

    @Test
    void theEnvelopeCarriesTheFrdisciplineFacts() {
        JsonNode envelope = mapper.readTree(
                SiemEventFormats.shipperdogJson(UUID.randomUUID(), SiemEventFormats.bridgePayload(event())));
        assertThat(envelope.get("schema_version").asString()).isEqualTo("1.0");
        assertThat(envelope.get("event_type").asString()).isEqualTo("ORGANIZATION_UPDATED");
        assertThat(envelope.get("result").asString()).isEqualTo("FAILURE"); // the success flag's stable rendering
        assertThat(envelope.get("ip_address").asString()).isEqualTo("203.0.113.9");
        assertThat(envelope.get("correlation_id").asString()).isEqualTo("corr-12345678");
        assertThat(envelope.get("detail").get("action").asString()).isEqualTo("update");
        // The snapshots ride as raw JSON strings (the audit query API's
        // own rendering -- receivers parse, the schema documents).
        assertThat(envelope.get("before").isString()).isTrue();
        assertThat(envelope.get("user_id").asString()).isEqualTo("00000000-0000-0000-0000-000000000001");
        assertThat(envelope.get("tenant_id").asString()).isEqualTo("00000000-0000-0000-0000-000000000003");
        assertThat(envelope.get("application_id").isNull()).isTrue();
    }

    @Test
    void splunkHecWrapsTheEnvelopeWithTheStableMembers() {
        byte[] body = SiemEventFormats.bodyOf(
                AuditStreamFormat.SPLUNK_HEC,
                UUID.randomUUID(),
                SiemEventFormats.bridgePayload(event()),
                Instant.parse("2026-10-12T05:00:00Z"));
        JsonNode hec = mapper.readTree(body);
        assertThat(hec.get("sourcetype").asString()).isEqualTo("shipperdog:audit:v1");
        assertThat(hec.get("time").asLong())
                .isEqualTo(Instant.parse("2026-10-12T05:00:00Z").getEpochSecond());
        assertThat(hec.get("host").asString()).isEqualTo("00000000-0000-0000-0000-000000000002");
        assertThat(hec.get("event").get("event_type").asString()).isEqualTo("ORGANIZATION_UPDATED");
    }
}
