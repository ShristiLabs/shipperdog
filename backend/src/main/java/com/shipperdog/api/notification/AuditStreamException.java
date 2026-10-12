package com.shipperdog.api.notification;

import com.shipperdog.api.config.ApiProblems;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Audit-stream-surface domain exception (issue #71, S14-05). The
 * handler in {@code com.shipperdog.api.notification.AuditStreamExceptionHandler}
 * renders {@link #problem()} -- the RFC 7807 envelope carrying the
 * stable public {@link #code} -- so services never touch servlet
 * types (issue #8). {@code EXPORT_INVALID} is a 422 (semantic failure
 * of a well-formed body), the organization and export misses are
 * concealed 404s (the platform's INV-001 posture: a foreign export is
 * indistinguishable from an unknown id).
 */
public class AuditStreamException extends RuntimeException {

    public enum Code {
        NOT_FOUND(HttpStatus.NOT_FOUND, "AUDIT_STREAM_EXPORT_NOT_FOUND", "Resource not found."),
        /** The organization-miss backstop: through HTTP the permission check conceals first; the service keeps one vocabulary. */
        ORGANIZATION_NOT_FOUND(HttpStatus.NOT_FOUND, "ORGANIZATION_NOT_FOUND", "Resource not found."),
        INVALID_REQUEST(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request is invalid."),
        /**
         * The export body does not validate: an unknown kind or
         * format, an unknown audit event type in the filter, a
         * malformed destination (a non-routable collector URL, a
         * non-gs:// bucket URI), a malformed or oversized secret
         * reference, or a kind/destination shape violation -- one 422
         * keyed by the offending member.
         */
        EXPORT_INVALID(
                HttpStatus.UNPROCESSABLE_ENTITY, "AUDIT_STREAM_EXPORT_INVALID", "The audit stream export is invalid."),
        /**
         * A concurrent edit won the row (the optimistic {@code
         * version} column): the stale writer's refusal, delivered as
         * 409 so the client reloads and retries -- a rotated secret
         * reference or a replaced destination is never silently
         * resurrected.
         */
        CONFLICT(HttpStatus.CONFLICT, "AUDIT_STREAM_EXPORT_CONFLICT", "The export changed; reload and retry.");

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

    /** The plain failure (no field errors): the code's default detail is the problem's {@code detail}. */
    public AuditStreamException(Code code, String message) {
        this(code, message, Map.of());
    }

    /** The field-addressed failure: the {@code errors} member carries one entry per offense. */
    public AuditStreamException(Code code, String message, Map<String, String> errors) {
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
