package com.shipperdog.api.analytics;

import com.shipperdog.api.config.ApiProblems;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Analytics-module domain exception (issue #49, S11-04). The handler
 * renders {@link #problem()} — the RFC 7807 envelope with the stable
 * public {@link #code} — so services never touch servlet types
 * (issue #8).
 *
 * <p>The surface's misses conceal (PRD 12): an unknown, foreign, or
 * out-of-scope release answers the SAME {@code RELEASE_NOT_FOUND}
 * the release module's genuine miss produces (the shared resource of
 * every release-addressed route), and an ingest naming a release the
 * reporting tenant is not targeted by is byte-identical to that miss
 * — the reporter learns nothing about releases they cannot see.
 * Field rejections ride {@code errors}: the 400 for syntactic
 * problems, the 422 for semantic ones.
 */
public class AnalyticsException extends RuntimeException {

    public enum Code {
        /**
         * The addressed release: unknown, foreign, or not of the
         * addressed application — the concealed miss, byte-identical
         * to the release module's own shape.
         */
        RELEASE_NOT_FOUND(HttpStatus.NOT_FOUND, "RELEASE_NOT_FOUND", "Resource not found."),
        /** The addressed application did not resolve under the caller's scope (concealed miss). */
        APPLICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND", "Resource not found."),
        /**
         * The addressed organization (S11-05's dashboard): unknown —
         * byte-identical to the organization module's genuine miss.
         */
        ORGANIZATION_NOT_FOUND(HttpStatus.NOT_FOUND, "ORGANIZATION_NOT_FOUND", "Resource not found."),
        /**
         * The addressed tenant (S11-05's tenant dashboard): unknown or
         * foreign — byte-identical to the tenant module's genuine miss.
         */
        TENANT_NOT_FOUND(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "Resource not found."),
        /** A syntactically invalid query or ingest body (unknown metric token, malformed window, bad batch). */
        INVALID_REQUEST(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request is invalid."),
        /**
         * A semantically refused query or ingest: a window outside the
         * allowed bounds, an hourly grain over too long a span, or an
         * event whose shape the type refuses.
         */
        ANALYTICS_REQUEST_REFUSED(
                HttpStatus.UNPROCESSABLE_ENTITY, "ANALYTICS_REQUEST_REFUSED", "The analytics request is refused.");

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

    public AnalyticsException(Code code, String message) {
        this(code, message, Map.of());
    }

    /** A rejection carrying a field-to-message report for the {@code errors} member. */
    public AnalyticsException(Code code, String message, Map<String, String> errors) {
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
