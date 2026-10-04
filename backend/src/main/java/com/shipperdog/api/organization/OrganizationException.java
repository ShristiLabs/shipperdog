package com.shipperdog.api.organization;

import org.springframework.http.HttpStatus;

/**
 * Organization-module domain exception. The handler in
 * {@code com.shipperdog.api.organization.OrganizationExceptionHandler}
 * maps {@link #httpStatus} and {@link #errorSlug} onto the HTTP response,
 * so services never touch servlet types.
 */
public class OrganizationException extends RuntimeException {

    public enum Code {
        NOT_FOUND(HttpStatus.NOT_FOUND, "not_found"),
        FORBIDDEN(HttpStatus.FORBIDDEN, "forbidden"),
        INVALID_REQUEST(HttpStatus.BAD_REQUEST, "invalid_request"),
        EXTERNAL_ID_TAKEN(HttpStatus.CONFLICT, "external_id_taken"),
        MEMBER_EXISTS(HttpStatus.CONFLICT, "member_exists"),
        ORGANIZATION_DEACTIVATED(HttpStatus.CONFLICT, "organization_deactivated"),
        LAST_ORG_ADMIN(HttpStatus.CONFLICT, "last_org_admin");

        final HttpStatus httpStatus;
        final String errorSlug;

        Code(HttpStatus httpStatus, String errorSlug) {
            this.httpStatus = httpStatus;
            this.errorSlug = errorSlug;
        }
    }

    private final Code code;

    public OrganizationException(Code code, String message) {
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
