package com.shipperdog.api.gate;

import com.shipperdog.api.config.ApiProblems;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Gate-module domain exception (issue #42, S10-03). The handler in
 * {@code com.shipperdog.api.gate.GateExceptionHandler} renders
 * {@link #problem()} — the RFC 7807 envelope carrying the stable
 * public {@link #code} — so services never touch servlet types (the
 * platform convention since issue #8).
 *
 * <p>{@code RELEASE_NOT_FOUND} is this surface's own value of the
 * release read's public code (the {@code APPLICATION_NOT_FOUND}
 * pattern: one public code, the surface that emits it owns its value)
 * so the concealed miss is byte-identical through either module.
 */
public class GateException extends RuntimeException {

    public enum Code {
        NOT_FOUND(HttpStatus.NOT_FOUND, "GATE_DEFINITION_NOT_FOUND", "Resource not found."),
        /**
         * The release miss of the gate surfaces: unknown, out of scope, or of
         * another application than the addressed one — this module's own
         * value of the release read's public code, byte-identical concealment.
         */
        RELEASE_NOT_FOUND(HttpStatus.NOT_FOUND, "RELEASE_NOT_FOUND", "Resource not found."),
        /** The organization miss of the definition surfaces (the environments standing). */
        ORGANIZATION_NOT_FOUND(HttpStatus.NOT_FOUND, "ORGANIZATION_NOT_FOUND", "Resource not found."),
        INVALID_REQUEST(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request is invalid."),
        /**
         * One definition per organization and gate type at each ring
         * attachment level (V31's guards; the pre-check and the
         * lost-race translation answer it identically).
         */
        GATE_DEFINITION_EXISTS(
                HttpStatus.CONFLICT, "GATE_DEFINITION_EXISTS", "A definition already exists for this gate type."),
        /**
         * The submission is semantically invalid (422 family, the
         * {@code errors} member keyed by field): a numeric gate
         * without its observed value, a pass/fail gate with one, a
         * malformed outcome, or a malformed evidence URL.
         */
        GATE_RESULT_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "GATE_RESULT_INVALID", "The gate result is invalid."),
        /**
         * A metric-backed gate type refuses manual submission
         * (AC4's model): the value resolves from analytics at
         * evaluation time through the {@link GateMetricSource} seam —
         * fail-closed until S11-04's feeds land, never
         * human-assertable.
         */
        GATE_METRIC_BACKED(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "GATE_METRIC_BACKED",
                "Metric-backed gates resolve their values from analytics at evaluation time."),
        /**
         * The override path's precise conflicts (AC3): only a FAILED
         * or NO_DATA gate can be overridden, exactly once — a passed
         * gate needs no override, a pending one has nothing to
         * override, and a used override is a recorded fact that never
         * moves.
         */
        GATE_OVERRIDE_INVALID(HttpStatus.CONFLICT, "GATE_OVERRIDE_INVALID", "This gate cannot be overridden.");

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

    public GateException(Code code, String message) {
        this(code, message, Map.of());
    }

    /** A rejection carrying a field-to-message report for the {@code errors} member (the 422 family). */
    public GateException(Code code, String message, Map<String, String> errors) {
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
