package com.shipperdog.api.authorization;

import com.shipperdog.api.config.ApiProblems;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Grant-administration failures (issue #31, S7-02), rendered through
 * the platform's single error envelope: RFC 7807 problem+json with a
 * stable public code and the request's trace id. Concealment follows
 * the module rule — a foreign or unknown tester/application/version
 * is indistinguishable from a missing one (PRD 12), so those codes
 * are the ones the owning modules' genuine misses produce.
 */
public class AuthorizationGrantException extends RuntimeException {

    public enum Code {
        INVALID_REQUEST(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request is invalid."),
        GRANT_NOT_FOUND(HttpStatus.NOT_FOUND, "AUTHORIZATION_GRANT_NOT_FOUND", "Resource not found."),
        TENANT_NOT_FOUND(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "Resource not found."),
        TESTER_NOT_FOUND(HttpStatus.NOT_FOUND, "TESTER_NOT_FOUND", "Resource not found."),
        MEMBERSHIP_NOT_FOUND(
                HttpStatus.NOT_FOUND, "TENANT_TESTER_MEMBERSHIP_NOT_FOUND", "Tenant tester membership not found."),
        APPLICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND", "Resource not found."),
        VERSION_NOT_FOUND(HttpStatus.NOT_FOUND, "VERSION_NOT_FOUND", "Resource not found."),
        TAG_UNKNOWN(
                HttpStatus.BAD_REQUEST,
                "AUTHORIZATION_GRANT_TAG_UNKNOWN",
                "The application has no version carrying this tag."),
        CONFLICT(
                HttpStatus.CONFLICT,
                "AUTHORIZATION_GRANT_CONFLICT",
                "A different grant for this tester, application, and shape already exists."),
        GRANT_REVOKED(
                HttpStatus.CONFLICT,
                "AUTHORIZATION_GRANT_REVOKED",
                "The grant is revoked; grant access again with a new grant."),
        TENANT_DEACTIVATED(
                HttpStatus.CONFLICT, "TENANT_DEACTIVATED", "The tenant is deactivated and takes no new grants.");

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

    public AuthorizationGrantException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public ProblemDetail problem() {
        return ApiProblems.of(code.httpStatus, code.code, code.httpStatus.getReasonPhrase(), code.detail);
    }
}
