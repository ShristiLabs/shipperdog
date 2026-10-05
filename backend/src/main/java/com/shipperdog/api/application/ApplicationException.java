package com.shipperdog.api.application;

import com.shipperdog.api.config.ApiProblems;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Application-module domain exception (issue #13). The handler in
 * {@code com.shipperdog.api.application.ApplicationExceptionHandler}
 * renders {@link #problem()} — the RFC 7807 envelope carrying the stable
 * public {@link #code} — so services never touch servlet types
 * (issue #8).
 */
public class ApplicationException extends RuntimeException {

    public enum Code {
        NOT_FOUND(HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND", "Resource not found."),
        INVALID_REQUEST(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request is invalid."),
        TENANT_DEACTIVATED(HttpStatus.CONFLICT, "APPLICATION_TENANT_DEACTIVATED", "The owning tenant is deactivated."),
        NAME_TAKEN(
                HttpStatus.CONFLICT,
                "APPLICATION_NAME_TAKEN",
                "The application name is already in use in the organization."),
        BUNDLE_TAKEN(
                HttpStatus.CONFLICT,
                "APPLICATION_BUNDLE_TAKEN",
                "The bundle id is already in use in the organization."),
        PACKAGE_TAKEN(
                HttpStatus.CONFLICT,
                "APPLICATION_PACKAGE_TAKEN",
                "The package name is already in use in the organization."),
        AUTHORIZED_TENANTS_PRESENT(
                HttpStatus.CONFLICT,
                "APPLICATION_AUTHORIZED_TENANTS_PRESENT",
                "The application has authorized tenants; de-authorize them before converting ownership.");

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

    public ApplicationException(Code code, String message) {
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
