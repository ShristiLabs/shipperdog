package com.shipperdog.api.version;

import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders version-module failures in the platform's single error
 * envelope (issue #8): RFC 7807 problem+json with a stable public code
 * and the request's trace_id. Internal exception messages are logged,
 * never echoed. Serves the version surface of issue #17 (S4-01);
 * permission denials conceal as {@code APPLICATION_NOT_FOUND} through
 * the authorization module's handler (the permission target of every
 * version route is the application), and a genuine version miss renders
 * {@code VERSION_NOT_FOUND} here.
 */
@Order(0)
@RestControllerAdvice(assignableTypes = VersionController.class)
public class VersionExceptionHandler {

    @ExceptionHandler(VersionException.class)
    ResponseEntity<ProblemDetail> handle(VersionException exception) {
        return ResponseEntity.status(exception.code().httpStatus)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(exception.problem());
    }
}
