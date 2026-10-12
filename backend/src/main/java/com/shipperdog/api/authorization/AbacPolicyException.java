package com.shipperdog.api.authorization;

import com.shipperdog.api.config.ApiProblems;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * The ABAC policy surface's failures (issue #70, S14-04): the stable
 * public codes of the admin routes, concealment included — a policy
 * of another organization is indistinguishable from a missing one.
 * The {@code errors} member of the 400 carries the field-keyed
 * validation messages the console editor renders inline (the
 * write-time syntax gate AC4 demands).
 */
public final class AbacPolicyException extends RuntimeException {

    /** The stable public codes (the error-code registry's family). */
    public enum Code {
        INVALID_REQUEST(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request is invalid."),
        NOT_FOUND(HttpStatus.NOT_FOUND, "ABAC_POLICY_NOT_FOUND", "Resource not found."),
        NAME_TAKEN(HttpStatus.CONFLICT, "ABAC_POLICY_NAME_TAKEN", "A policy with this name already exists."),
        ORGANIZATION_NOT_FOUND(HttpStatus.NOT_FOUND, "ORGANIZATION_NOT_FOUND", "Resource not found.");

        private final HttpStatus httpStatus;
        private final String code;
        private final String detail;

        Code(HttpStatus httpStatus, String code, String detail) {
            this.httpStatus = httpStatus;
            this.code = code;
            this.detail = detail;
        }
    }

    private final Code code;
    private final Map<String, String> errors;

    public AbacPolicyException(Code code, String message) {
        this(code, message, Map.of());
    }

    public AbacPolicyException(Code code, String message, Map<String, String> errors) {
        super(message);
        this.code = code;
        this.errors = Map.copyOf(errors);
    }

    /** The problem+json body (the platform's single error envelope, with the editor's field errors). */
    public ProblemDetail problem() {
        return ApiProblems.withErrors(
                ApiProblems.of(code.httpStatus, code.code, code.httpStatus.getReasonPhrase(), code.detail), errors);
    }

    public Code code() {
        return code;
    }
}
