package com.shipperdog.api.organization;

import com.shipperdog.api.config.ApiProblems;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Organization-module domain exception. The handler in
 * {@code com.shipperdog.api.organization.OrganizationExceptionHandler}
 * renders {@link #problem()} — the RFC 7807 envelope carrying the stable
 * public {@link #code} — so services never touch servlet types and the
 * wire contract is fixed in one place (issue #8).
 *
 * <p>Codes are stable public identifiers (UPPER_SNAKE, never renamed or
 * reused); {@link #detail} is the safe static text shown to callers, while
 * the exception message stays internal (logged, never echoed).
 */
public class OrganizationException extends RuntimeException {

    public enum Code {
        NOT_FOUND(HttpStatus.NOT_FOUND, "ORGANIZATION_NOT_FOUND", "Resource not found."),
        FORBIDDEN(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied."),
        INVALID_REQUEST(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request is invalid."),
        EXTERNAL_ID_TAKEN(HttpStatus.CONFLICT, "EXTERNAL_ID_TAKEN", "The external id is already in use."),
        MEMBER_EXISTS(HttpStatus.CONFLICT, "MEMBER_EXISTS", "The user is already a member of the organization."),
        ORGANIZATION_DEACTIVATED(HttpStatus.CONFLICT, "ORGANIZATION_DEACTIVATED", "The organization is deactivated."),
        LAST_ORG_ADMIN(HttpStatus.CONFLICT, "LAST_ORG_ADMIN", "The last organization administrator cannot be removed.");

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

    public OrganizationException(Code code, String message) {
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
