package com.shipperdog.api.application;

import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders application-module failures in the platform's single error
 * envelope (issue #8): RFC 7807 problem+json with a stable public code
 * and the request's trace_id. Internal exception messages are logged,
 * never echoed. Serves the registry surface (issue #13); the
 * tenant-scoped listing route under {@code /tenants/{id}/applications}
 * keeps rendering in the tenant vocabulary (TENANT_NOT_FOUND) through
 * the tenant module's own handler.
 */
@Order(0)
@RestControllerAdvice(assignableTypes = ApplicationController.class)
public class ApplicationExceptionHandler {

    @ExceptionHandler(ApplicationException.class)
    ResponseEntity<ProblemDetail> handle(ApplicationException exception) {
        return ResponseEntity.status(exception.code().httpStatus)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(exception.problem());
    }
}
