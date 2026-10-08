package com.shipperdog.api.release;

import com.shipperdog.api.config.ApiProblems;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Ring-surface domain exception (issue #43, S10-04, FR-017). The
 * handler in {@code com.shipperdog.api.release.RingExceptionHandler}
 * renders {@link #problem()} — the RFC 7807 envelope carrying the
 * stable public {@link #code} — so services never touch servlet types
 * (issue #8). {@code RING_TARGETS_INVALID} and the production-ring
 * refusals are 422s: semantic failures of well-formed requests, the
 * field-to-message {@code errors} member carrying every offense so an
 * Organization Admin fixes the whole ring in one pass (the release
 * targets precedent).
 */
public class RingException extends RuntimeException {

    public enum Code {
        NOT_FOUND(HttpStatus.NOT_FOUND, "RING_NOT_FOUND", "Resource not found."),
        /** The organization-miss backstop: through HTTP the permission check conceals first; the service keeps one vocabulary. */
        ORGANIZATION_NOT_FOUND(HttpStatus.NOT_FOUND, "ORGANIZATION_NOT_FOUND", "Resource not found."),
        INVALID_REQUEST(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request is invalid."),
        /**
         * One code per scope (V24's {@code uq_rings_scope_code} is the
         * authority; the pre-check and the lost-race translation answer it
         * identically). An organization ring may carry a SYSTEM code — the
         * shadow that redefines the code's meaning for the organization.
         */
        RING_ALREADY_EXISTS(HttpStatus.CONFLICT, "RING_ALREADY_EXISTS", "A ring with this code already exists."),
        /**
         * The V24 system defaults are organization-invariant reference
         * data: an organization customizes a code by defining its own ring
         * under it (the shadow), never by editing the shared row.
         */
        RING_SYSTEM_IMMUTABLE(
                HttpStatus.CONFLICT,
                "RING_SYSTEM_IMMUTABLE",
                "System rings cannot be edited; define an organization ring with the same code to customize it."),
        /**
         * The ring is referenced by releases, targets, gate definitions,
         * or approval workflows (the database's foreign keys are the
         * authority; this is their translation). Reference data that
         * changes who can receive what is never silently deleted.
         */
        RING_IN_USE(HttpStatus.CONFLICT, "RING_IN_USE", "The ring is referenced and cannot be deleted."),
        /**
         * The eligibility rules do not validate: unknown keys, a
         * non-array axis, an empty axis, or an id that is not one of the
         * organization's tenants or tenant-groups — one 422 keyed by the
         * offending member.
         */
        RING_ELIGIBILITY_INVALID(
                HttpStatus.UNPROCESSABLE_ENTITY, "RING_ELIGIBILITY_INVALID", "The ring eligibility rules are invalid."),
        /**
         * The write would break the progressive order (the story's
         * Production-last rule): a non-production ring at or past the
         * production ring's position, or a production ring no longer
         * after every other ring.
         */
        RING_POSITION_INVALID(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "RING_POSITION_INVALID",
                "The ring position breaks the progression; Production stays last."),
        /**
         * The production ring must carry an approval-policy reference
         * (the story's third acceptance criterion): editing or creating
         * the terminal ring without one refuses — the widest audience is
         * governed by default, never open by omission.
         */
        RING_PRODUCTION_POLICY_REQUIRED(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "RING_PRODUCTION_POLICY_REQUIRED",
                "The production ring must reference an approval policy."),
        /**
         * The production ring must reference a gate set: at least one
         * required gate definition attached to it or standing
         * organization-wide (S10-03's catalog). Production ships through
         * gates, never past them.
         */
        RING_PRODUCTION_GATES_REQUIRED(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "RING_PRODUCTION_GATES_REQUIRED",
                "The production ring must reference at least one required gate definition.");

        private final HttpStatus httpStatus;
        private final String publicCode;
        private final String defaultDetail;

        Code(HttpStatus httpStatus, String publicCode, String defaultDetail) {
            this.httpStatus = httpStatus;
            this.publicCode = publicCode;
            this.defaultDetail = defaultDetail;
        }

        public HttpStatus httpStatus() {
            return httpStatus;
        }

        public String publicCode() {
            return publicCode;
        }
    }

    private final Code code;
    private final Map<String, String> errors;

    /**
     * The plain failure (no field errors): the code's default detail is
     * the problem's {@code detail}; internal messages stay in logs.
     */
    public RingException(Code code, String message) {
        this(code, message, Map.of());
    }

    /** The field-addressed failure: the {@code errors} member carries one entry per offense. */
    public RingException(Code code, String message, Map<String, String> errors) {
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
                ApiProblems.of(code.httpStatus, code.publicCode, code.httpStatus.getReasonPhrase(), code.defaultDetail);
        return ApiProblems.withErrors(problem, errors);
    }
}
