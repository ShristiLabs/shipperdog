package com.shipperdog.api.environment;

import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders environment-module failures in the platform's single error
 * envelope (issue #8): RFC 7807 problem+json with a stable public code
 * and the request's trace_id. Internal exception messages are logged,
 * never echoed. Serves the environment surface of issue #58 (S9-04);
 * permission denials conceal as {@code ORGANIZATION_NOT_FOUND} (the
 * collection routes' permission target) or {@code ENVIRONMENT_NOT_FOUND}
 * (the item routes' target, through the authorization module's handler),
 * and a genuine environment miss renders {@code ENVIRONMENT_NOT_FOUND}
 * here.
 */
@Order(0)
@RestControllerAdvice(assignableTypes = EnvironmentController.class)
public class EnvironmentExceptionHandler {

    @ExceptionHandler(EnvironmentException.class)
    ResponseEntity<ProblemDetail> handle(EnvironmentException exception) {
        return ResponseEntity.status(exception.code().httpStatus)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(exception.problem());
    }
}
