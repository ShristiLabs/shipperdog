package com.shipperdog.api.environment;

import com.shipperdog.api.config.ApiProblems;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Environment-module domain exception (issue #58, S9-04). The handler in
 * {@code com.shipperdog.api.environment.EnvironmentExceptionHandler} renders
 * {@link #problem()} — the RFC 7807 envelope carrying the stable public
 * {@link #code} — so services never touch servlet types (issue #8).
 *
 * <p>{@code ENVIRONMENT_TENANTS_INVALID} is the release surface's
 * {@code RELEASE_TARGETS_INVALID} shape applied to assignment writes: one
 * 422 carrying every offending tenant, keyed by tenant id in the
 * {@code errors} member — and the message is identical for an unknown and
 * a foreign-organization tenant so the refusal never confirms existence
 * (the opaque-denial discipline of the target validation it mirrors).
 * {@code TENANT_NOT_FOUND} is this module's own value of the tenant
 * module's public code (the {@code APPLICATION_ARCHIVED} pattern: one
 * public code, the surface that emits it owns its value).
 */
public class EnvironmentException extends RuntimeException {

    public enum Code {
        NOT_FOUND(HttpStatus.NOT_FOUND, "ENVIRONMENT_NOT_FOUND", "Resource not found."),
        /**
         * The organization-miss backstop of creation and the listing:
         * through HTTP the permission check conceals an unknown or
         * foreign organization as the organization module's public code
         * before the service runs, so the backstop keeps that
         * vocabulary.
         */
        ORGANIZATION_NOT_FOUND(HttpStatus.NOT_FOUND, "ORGANIZATION_NOT_FOUND", "Resource not found."),
        INVALID_REQUEST(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request is invalid."),
        /**
         * The tenant miss of the assignment write's resolution path:
         * unknown, foreign, or of another organization (concealed — the
         * tenant module's own public code).
         */
        TENANT_NOT_FOUND(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "Resource not found."),
        /**
         * One code per organization (V27's
         * {@code uq_environments_org_code} with V1's
         * {@code uq_environments_org_name} beside it is the authority;
         * the pre-check and the lost-race translation answer it
         * identically).
         */
        ENVIRONMENT_ALREADY_EXISTS(
                HttpStatus.CONFLICT,
                "ENVIRONMENT_ALREADY_EXISTS",
                "An environment already exists for this organization."),
        /**
         * The environment is DISABLED (V1's lifecycle park): a parked
         * environment accepts no mutations and may not be named by a new
         * release — reactivation is the one crossing out.
         */
        ENVIRONMENT_DISABLED(HttpStatus.CONFLICT, "ENVIRONMENT_DISABLED", "The environment is disabled."),
        /**
         * A status change onto the state the environment already holds
         * (including every crossing out of ACTIVE/DISABLED's two-value
         * vocabulary that is not the other value): a conflict with
         * stored state, answered 409 with the {@code errors} member
         * carrying {@code current} and {@code attempted} (the
         * {@code RELEASE_TRANSITION_INVALID} shape).
         */
        ENVIRONMENT_STATUS_INVALID(
                HttpStatus.CONFLICT,
                "ENVIRONMENT_STATUS_INVALID",
                "The environment cannot change to the requested status."),
        /**
         * The requested configuration snapshot version does not exist
         * for the environment — an honest state-dependent miss, never a
         * concealment (the environment is readable; the version is
         * absent).
         */
        ENVIRONMENT_CONFIG_VERSION_NOT_FOUND(
                HttpStatus.NOT_FOUND,
                "ENVIRONMENT_CONFIG_VERSION_NOT_FOUND",
                "No configuration snapshot exists at that version."),
        /**
         * A tenant of the assignment set is not a member of the
         * environment's organization (or does not exist — the messages
         * are identical): one 422 carrying every offending tenant,
         * keyed by tenant id.
         */
        ENVIRONMENT_TENANTS_INVALID(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "ENVIRONMENT_TENANTS_INVALID",
                "One or more tenants are not members of the environment's organization.");

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

    private final Map<String, String> errors;

    public EnvironmentException(Code code, String message) {
        this(code, message, Map.of());
    }

    /** A rejection carrying a field-to-message report for the {@code errors} member (the 422 family). */
    public EnvironmentException(Code code, String message, Map<String, String> errors) {
        super(message);
        this.code = code;
        this.errors = Map.copyOf(errors);
    }

    public Code code() {
        return code;
    }

    /** The RFC 7807 envelope for this failure (issue #8), with the {@code errors} member when present. */
    public ProblemDetail problem() {
        ProblemDetail problem =
                ApiProblems.of(code.httpStatus, code.code, code.httpStatus.getReasonPhrase(), code.detail);
        return ApiProblems.withErrors(problem, errors);
    }
}
