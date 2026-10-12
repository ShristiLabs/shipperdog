package com.shipperdog.api.identity;

import com.shipperdog.api.identity.internal.scim.ScimException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Renders the SCIM surface's failures in ITS protocol's error shape
 * (S14-03, RFC 7644 section 3.12): the {@code
 * urn:ietf:params:scim:api:messages:2.0:Error} document over {@code
 * application/scim+json} — the documented, deliberate deviation from
 * the platform's problem+json envelope, because SCIM clients parse
 * SCIM errors and nothing else. Claims the whole {@link
 * ScimController} so even framework faults (unreadable bodies, wrong
 * methods, unknown paths) keep the SCIM shape.
 *
 * <p>The 500 path is leak-proof like the global handler's: the
 * static detail text only, the stack trace to the log.
 */
@Order(0)
@RestControllerAdvice(assignableTypes = {ScimController.class})
public class ScimExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ScimExceptionHandler.class);

    private static final String ERROR_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:Error";

    @ExceptionHandler(ScimException.class)
    ResponseEntity<Map<String, Object>> scim(ScimException exception) {
        return document(exception.status(), exception.scimType(), exception.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException exception) {
        return document(HttpStatus.BAD_REQUEST.value(), "invalidSyntax", "The request body is not valid SCIM JSON.");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Map<String, Object>> noRoute(NoResourceFoundException exception) {
        return document(HttpStatus.NOT_FOUND.value(), null, "Resource not found.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Map<String, Object>> methodNotAllowed(HttpRequestMethodNotSupportedException exception) {
        return document(HttpStatus.METHOD_NOT_ALLOWED.value(), null, "The HTTP method is not supported on this path.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<Map<String, Object>> mediaType(HttpMediaTypeNotSupportedException exception) {
        return document(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(), null, "The request's media type is not supported.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unhandled(Exception exception) {
        log.error("unhandled SCIM exception", exception);
        return document(
                HttpStatus.INTERNAL_SERVER_ERROR.value(), null, "An internal error occurred; the request was refused.");
    }

    private static ResponseEntity<Map<String, Object>> document(int status, String scimType, String detail) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("schemas", List.of(ERROR_SCHEMA));
        body.put("status", Integer.toString(status));
        if (scimType != null) {
            body.put("scimType", scimType);
        }
        body.put("detail", detail);
        return ResponseEntity.status(status)
                .contentType(MediaType.parseMediaType("application/scim+json"))
                .body(body);
    }
}
