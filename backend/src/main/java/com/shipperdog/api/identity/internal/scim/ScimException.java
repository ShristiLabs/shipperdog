package com.shipperdog.api.identity.internal.scim;

/**
 * The SCIM protocol's error carrier (S14-03, issue #69, RFC 7644
 * section 3.12): rendered by {@code ScimExceptionHandler} as the SCIM
 * error document ({@code application/scim+json}, schemas
 * {@code urn:ietf:params:scim:api:messages:2.0:Error}) — a deliberate,
 * documented deviation from the platform's problem+json envelope,
 * because SCIM clients parse SCIM errors and nothing else. The
 * scimType detail codes follow the RFC's vocabulary (invalidSyntax,
 * invalidFilter, invalidValue, invalidPath, tooMany, uniqueness).
 *
 * <p>Uniform-rejection discipline: an unauthenticated caller learns
 * nothing but 401 — which organization refused, whether a connector
 * is configured, or which leg failed never appears in the answer; the
 * distinct reason lives on the SCIM_REQUEST_REJECTED audit line only.
 */
public class ScimException extends RuntimeException {

    private final int status;

    /** The RFC 7644 scimType detail code, or null for a plain status. */
    private final String scimType;

    public ScimException(int status, String scimType, String detail) {
        super(detail);
        this.status = status;
        this.scimType = scimType;
    }

    /** The RFC's uniform unauthenticated answer: no organization hints. */
    public static ScimException unauthorized() {
        return new ScimException(401, null, "Authentication required: a valid SCIM bearer token is required.");
    }

    /** The RFC's unknown-resource answer. */
    public static ScimException notFound() {
        return new ScimException(404, null, "Resource not found.");
    }

    /** A malformed payload (RFC: 400 invalidSyntax). */
    public static ScimException invalidSyntax(String detail) {
        return new ScimException(400, "invalidSyntax", detail);
    }

    /** An unsupported or malformed filter (RFC: 400 invalidFilter). */
    public static ScimException invalidFilter(String detail) {
        return new ScimException(400, "invalidFilter", detail);
    }

    /** A semantically wrong attribute value (RFC: 400 invalidValue). */
    public static ScimException invalidValue(String detail) {
        return new ScimException(400, "invalidValue", detail);
    }

    /** An unsupported or malformed PATCH path (RFC: 400 invalidPath). */
    public static ScimException invalidPath(String detail) {
        return new ScimException(400, "invalidPath", detail);
    }

    /** A uniqueness conflict (RFC: 409 uniqueness). */
    public static ScimException uniqueness(String detail) {
        return new ScimException(409, "uniqueness", detail);
    }

    public int status() {
        return status;
    }

    public String scimType() {
        return scimType;
    }
}
