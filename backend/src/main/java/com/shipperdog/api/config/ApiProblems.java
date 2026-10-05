package com.shipperdog.api.config;

import java.net.URI;
import org.slf4j.MDC;
import org.springframework.http.ProblemDetail;

/**
 * Builds the single error envelope of the platform (issue #8): RFC 7807
 * {@code application/problem+json} with the stable public {@code code} and
 * the request's {@code trace_id} as extension members. Every error path —
 * module exception handlers, the global handler, and the security chain's
 * 401/403 responders — renders through this factory so an error's shape is
 * a construction guarantee, not a convention to remember.
 *
 * <p>Field contract (acceptance criterion 1):
 * <ul>
 *   <li>{@code type} — {@code https://api.shipperdog.dev/problems/<code>},
 *       stable per code, resolvable by future docs hosting.</li>
 *   <li>{@code title} / {@code status} — the HTTP status family label.</li>
 *   <li>{@code code} — the stable public identifier from the registry
 *       ({@link ApiError} or a module code), never localized, never
 *       renamed.</li>
 *   <li>{@code detail} — safe-for-callers static text from the registry;
 *       internal exception messages are logged, never echoed.</li>
 *   <li>{@code trace_id} — the request correlation id (S1-07 tri-join
 *       key across trace, logs, and audit).</li>
 * </ul>
 */
public final class ApiProblems {

    /** Base of the stable {@code type} URIs; one path element per code. */
    public static final String TYPE_BASE = "https://api.shipperdog.dev/problems/";

    private ApiProblems() {}

    /** Envelope for a cross-cutting registry code. */
    public static ProblemDetail of(ApiError error) {
        return of(error.status(), error.name(), error.title(), error.detail());
    }

    /**
     * Envelope for a module-owned code. Detail must be registry text that is
     * safe for every caller of that code; module handlers own their codes'
     * wording.
     */
    public static ProblemDetail of(
            org.springframework.http.HttpStatus status, String code, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle(title);
        problem.setType(
                URI.create(TYPE_BASE + code.toLowerCase(java.util.Locale.ROOT).replace('_', '-')));
        problem.setDetail(detail);
        problem.setProperty("code", code);
        problem.setProperty("trace_id", MDC.get(CorrelationIdFilter.MDC_KEY));
        return problem;
    }

    /** Adds a field-level validation report ({@code errors} member). */
    public static ProblemDetail withErrors(ProblemDetail problem, java.util.Map<String, String> errors) {
        if (errors != null && !errors.isEmpty()) {
            problem.setProperty("errors", errors);
        }
        return problem;
    }
}
