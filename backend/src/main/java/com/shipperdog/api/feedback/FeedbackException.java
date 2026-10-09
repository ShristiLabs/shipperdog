package com.shipperdog.api.feedback;

import com.shipperdog.api.config.ApiProblems;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Feedback-module domain exception (issue #46, S11-01). The handler in
 * {@code com.shipperdog.api.feedback.FeedbackExceptionHandler} renders
 * {@link #problem()} — the RFC 7807 envelope carrying the stable public
 * {@link #code} — so services never touch servlet types (issue #8).
 *
 * <p>The submission surface's misses conceal (PRD 12): an unknown
 * application/version/release, a release that does not serve the named
 * tenant, and a caller with no tester record all answer the SAME
 * {@code FEEDBACK_RELEASE_NOT_FOUND} the genuine miss produces — the
 * client learns nothing about which link failed. The artifact-miss
 * shape reuses the artifact read's public code through this module's
 * own value of it (the {@code APPLICATION_ARCHIVED} pattern: one
 * public code, the surface that emits it owns its value).
 *
 * <p>Field rejections ride the platform's {@code errors} member: the
 * 400 for syntactic problems (an empty description, an unknown type
 * token) and the 422 for semantic ones (a rejected attachment), the
 * S9-01 release-targets precedent.
 */
public class FeedbackException extends RuntimeException {

    public enum Code {
        /**
         * The submission's release link failed (AC2's concealment):
         * unknown application, version, or release; a release that
         * exists but does not target the named tenant; or a caller
         * with no linked tester record — all byte-identical.
         */
        FEEDBACK_RELEASE_NOT_FOUND(HttpStatus.NOT_FOUND, "FEEDBACK_RELEASE_NOT_FOUND", "Resource not found."),
        /** The addressed feedback row: unknown, foreign, or invisible to this caller (the security-issue filter conceals here too). */
        FEEDBACK_NOT_FOUND(HttpStatus.NOT_FOUND, "FEEDBACK_NOT_FOUND", "Resource not found."),
        /** The artifact-miss of submission: unknown, or not a READY build of the release's version (this surface's own value of the artifact read's code). */
        ARTIFACT_NOT_FOUND(HttpStatus.NOT_FOUND, "ARTIFACT_NOT_FOUND", "Resource not found."),
        /**
         * The device-miss of submission (S11-03, AC4): an unknown
         * device, a device belonging to another tester or tenant, or a
         * deactivated device — all byte-identical (this surface's own
         * value of the device module's code, the artifact-miss
         * pattern: the client learns nothing about which link failed).
         */
        DEVICE_NOT_FOUND(HttpStatus.NOT_FOUND, "DEVICE_NOT_FOUND", "Resource not found."),
        /**
         * A syntactically invalid submission (AC1): an empty or
         * blank-trimmed description, an unknown type token, oversized
         * free-text fields — 400 with one {@code errors} entry per
         * offending field, the whole form fixed in one pass.
         */
        INVALID_REQUEST(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request is invalid."),
        /**
         * An attachment the limits refuse (AC3): a disallowed file
         * extension, an over-size upload, too many files, or a
         * duplicate file name within one submission — 422 keyed by
         * file name in the {@code errors} member.
         */
        ATTACHMENT_REJECTED(
                HttpStatus.UNPROCESSABLE_ENTITY, "ATTACHMENT_REJECTED", "One or more attachments are rejected."),
        /**
         * A triage act the report's current state refuses (S11-02,
         * AC1): an illegal status edge, a severity change on a closed
         * report, or a closing crossing without its mandatory note —
         * 422 naming the report's legal edges in the {@code errors}
         * member, the release-transition refusal's shape.
         */
        FEEDBACK_TRANSITION_INVALID(
                HttpStatus.UNPROCESSABLE_ENTITY, "FEEDBACK_TRANSITION_INVALID", "The triage action is refused."),
        /**
         * An assignee who cannot work this report (S11-02): not a
         * member of the owning organization — the assignment surface
         * resolves candidates there and no other.
         */
        ASSIGNEE_NOT_ELIGIBLE(
                HttpStatus.UNPROCESSABLE_ENTITY, "ASSIGNEE_NOT_ELIGIBLE", "The assignee is not eligible."),
        /** The object store refused the write (the artifact module's STORAGE_UNAVAILABLE shape): never a silent 201. */
        STORAGE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", "Object storage is unavailable.");

        final HttpStatus httpStatus;
        final String code;
        final String detail;

        Code(HttpStatus httpStatus, String code, String detail) {
            this.httpStatus = httpStatus;
            this.code = code;
            this.detail = detail;
        }
    }

    private final Code code;

    private final Map<String, String> errors;

    public FeedbackException(Code code, String message) {
        this(code, message, Map.of());
    }

    /** A rejection carrying a field-to-message report for the {@code errors} member. */
    public FeedbackException(Code code, String message, Map<String, String> errors) {
        super(message);
        this.code = code;
        this.errors = Map.copyOf(errors);
    }

    public Code code() {
        return code;
    }

    /** The RFC 7807 envelope for this failure (issue #8), with the {@code errors} member when present. */
    public ProblemDetail problem() {
        ProblemDetail problem =
                ApiProblems.of(code.httpStatus, code.code, code.httpStatus.getReasonPhrase(), code.detail);
        return ApiProblems.withErrors(problem, errors);
    }
}
