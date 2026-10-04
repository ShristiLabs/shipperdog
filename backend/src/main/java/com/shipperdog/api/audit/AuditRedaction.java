package com.shipperdog.api.audit;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Secret scrubbing for audit payloads (issue #6 technical notes: never log
 * secrets or full tokens). Key-based: any detail key or JSON property
 * whose name carries a secret marker is redacted regardless of value —
 * for object properties the entire subtree is replaced, the safe
 * direction (over-redaction) by design. Matching normalizes the key
 * (lower-cased, separators stripped), so {@code apiKey},
 * {@code private_key}, and {@code client-secret} all match their markers.
 * Ordinary keys pass through untouched.
 *
 * <p>Applied by the shared publisher binding to every event's detail map
 * and before/after JSON snapshots, so no caller can bypass it.
 */
public final class AuditRedaction {

    /** Replacement written in place of any secret-bearing value. */
    public static final String REDACTED = "[REDACTED]";

    private static final String[] MARKERS = {
        "password", "secret", "token", "authorization", "credential", "cookie", "apikey", "privatekey"
    };

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AuditRedaction() {}

    /** Whether a key name carries a secret marker. */
    static boolean isSecretKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        for (String marker : MARKERS) {
            if (normalized.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns a copy of the detail map with secret-bearing values replaced.
     * Input order is preserved; a null map yields an empty map.
     */
    public static Map<String, String> redactDetail(Map<String, String> detail) {
        if (detail == null || detail.isEmpty()) {
            return Map.of();
        }
        Map<String, String> redacted = new LinkedHashMap<>(Math.max(8, detail.size()));
        detail.forEach((key, value) -> redacted.put(key, isSecretKey(key) ? REDACTED : value));
        return Map.copyOf(redacted);
    }

    /**
     * Redacts secret-bearing properties anywhere in a JSON snapshot. A
     * snapshot that does not parse as JSON is replaced wholesale with a
     * redaction marker object: audit snapshots are documented as JSON, and
     * keeping unparseable bytes would smuggle content past the scrubber.
     * A null or blank snapshot stays null/blank.
     */
    public static String redactSnapshot(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) {
            return snapshot;
        }
        try {
            JsonNode root = MAPPER.readTree(snapshot);
            redactNode(root);
            return MAPPER.writeValueAsString(root);
        } catch (RuntimeException e) {
            return "{\"redacted\":\"unparseable-snapshot\"}";
        }
    }

    private static void redactNode(JsonNode node) {
        if (node instanceof ObjectNode object) {
            object.properties().forEach(property -> {
                if (isSecretKey(property.getKey())) {
                    object.put(property.getKey(), REDACTED);
                } else {
                    redactNode(property.getValue());
                }
            });
        } else if (node.isArray()) {
            node.forEach(AuditRedaction::redactNode);
        }
    }
}
