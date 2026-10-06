package com.shipperdog.api.artifact.internal.provenance;

import com.shipperdog.api.artifact.ArtifactException;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Build provenance of one artifact (issue #23, S5-04, FR-005, PRD 8.5):
 * the client-asserted claims of WHERE and HOW the binary was built —
 * git commit, git branch, pipeline name, CI run URL, and build
 * timestamp — captured at upload time on every upload surface (the
 * multipart form, the upload-session body; finalize replays what the
 * session captured). The five names are FROZEN: they are the M2
 * CI-integration contract the S12-03/S12-04 integrations will send, so
 * the wire names (snake_case form fields {@code git_commit},
 * {@code git_branch}, {@code pipeline}, {@code ci_run_url},
 * {@code build_timestamp}; camelCase JSON members) and the jsonb
 * document keys never rename.
 *
 * <p>Trust model (deliberate, documented in docs/artifacts.md): these
 * values are CLAIMS by the uploading client, validated for SHAPE only.
 * Nothing in M1 attests them — the authenticated uploader is the
 * accountability anchor ({@code uploadedBy} plus the audit trail), and
 * cryptographic provenance/attestation verification is future work
 * beyond S5-04. The corollary is the story's second acceptance
 * criterion: nothing is ever fabricated server-side. An absent field
 * stays absent (null in the document, null in the view); the server
 * never guesses a branch, a pipeline, or a build time, and a blank
 * input is treated as absent — never coerced into a value.
 *
 * <p>Validation (the story's fourth criterion, rejection with field
 * errors): every present field must carry its documented shape — the
 * commit a hex SHA-1 (40) or SHA-256 (64), case-insensitive and stored
 * lowercase; the branch and pipeline 1-255 printable characters; the
 * run URL an absolute http/https URL of at most 2048 characters; the
 * build timestamp an ISO-8601 instant with offset, canonicalized to
 * UTC. {@link #of(Fields)} collects EVERY field's error into one
 * rejection so a client fixes the whole request in one round trip.
 * Field-error keys are the frozen snake_case wire names.
 */
public record Provenance(String gitCommit, String gitBranch, String pipeline, String ciRunUrl, String buildTimestamp) {

    /** The shared document codec; provenance documents are tiny. */
    private static final ObjectMapper DOCUMENTS = new ObjectMapper();

    /** The rendered-URL ceiling, matching the session_url column width headroom. */
    private static final int URL_MAX = 2048;

    /** The branch/pipeline length ceiling. */
    private static final int NAME_MAX = 255;

    /**
     * The raw, unvalidated tuple as each wire flavor delivers it: the
     * multipart form binds the snake_case names, the JSON bodies bind
     * the camelCase members, and both funnel into this carrier so the
     * service signatures stay independent of the transport.
     */
    public record Fields(String gitCommit, String gitBranch, String pipeline, String ciRunUrl, String buildTimestamp) {}

    /**
     * Validates the raw tuple and answers the provenance it carries:
     * all-absent (null or blank in every slot) answers {@code null} —
     * manual uploads may omit provenance entirely (the story's second
     * criterion); any present-but-malformed field answers the single
     * 400 with every field's error attached.
     */
    public static Provenance of(Fields fields) {
        Map<String, String> errors = new LinkedHashMap<>();
        String gitCommit = commit(fields.gitCommit(), errors);
        String gitBranch = printable("git_branch", fields.gitBranch(), errors);
        String pipeline = printable("pipeline", fields.pipeline(), errors);
        String ciRunUrl = url(fields.ciRunUrl(), errors);
        String buildTimestamp = timestamp(fields.buildTimestamp(), errors);
        if (!errors.isEmpty()) {
            throw new ArtifactException(
                    ArtifactException.Code.INVALID_REQUEST,
                    "provenance is invalid: " + String.join(", ", errors.keySet()),
                    errors);
        }
        if (gitCommit == null && gitBranch == null && pipeline == null && ciRunUrl == null && buildTimestamp == null) {
            return null;
        }
        return new Provenance(gitCommit, gitBranch, pipeline, ciRunUrl, buildTimestamp);
    }

    /**
     * Reads the stored jsonb document back (the view path): tolerant of
     * unknown keys — the frozen five are read, anything else is a
     * future field the reader has not learned yet — and of absent
     * members. A null or unreadable document answers null; stored
     * documents were validated at write time, so unreadable means
     * "never written", never "silently wrong".
     */
    public static Provenance from(String document) {
        if (document == null || document.isBlank()) {
            return null;
        }
        try {
            JsonNode tree = DOCUMENTS.readTree(document);
            return new Provenance(
                    text(tree, "gitCommit"),
                    text(tree, "gitBranch"),
                    text(tree, "pipeline"),
                    text(tree, "ciRunUrl"),
                    text(tree, "buildTimestamp"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The stored jsonb document: ONLY the supplied members — absent
     * fields are absent keys, the written form of "never fabricated".
     */
    public String toJson() {
        Map<String, Object> document = new LinkedHashMap<>();
        if (gitCommit != null) {
            document.put("gitCommit", gitCommit);
        }
        if (gitBranch != null) {
            document.put("gitBranch", gitBranch);
        }
        if (pipeline != null) {
            document.put("pipeline", pipeline);
        }
        if (ciRunUrl != null) {
            document.put("ciRunUrl", ciRunUrl);
        }
        if (buildTimestamp != null) {
            document.put("buildTimestamp", buildTimestamp);
        }
        try {
            return DOCUMENTS.writeValueAsString(document);
        } catch (RuntimeException e) {
            throw new IllegalStateException("cannot render the provenance document", e);
        }
    }

    // -----------------------------------------------------------------------
    // Field validators: blank means absent; present means shaped
    // -----------------------------------------------------------------------

    /** Hex SHA-1 or SHA-256, case-insensitive on input, stored lowercase. */
    private static String commit(String raw, Map<String, String> errors) {
        String value = absentOrTrimmed(raw);
        if (value == null) {
            return null;
        }
        if (!value.matches("[0-9a-fA-F]{40}") && !value.matches("[0-9a-fA-F]{64}")) {
            errors.put("git_commit", "git_commit must be a 40-character SHA-1 or 64-character SHA-256 hex commit");
            return null;
        }
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    /** A 1-255 character printable label (branch, pipeline). */
    private static String printable(String field, String raw, Map<String, String> errors) {
        String value = absentOrTrimmed(raw);
        if (value == null) {
            return null;
        }
        if (value.length() > NAME_MAX || value.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            errors.put(field, field + " must be 1 to 255 printable characters");
            return null;
        }
        return value;
    }

    /** An absolute http/https URL with a host, at most 2048 characters. */
    private static String url(String raw, Map<String, String> errors) {
        String value = absentOrTrimmed(raw);
        if (value == null) {
            return null;
        }
        String rejection = "ci_run_url must be an absolute http or https URL";
        // Controls are rejected EXPLICITLY before the URI parse:
        // java.net.URI accepts embedded NULs, and a \u0000 inside a
        // stored jsonb string is a database-level refusal — a hostile
        // URL must answer the 400 here, never a 500 at insert.
        if (value.length() > URL_MAX || value.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            errors.put("ci_run_url", rejection + " of at most 2048 printable characters");
            return null;
        }
        URI parsed;
        try {
            parsed = URI.create(value);
        } catch (RuntimeException e) {
            errors.put("ci_run_url", rejection);
            return null;
        }
        String scheme = parsed.getScheme();
        if (scheme == null
                || parsed.getHost() == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            errors.put("ci_run_url", rejection);
            return null;
        }
        return value;
    }

    /** An ISO-8601 instant with offset, canonicalized to UTC. */
    private static String timestamp(String raw, Map<String, String> errors) {
        String value = absentOrTrimmed(raw);
        if (value == null) {
            return null;
        }
        try {
            Instant instant = value.endsWith("Z") || value.endsWith("z")
                    ? Instant.parse(value)
                    : OffsetDateTime.parse(value).toInstant();
            return instant.toString();
        } catch (RuntimeException e) {
            errors.put(
                    "build_timestamp",
                    "build_timestamp must be an ISO-8601 instant with offset (e.g. 2026-10-04T12:30:00Z)");
            return null;
        }
    }

    /** Blank (null, empty, whitespace-only) means absent — never fabricated. */
    private static String absentOrTrimmed(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String text(JsonNode tree, String field) {
        JsonNode value = tree.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
