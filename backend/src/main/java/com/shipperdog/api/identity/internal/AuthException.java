package com.shipperdog.api.identity.internal;

import com.shipperdog.api.config.ApiProblems;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Identity-module domain exception. The handler in
 * {@code com.shipperdog.api.identity.IdentityExceptionHandler} renders
 * {@link #problem()} — the RFC 7807 envelope carrying the stable public
 * {@link #code} — so services never touch servlet types (issue #8).
 *
 * <p>{@link Code#INVALID_CREDENTIALS} deliberately keeps one uniform detail
 * across every failure cause (unknown address, wrong password, disabled,
 * locked, deactivated organizations): the text must not let a caller split
 * the branches apart (S1-03 uniform-401 rule).
 */
public class AuthException extends RuntimeException {

    public enum Code {
        INVALID_CREDENTIALS(
                HttpStatus.UNAUTHORIZED,
                "INVALID_CREDENTIALS",
                "email or password is incorrect, or the account is locked or disabled"),
        INVALID_REFRESH_TOKEN(
                HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "The refresh token is invalid or expired."),
        INVALID_INVITATION(HttpStatus.BAD_REQUEST, "INVALID_INVITATION", "The invitation is invalid or expired."),
        WEAK_PASSWORD(HttpStatus.BAD_REQUEST, "WEAK_PASSWORD", "The password does not meet the policy."),
        EMAIL_TAKEN(HttpStatus.CONFLICT, "EMAIL_TAKEN", "The email address is already registered."),
        INVALID_RESET_TOKEN(
                HttpStatus.BAD_REQUEST, "INVALID_RESET_TOKEN", "The password reset token is invalid or expired."),
        INVALID_REQUEST(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request is invalid."),
        NOT_FOUND(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found."),
        FORBIDDEN(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied."),
        /**
         * SSO session code failure (S14-01): one uniform detail for unknown,
         * expired, and already-consumed codes -- the code is single-use by
         * design and a replay must not be distinguishable from a guess.
         */
        INVALID_SSO_SESSION_CODE(
                HttpStatus.UNAUTHORIZED,
                "INVALID_SSO_SESSION_CODE",
                "The single sign-on session code is invalid or expired.");

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

    public AuthException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    /** The mapped HTTP status (issue #8: rendered by the identity advice). */
    public HttpStatus httpStatus() {
        return code.httpStatus;
    }

    /** The RFC 7807 envelope for this failure (issue #8). */
    public ProblemDetail problem() {
        return ApiProblems.of(code.httpStatus, code.code, code.httpStatus.getReasonPhrase(), code.detail);
    }
}
