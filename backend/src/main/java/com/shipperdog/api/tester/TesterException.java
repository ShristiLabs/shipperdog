package com.shipperdog.api.tester;

import com.shipperdog.api.config.ApiProblems;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Tester-module domain exception (issue #26). The handler in
 * {@code com.shipperdog.api.tester.TesterExceptionHandler} renders
 * {@link #problem()} — the RFC 7807 envelope carrying the stable public
 * {@link #code} — so services never touch servlet types (issue #8
 * conventions).
 */
public class TesterException extends RuntimeException {

    public enum Code {
        NOT_FOUND(HttpStatus.NOT_FOUND, "TESTER_NOT_FOUND", "Resource not found."),
        INVALID_REQUEST(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request is invalid."),
        EMAIL_TAKEN(HttpStatus.CONFLICT, "TESTER_EMAIL_TAKEN", "A tester with this email already exists."),
        ALREADY_ACTIVE(HttpStatus.CONFLICT, "TESTER_ALREADY_ACTIVE", "The tester has already accepted onboarding."),
        INVITATION_IN_FLIGHT(
                HttpStatus.CONFLICT,
                "TESTER_INVITATION_IN_FLIGHT",
                "A concurrent invitation for this tester is in flight; retry."),
        INVALID_TRANSITION(HttpStatus.CONFLICT, "TESTER_INVALID_TRANSITION", "The status transition is not allowed."),
        GROUP_NOT_FOUND(HttpStatus.NOT_FOUND, "TESTER_GROUP_NOT_FOUND", "Tester group not found."),
        NAME_TAKEN(
                HttpStatus.CONFLICT, "TESTER_GROUP_NAME_TAKEN", "A group with this name already exists in the tenant."),
        TENANT_DEACTIVATED(
                HttpStatus.CONFLICT,
                "TESTER_GROUP_TENANT_DEACTIVATED",
                "The tenant is deactivated and takes no new tester groups or members."),
        MEMBERSHIP_TENANT_DEACTIVATED(
                HttpStatus.CONFLICT,
                "TENANT_DEACTIVATED",
                "The tenant is deactivated and takes no new tester affiliations."),
        MEMBERSHIP_NOT_FOUND(
                HttpStatus.NOT_FOUND, "TENANT_TESTER_MEMBERSHIP_NOT_FOUND", "Tenant tester membership not found."),
        TESTER_NOT_ACTIVE(HttpStatus.CONFLICT, "TESTER_NOT_ACTIVE", "Only an ACTIVE tester can be linked to a tenant."),
        TESTER_NOT_TENANT_MEMBER(
                HttpStatus.CONFLICT, "TESTER_NOT_TENANT_MEMBER", "The tester is not an active member of the tenant.");

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

    public TesterException(Code code, String message) {
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
