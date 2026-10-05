package com.shipperdog.api.version;

import com.shipperdog.api.config.ApiProblems;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Version-module domain exception (issue #17, S4-01). The handler in
 * {@code com.shipperdog.api.version.VersionExceptionHandler} renders
 * {@link #problem()} — the RFC 7807 envelope carrying the stable public
 * {@link #code} — so services never touch servlet types (issue #8).
 *
 * <p>{@code APPLICATION_ARCHIVED} is the 409 the application registry
 * reserved in issue #13 for exactly this surface (its error-code
 * registry entry says "version and artifact creation under an archived
 * application, binding with E4/E5"); S4-01 is where it starts being
 * emitted, for versions.
 */
public class VersionException extends RuntimeException {

    public enum Code {
        NOT_FOUND(HttpStatus.NOT_FOUND, "VERSION_NOT_FOUND", "Resource not found."),
        INVALID_REQUEST(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request is invalid."),
        VERSION_NAME_TAKEN(
                HttpStatus.CONFLICT, "VERSION_NAME_TAKEN", "The version name is already in use for the application."),
        APPLICATION_ARCHIVED(
                HttpStatus.CONFLICT,
                "APPLICATION_ARCHIVED",
                "The application is archived; new versions cannot be added.");

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

    public VersionException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    /** The RFC 7807 envelope for this failure (issue #8). */
    public ProblemDetail problem() {
        return ApiProblems.of(code.httpStatus, code.code, code.httpStatus.getReasonPhrase(), code.detail);
    }
}
