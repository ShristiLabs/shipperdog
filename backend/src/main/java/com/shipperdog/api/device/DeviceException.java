package com.shipperdog.api.device;

import com.shipperdog.api.config.ApiProblems;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Device-module domain exception (issue #48, S11-03). The handler in
 * {@code com.shipperdog.api.device.DeviceExceptionHandler} renders
 * {@link #problem()} — the RFC 7807 envelope carrying the stable public
 * {@link #code} — so services never touch servlet types (issue #8).
 *
 * <p>The surface's misses conceal (PRD 12): an unknown, foreign, or
 * invisible device row answers the SAME {@code DEVICE_NOT_FOUND} the
 * genuine miss produces — the tenant seam already filters every read
 * to the caller's bound tenant context, so a foreign tenant's device
 * is indistinguishable from a missing one. Field rejections ride the
 * platform's {@code errors} member: the 400 for syntactic problems
 * (an unknown platform token, a blank identifier), the 422 for
 * semantic ones (a tester not linked to the tenant, an illegal status
 * crossing) — the S9-01 release-targets precedent.
 */
public class DeviceException extends RuntimeException {

    public enum Code {
        /**
         * The addressed device: unknown, foreign (the tenant seam's
         * filtered answer), or invisible to this caller — the genuine
         * miss, byte-identical.
         */
        DEVICE_NOT_FOUND(HttpStatus.NOT_FOUND, "DEVICE_NOT_FOUND", "Resource not found."),
        /**
         * A syntactically invalid registration or update (AC1): an
         * unknown platform token, a blank or oversized identifier,
         * model, or OS version — 400 with one {@code errors} entry per
         * offending field, the whole form fixed in one pass.
         */
        INVALID_REQUEST(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request is invalid."),
        /**
         * The tenant-scoped duplicate of AC1: this tenant already has a
         * device registered under the same identifier (V1's
         * uq_devices_tenant_identifier, surfaced as the platform's
         * conflict envelope).
         */
        DEVICE_IDENTIFIER_TAKEN(HttpStatus.CONFLICT, "DEVICE_IDENTIFIER_TAKEN", "The device identifier is taken."),
        /**
         * A registration or update the addressed facts refuse: a
         * tester with no retained membership row in the tenant, or a
         * status crossing the device's current state does not allow
         * (the membership surface's 422 shape).
         */
        DEVICE_REQUEST_REFUSED(
                HttpStatus.UNPROCESSABLE_ENTITY, "DEVICE_REQUEST_REFUSED", "The device request is refused."),
        /** The tester addressed by a registration did not resolve (concealed miss — never a hint which id failed). */
        TESTER_NOT_FOUND(HttpStatus.NOT_FOUND, "TESTER_NOT_FOUND", "Resource not found.");

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

    public DeviceException(Code code, String message) {
        this(code, message, Map.of());
    }

    /** A rejection carrying a field-to-message report for the {@code errors} member. */
    public DeviceException(Code code, String message, Map<String, String> errors) {
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
