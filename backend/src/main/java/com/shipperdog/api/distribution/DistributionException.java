package com.shipperdog.api.distribution;

import com.shipperdog.api.config.ApiProblems;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Distribution-module domain exception (issue #61, S13-01). The
 * handler in {@code com.shipperdog.api.distribution.DistributionExceptionHandler}
 * renders {@link #problem()} -- the RFC 7807 envelope carrying the
 * stable public {@link #code} -- so services never touch servlet
 * types (issue #8). The adapter-taxonomy codes ({@code ADAPTER_*})
 * map {@link AdapterException} categories per the taxonomy (AC5):
 * they ride distribution records as failure codes, and render as
 * problem codes should a dispatch surface ever expose them over HTTP
 * (the dispatch itself is event-driven -- a store failure fails the
 * record, never the release's ACTIVE crossing).
 */
public class DistributionException extends RuntimeException {

    public enum Code {
        INVALID_REQUEST(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request is invalid."),
        /** The permission target's concealed miss (the organization the binding surface addresses). */
        ORGANIZATION_NOT_FOUND(HttpStatus.NOT_FOUND, "ORGANIZATION_NOT_FOUND", "Resource not found."),
        /** The app-level binding's application miss: unknown, foreign, or of another organization (concealed). */
        APPLICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND", "Resource not found."),
        /** The environment-level binding's environment miss: unknown, foreign, or of another organization (concealed). */
        ENVIRONMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "ENVIRONMENT_NOT_FOUND", "Resource not found."),
        /** The addressed credential binding's miss (concealed across organizations). */
        DISTRIBUTION_CREDENTIAL_NOT_FOUND(
                HttpStatus.NOT_FOUND, "DISTRIBUTION_CREDENTIAL_NOT_FOUND", "Resource not found."),
        /** The addressed distribution record's miss (concealed across organizations). */
        DISTRIBUTION_RECORD_NOT_FOUND(HttpStatus.NOT_FOUND, "DISTRIBUTION_RECORD_NOT_FOUND", "Resource not found."),
        /**
         * The environment-level binding names an environment the
         * application does not target in the chain's shape (an
         * environment binding must name its application; PRD 25's
         * levels are org, app, app+env).
         */
        DISTRIBUTION_CREDENTIAL_LEVEL_INVALID(
                HttpStatus.BAD_REQUEST, "DISTRIBUTION_CREDENTIAL_LEVEL_INVALID", "The credential level is invalid."),
        /**
         * The adapter failure taxonomy's terminal answer for
         * {@code TRANSIENT}/{@code PERMANENT} categories (AC5): the
         * per-adapter retries are exhausted or the fault was final.
         * Rides the distribution record's {@code failure_code}; a
         * problem code for any future dispatch surface.
         */
        ADAPTER_FAILED(HttpStatus.BAD_GATEWAY, "ADAPTER_FAILED", "The distribution channel failed."),
        /**
         * The taxonomy's terminal answer for {@code RATE_LIMITED}:
         * the provider throttled every attempt.
         */
        ADAPTER_RATE_LIMITED(
                HttpStatus.SERVICE_UNAVAILABLE, "ADAPTER_RATE_LIMITED", "The distribution channel is rate limited."),
        /**
         * The taxonomy's terminal answer for {@code TIMEOUT}: the
         * per-adapter timeout cut every attempt.
         */
        ADAPTER_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "ADAPTER_TIMEOUT", "The distribution channel timed out."),
        /**
         * The taxonomy's answer for {@code AUTHENTICATION}: never
         * retried (retrying a bad credential is a lockout generator);
         * the detail never carries material.
         */
        ADAPTER_AUTHENTICATION_FAILED(
                HttpStatus.BAD_GATEWAY, "ADAPTER_AUTHENTICATION_FAILED", "The channel credentials were rejected."),
        /** The dispatch reached an unregistered channel reference (a configuration fault, recorded loudly). */
        CHANNEL_UNKNOWN(HttpStatus.BAD_REQUEST, "CHANNEL_UNKNOWN", "The distribution channel is not registered."),
        /**
         * The group-mapping write's shape or target fault (S13-02):
         * an unknown application, tester group, or bundle id; a
         * provider group that does not exist and was not asked to be
         * created. The message names the fault's fact, never a
         * foreign row (concealment).
         */
        DISTRIBUTION_GROUP_LINK_INVALID(
                HttpStatus.BAD_REQUEST, "DISTRIBUTION_GROUP_LINK_INVALID", "The group link request is invalid."),
        /** The addressed group link's miss (concealed across organizations, S13-02). */
        DISTRIBUTION_GROUP_LINK_NOT_FOUND(
                HttpStatus.NOT_FOUND, "DISTRIBUTION_GROUP_LINK_NOT_FOUND", "Resource not found."),
        FORBIDDEN(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied.");

        final HttpStatus httpStatus;
        final String code;
        final String detail;

        Code(HttpStatus httpStatus, String code, String detail) {
            this.httpStatus = httpStatus;
            this.code = code;
            this.detail = detail;
        }

        /** The stable public code string (cross-package readers: the record's failure codes, the taxonomy mapping). */
        public String codeValue() {
            return code;
        }

        /** The HTTP status the problem envelope renders. */
        public HttpStatus status() {
            return httpStatus;
        }
    }

    private final Code code;

    public DistributionException(Code code, String message) {
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
