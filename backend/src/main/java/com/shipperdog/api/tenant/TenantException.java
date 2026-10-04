package com.shipperdog.api.tenant;

import org.springframework.http.HttpStatus;

/**
 * Tenant-module domain exception. The handler in
 * {@code com.shipperdog.api.tenant.TenantExceptionHandler} maps
 * {@link #httpStatus} and {@link #errorSlug} onto the HTTP response, so
 * services never touch servlet types.
 */
public class TenantException extends RuntimeException {

    public enum Code {
        NOT_FOUND(HttpStatus.NOT_FOUND, "not_found"),
        INVALID_REQUEST(HttpStatus.BAD_REQUEST, "invalid_request"),
        MEMBER_EXISTS(HttpStatus.CONFLICT, "member_exists");

        final HttpStatus httpStatus;
        final String errorSlug;

        Code(HttpStatus httpStatus, String errorSlug) {
            this.httpStatus = httpStatus;
            this.errorSlug = errorSlug;
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

    public HttpStatus httpStatus() {
        return code.httpStatus;
    }

    public String errorSlug() {
        return code.errorSlug;
    }
}
