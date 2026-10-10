package com.shipperdog.api.integration.internal.service;

import java.net.URI;

/**
 * The S5-04 provenance shape checks every verified-source backfill
 * input must pass (issue #53; shared by the S12-04 Jenkins and GitLab
 * receivers): the same shapes the provenance contract validates at
 * upload, so a mismatch degrades to a no-op rather than poisoning the
 * frozen document. A null answer means "not backfillable from this
 * event" -- never an error the sender would retry.
 */
final class ProvenanceShapes {

    private static final int PIPELINE_MAX = 255;
    private static final int URL_MAX = 2048;

    private ProvenanceShapes() {}

    /** Hex SHA-1/SHA-256, case-insensitive, answered lowercase. */
    static String commitOf(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return value.matches("[0-9a-f]{40}") || value.matches("[0-9a-f]{64}") ? value : null;
    }

    /** An absolute http(s) URL within the provenance ceiling. */
    static String urlOf(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.length() > URL_MAX || value.length() == 0) {
            return null;
        }
        try {
            URI parsed = URI.create(value);
            String scheme = parsed.getScheme();
            if (scheme == null || parsed.getHost() == null) {
                return null;
            }
            if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) {
                return null;
            }
            return value;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A 1-255 printable label, or null (a null pipeline simply does not backfill). */
    static String printable(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty() || value.length() > PIPELINE_MAX || value.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            return null;
        }
        return value;
    }
}
