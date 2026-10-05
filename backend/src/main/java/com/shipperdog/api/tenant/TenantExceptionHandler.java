package com.shipperdog.api.tenant;

import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders tenant-module failures in the platform's single error envelope
 * (issue #8): RFC 7807 problem+json with a stable public code and the
 * request's trace_id. Internal exception messages are logged, never
 * echoed.
 */
@Order(0)
@RestControllerAdvice(assignableTypes = TenantController.class)
public class TenantExceptionHandler {

    @ExceptionHandler(TenantException.class)
    ResponseEntity<ProblemDetail> handle(TenantException exception) {
        return ResponseEntity.status(exception.code().httpStatus)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(exception.problem());
    }
}
