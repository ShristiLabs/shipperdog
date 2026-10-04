package com.shipperdog.api.identity.internal;

import org.springframework.http.HttpStatus;

/**
 * Identity-module domain exception. The handler in
 * {@code com.shipperdog.api.identity.IdentityExceptionHandler} maps
 * {@link #httpStatus} and {@link #errorSlug} onto the HTTP response, so
 * services never touch servlet types.
 */
public class AuthException extends RuntimeException {

    public enum Code {
        INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "invalid_credentials"),
        INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "invalid_refresh_token"),
        INVALID_INVITATION(HttpStatus.BAD_REQUEST, "invalid_invitation"),
        WEAK_PASSWORD(HttpStatus.BAD_REQUEST, "weak_password"),
        EMAIL_TAKEN(HttpStatus.CONFLICT, "email_taken"),
        INVALID_RESET_TOKEN(HttpStatus.BAD_REQUEST, "invalid_reset_token"),
        INVALID_REQUEST(HttpStatus.BAD_REQUEST, "invalid_request"),
        NOT_FOUND(HttpStatus.NOT_FOUND, "not_found"),
        FORBIDDEN(HttpStatus.FORBIDDEN, "forbidden");

        final HttpStatus httpStatus;
        final String errorSlug;

        Code(HttpStatus httpStatus, String errorSlug) {
            this.httpStatus = httpStatus;
            this.errorSlug = errorSlug;
        }
    }

    private final Code code;

    public AuthException(Code code, String message) {
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
