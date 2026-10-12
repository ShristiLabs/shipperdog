package com.shipperdog.api.notification.internal.service;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventFeed.AuditFeedRow;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The exported event schema (issue #71, S14-05, AC4): the versioned,
 * receiver-facing envelope of docs/siem.md -- a PUBLIC API. The
 * member set and its order are frozen per {@code SCHEMA_VERSION}:
 * members are append-only within a version's lifetime, and any
 * breaking change rides the S1-08 contract gate. Two renderers share
 * this one authority so the HTTPS leg and the bucket leg always emit
 * byte-identical shapes for the same facts:
 *
 * <ul>
 *   <li>{@code SHIPPERDOG_JSON} -- the envelope object itself, fixed
 *       key order, so the bytes a receiver re-reads for HMAC
 *       verification are the bytes that were signed.</li>
 *   <li>{@code SPLUNK_HEC} -- the optional vendor helper: the
 *       envelope rides the HEC JSON's {@code event} member with
 *       {@code time} and a stable {@code sourcetype}.</li>
 * </ul>
 *
 * <p>The bridge ({@code OutboxRecorder}'s audit listener) persists
 * the envelope's FACTS as the outbox payload (every member except
 * {@code event_id}, which is the outbox row's own id and exists only
 * once the row does); the dispatcher assembles the final envelope by
 * placing {@code event_id} at the head. The bucket leg builds the
 * identical envelope from the feed row, with the AUDIT row's id as
 * {@code event_id} -- each destination's stream is idempotent under
 * its own event ids, and cross-destination correlation rides the
 * {@code correlation_id} member (documented in docs/siem.md).
 *
 * <p>Redaction discipline: every fact rendered here was already
 * enriched and scrubbed by the audit publisher binding before
 * persistence (docs/audit.md); the format layer re-renders, never
 * re-widens.
 */
public final class SiemEventFormats {

    /** The envelope's schema version member -- the stability promise's own name. */
    static final String SCHEMA_VERSION = "1.0";

    /** The Splunk HEC envelope's sourcetype: one per schema version, stable for the indexer's extractions. */
    static final String SPLUNK_SOURCETYPE = "shipperdog:audit:v1";

    /** The frozen member names, in the frozen order (the schema itself -- the contract test pins this). */
    static final String[] MEMBERS = {
        "event_id",
        "schema_version",
        "event_type",
        "occurred_at",
        "organization_id",
        "tenant_id",
        "user_id",
        "application_id",
        "version_id",
        "artifact_id",
        "result",
        "reason",
        "ip_address",
        "correlation_id",
        "detail",
        "before",
        "after"
    };

    private static final ObjectMapper CANONICAL = new ObjectMapper();

    private SiemEventFormats() {}

    /** The bridge's outbox payload: the envelope facts without the event id (the row does not exist yet). */
    public static Map<String, Object> bridgePayload(AuditEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema_version", SCHEMA_VERSION);
        payload.put("event_type", event.type().name());
        payload.put("occurred_at", event.occurredAt().toString());
        payload.put("organization_id", event.orgId().toString());
        putRef(payload, "tenant_id", event.tenantId());
        putRef(payload, "user_id", event.userId());
        putRef(payload, "application_id", event.applicationId());
        putRef(payload, "version_id", event.versionId());
        putRef(payload, "artifact_id", event.artifactId());
        payload.put("result", event.success() ? "SUCCESS" : "FAILURE");
        payload.put("reason", event.reason());
        payload.put("ip_address", event.ipAddress());
        payload.put("correlation_id", event.correlationId());
        payload.put("detail", event.detail());
        payload.put("before", event.before());
        payload.put("after", event.after());
        return payload;
    }

    /** The envelope facts of a feed row (the bucket leg's input), the same members the bridge writes. */
    static Map<String, Object> feedFacts(AuditFeedRow row) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema_version", SCHEMA_VERSION);
        payload.put("event_type", row.eventType().name());
        payload.put("occurred_at", row.occurredAt().toString());
        payload.put("organization_id", row.orgId().toString());
        putRef(payload, "tenant_id", row.tenantId());
        putRef(payload, "user_id", row.userId());
        putRef(payload, "application_id", row.applicationId());
        putRef(payload, "version_id", row.versionId());
        putRef(payload, "artifact_id", row.artifactId());
        payload.put("result", row.success() ? "SUCCESS" : "FAILURE");
        payload.put("reason", row.reason());
        payload.put("ip_address", row.ipAddress());
        payload.put("correlation_id", row.correlationId());
        payload.put("detail", parseJson(row.detailJson()));
        payload.put("before", row.beforeJson());
        payload.put("after", row.afterJson());
        return payload;
    }

    /**
     * The final SHIPPERDOG_JSON body: the event id placed at the head
     * of the frozen order, serialized over the canonical mapper --
     * deterministic for identical inputs, so a replayed attempt signs
     * and sends the same bytes.
     */
    static byte[] shipperdogJson(UUID eventId, Map<String, Object> facts) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("event_id", eventId.toString());
        envelope.putAll(facts);
        return CANONICAL.writeValueAsBytes(envelope);
    }

    /** The SPLUNK_HEC body: the HEC JSON envelope around the same audit envelope. */
    static byte[] splunkHec(UUID eventId, Map<String, Object> facts, Instant occurredAt) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("event_id", eventId.toString());
        envelope.putAll(facts);
        Map<String, Object> hec = new LinkedHashMap<>();
        hec.put("time", occurredAt.getEpochSecond());
        hec.put("host", facts.get("organization_id"));
        hec.put("sourcetype", SPLUNK_SOURCETYPE);
        hec.put("event", envelope);
        return CANONICAL.writeValueAsBytes(hec);
    }

    /** The body for the export's format -- the dispatcher's single entry point. */
    static byte[] bodyOf(
            com.shipperdog.api.notification.AuditStreamFormat format,
            UUID eventId,
            Map<String, Object> facts,
            Instant occurredAt) {
        return switch (format) {
            case SHIPPERDOG_JSON -> shipperdogJson(eventId, facts);
            case SPLUNK_HEC -> splunkHec(eventId, facts, occurredAt);
        };
    }

    private static void putRef(Map<String, Object> payload, String member, UUID value) {
        payload.put(member, value == null ? null : value.toString());
    }

    /** A stored detail/before/after JSON string parsed for embedding; unparseable content renders as null, never throws. */
    private static JsonNode parseJson(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        try {
            return CANONICAL.readTree(stored);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
