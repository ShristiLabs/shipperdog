package com.shipperdog.api.analytics;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders analytics-module failures in the platform's single error
 * envelope (issue #8): RFC 7807 problem+json with a stable public code
 * and the request's trace_id. Internal exception messages are logged,
 * never echoed. Serves the analytics surface of issue #49 (S11-04)
 * and the dashboards of issue #50 (S11-05); permission denials conceal
 * as the authorization module's own not-found family (the permission
 * target of every route here is the application, the organization, or
 * the tenant). Retryable failures (the ingest's write contention,
 * S15-01) carry the {@code Retry-After} hint their exception holds.
 */
@Order(0)
@RestControllerAdvice(assignableTypes = {AnalyticsController.class, DashboardController.class})
public class AnalyticsExceptionHandler {

    @ExceptionHandler(AnalyticsException.class)
    ResponseEntity<ProblemDetail> handle(AnalyticsException exception) {
        ResponseEntity.BodyBuilder response =
                ResponseEntity.status(exception.code().httpStatus).contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (exception.retryAfterSeconds() > 0) {
            response.header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds()));
        }
        return response.body(exception.problem());
    }
}
