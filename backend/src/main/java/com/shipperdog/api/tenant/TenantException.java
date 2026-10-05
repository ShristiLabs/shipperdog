package com.shipperdog.api.tenant;

import com.shipperdog.api.config.ApiProblems;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Tenant-module domain exception. The handler in
 * {@code com.shipperdog.api.tenant.TenantExceptionHandler} renders
 * {@link #problem()} — the RFC 7807 envelope carrying the stable public
 * {@link #code} — so services never touch servlet types (issue #8).
 */
public class TenantException extends RuntimeException {

    public enum Code {
        NOT_FOUND(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "Resource not found."),
        INVALID_REQUEST(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request is invalid."),
        MEMBER_EXISTS(HttpStatus.CONFLICT, "TENANT_MEMBER_EXISTS", "The user is already a member of the tenant."),
        NAME_TAKEN(HttpStatus.CONFLICT, "TENANT_NAME_TAKEN", "The tenant name is already in use in the organization."),
        EXTERNAL_ID_TAKEN(HttpStatus.CONFLICT, "TENANT_EXTERNAL_ID_TAKEN", "The external id is already in use."),
        TENANT_DEACTIVATED(HttpStatus.CONFLICT, "TENANT_DEACTIVATED", "The tenant is deactivated.");

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

    public TenantException(Code code, String message) {
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
