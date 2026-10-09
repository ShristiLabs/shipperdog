package com.shipperdog.api.analytics;

import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders analytics-module failures in the platform's single error
 * envelope (issue #8): RFC 7807 problem+json with a stable public code
 * and the request's trace_id. Internal exception messages are logged,
 * never echoed. Serves the analytics surface of issue #49 (S11-04);
 * permission denials conceal as the authorization module's own
 * not-found family (the permission target of every route here is the
 * application or the tenant).
 */
@Order(0)
@RestControllerAdvice(assignableTypes = AnalyticsController.class)
public class AnalyticsExceptionHandler {

    @ExceptionHandler(AnalyticsException.class)
    ResponseEntity<ProblemDetail> handle(AnalyticsException exception) {
        return ResponseEntity.status(exception.code().httpStatus)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(exception.problem());
    }
}
