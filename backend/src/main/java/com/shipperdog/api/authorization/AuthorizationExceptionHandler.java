package com.shipperdog.api.authorization;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps permission denials from the method-security enforcement point to
 * an opaque 403 (issue #5, PRD 12): one status, one body, no hint about
 * which permission was missing or whether the target resource exists.
 * Audit-worthy detail (who/what) belongs to the audit trail, not the
 * HTTP response.
 */
@RestControllerAdvice
public class AuthorizationExceptionHandler {

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorResponse> handle(AccessDeniedException exception) {
        return ResponseEntity.status(403).body(new ErrorResponse("forbidden"));
    }

    record ErrorResponse(String error) {}
}
