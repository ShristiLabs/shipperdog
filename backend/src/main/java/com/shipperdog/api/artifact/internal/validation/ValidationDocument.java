package com.shipperdog.api.artifact.internal.validation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/**
 * The validation document (issue #24, S5-05): the per-stage record
 * FR-006 demands — every stage's outcome, in order, with its reason —
 * persisted as the {@code artifacts.validation} jsonb (V18) and
 * rendered typed by the artifact read. Unlike the extraction document
 * (write-once over an immutable outcome), this one is REWRITTEN by
 * each attempt: the row carries the current state, the audit trail
 * carries the history, and the {@code attempts} counter carries the
 * retry count (the story's idempotent, re-runnable stages).
 *
 * <p>Outcome vocabulary: {@code PASSED} (all stages passed; the
 * artifact moved READY), {@code FAILED} (a stage failed
 * deterministically; the artifact moved FAILED), {@code BLOCKED}
 * (infrastructure refused — queue full, storage unreadable after
 * retries, or extraction still pending — the artifact STAYS
 * VALIDATING and this document is what makes the stall visible).
 */
public final class ValidationDocument {

    /** Documents are plain maps of scalars; one mapper serves them all. */
    private static final ObjectMapper DOCUMENTS = new ObjectMapper();

    private final List<Map<String, Object>> stages = new ArrayList<>();

    private int attempts = 1;

    private Instant startedAt;

    private Instant finishedAt;

    private String outcome;

    private String format;

    /** The infra code of a BLOCKED attempt (queue full, storage unreadable); null otherwise. */
    private ValidationFailureCode code;

    /** The human-readable reason of a BLOCKED attempt; null otherwise. */
    private String reason;

    /** The first FAILED stage of this attempt, for the audit summary; null when none failed. */
    private StageFailure firstFailure;

    /** Opens an attempt at the given moment. */
    public static ValidationDocument begin(Instant startedAt) {
        ValidationDocument document = new ValidationDocument();
        document.startedAt = startedAt;
        return document;
    }

    /**
     * Continues from a previous attempt: the attempt counter carries
     * over (a revalidation is attempt N+1), the previous stages do
     * not — the document always renders the LATEST attempt, the audit
     * trail keeps every one.
     */
    public static ValidationDocument continueFrom(String previousDocument, Instant startedAt) {
        ValidationDocument document = begin(startedAt);
        document.attempts = previousAttempts(previousDocument) + 1;
        return document;
    }

    private static int previousAttempts(String previousDocument) {
        if (previousDocument == null) {
            return 0;
        }
        try {
            var node = DOCUMENTS.readTree(previousDocument).get("attempts");
            return node == null || node.isNull() ? 0 : Math.max(0, node.asInt());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Records the structural format verdict this attempt observed (the channel-format fact, FR-006's note). */
    public void observedFormat(String verdict) {
        this.format = verdict;
    }

    /** One stage's terminal outcome; duration rounds up so a sub-millisecond stage is never 0-hidden. */
    public void stage(ValidationStage stage, StageStatus status, ValidationFailureCode code, String reason) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", stage.name());
        entry.put("status", status.name());
        if (code != null) {
            entry.put("code", code.name());
        }
        if (reason != null) {
            entry.put("reason", reason);
        }
        stages.add(entry);
        if (status == StageStatus.FAILED && firstFailure == null) {
            firstFailure = new StageFailure(stage, code);
        }
    }

    /** Closes the attempt as PASSED (artifact READY). */
    public void passed(Instant finishedAt) {
        this.outcome = "PASSED";
        this.finishedAt = finishedAt;
    }

    /** Closes the attempt as FAILED (artifact FAILED; a stage already carries the code). */
    public void failed(Instant finishedAt) {
        this.outcome = "FAILED";
        this.finishedAt = finishedAt;
    }

    /**
     * Closes the attempt as BLOCKED (artifact stays VALIDATING;
     * infrastructure refused or the extraction tier has not settled),
     * carrying the infra code and reason that make the stall visible.
     */
    public void blocked(Instant finishedAt, ValidationFailureCode code, String reason) {
        this.outcome = "BLOCKED";
        this.finishedAt = finishedAt;
        this.code = code;
        this.reason = reason;
    }

    /** The attempts counter of the latest attempt this document renders. */
    public int attempts() {
        return attempts;
    }

    /** The attempt duration in milliseconds; 0 while open. */
    public long durationMs() {
        if (startedAt == null || finishedAt == null) {
            return 0;
        }
        return Math.max(0, finishedAt.toEpochMilli() - startedAt.toEpochMilli());
    }

    /** The infra code of a BLOCKED attempt, or null. */
    public ValidationFailureCode code() {
        return code;
    }

    /** The first FAILED stage of the attempt, or null when none failed. */
    public StageFailure firstFailure() {
        return firstFailure;
    }

    /** The jsonb payload of the {@code artifacts.validation} column. */
    public String toJson() {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("outcome", outcome);
        document.put("attempts", attempts);
        document.put("startedAt", startedAt == null ? null : startedAt.toString());
        document.put("finishedAt", finishedAt == null ? null : finishedAt.toString());
        document.put("durationMs", durationMs());
        if (format != null) {
            document.put("format", format);
        }
        if (code != null) {
            document.put("code", code.name());
        }
        if (reason != null) {
            document.put("reason", reason);
        }
        document.put("stages", stages);
        return DOCUMENTS.writeValueAsString(document);
    }

    /** The failed-stage summary the audit trail and the views share. */
    public record StageFailure(ValidationStage stage, ValidationFailureCode code) {}

    /** One stage's status: PASSED, FAILED (with a code), or SKIPPED (a prior stage already failed). */
    public enum StageStatus {
        PASSED,
        FAILED,
        SKIPPED
    }
}
