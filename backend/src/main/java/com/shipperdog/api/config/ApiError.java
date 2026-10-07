package com.shipperdog.api.config;

import org.springframework.http.HttpStatus;

/**
 * Registry of the cross-cutting stable public error codes (issue #8): every
 * error response carries one {@code code} in its problem+json envelope, and
 * these identifiers are public API contract — never rename or reuse one.
 * Module-owned codes (identity, organization, tenant) live in their module's
 * domain exception enum; the full public registry is documented in
 * {@code docs/error-codes.md}, which must be updated whenever a code is
 * added.
 *
 * <p>Codes are UPPER_SNAKE_CASE per the payload enum-casing convention
 * (issue #8 technical notes); HTTP status and the default {@code title} and
 * {@code detail} text live here so every occurrence of a code renders
 * identically.
 */
public enum ApiError {

    /** A request body or parameter failed validation (detail carries the constraints). */
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Bad Request", "The request is invalid."),

    /** The Idempotency-Key header is malformed. */
    IDEMPOTENCY_KEY_INVALID(HttpStatus.BAD_REQUEST, "Bad Request", "The Idempotency-Key header is malformed."),

    /** The pagination cursor could not be decoded. */
    INVALID_CURSOR(HttpStatus.BAD_REQUEST, "Bad Request", "The cursor parameter is invalid."),

    /** The request path does not exist. Also the concealed-access shape (see {@code authorization}). */
    NOT_FOUND(HttpStatus.NOT_FOUND, "Not Found", "Resource not found."),

    /** No or unusable credentials on an endpoint that requires authentication. */
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Unauthorized", "Authentication is required."),

    /** The presented bearer token is invalid, expired, or revoked. */
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "Unauthorized", "The access token is invalid, expired, or revoked."),

    /** An authenticated caller lacks a permission that is not tied to a specific resource. */
    FORBIDDEN(HttpStatus.FORBIDDEN, "Forbidden", "Access denied."),

    /** A concurrent request already holds this Idempotency-Key. */
    REQUEST_IN_PROGRESS(HttpStatus.CONFLICT, "Conflict", "A request with this Idempotency-Key is in progress."),

    /** The HTTP method is not supported on the path. */
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Method Not Allowed", "The HTTP method is not allowed."),

    /** The request body's media type is not supported. */
    UNSUPPORTED_MEDIA_TYPE(
            HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported Media Type", "The media type is not supported."),

    /**
     * The in-app rate limiter refused the request (issue #39, S8-05). The 429 always carries a
     * {@code Retry-After} header with the seconds until the next token.
     */
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", "Too many requests."),

    /** Unhandled server fault; detail never leaks internals (the correlation id is the lookup key). */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "An unexpected error occurred.");

    private final HttpStatus status;
    private final String title;
    private final String detail;

    ApiError(HttpStatus status, String title, String detail) {
        this.status = status;
        this.title = title;
        this.detail = detail;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    public String detail() {
        return detail;
    }
}
