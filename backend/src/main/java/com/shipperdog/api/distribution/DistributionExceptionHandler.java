package com.shipperdog.api.distribution;

import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders distribution-module failures in the platform's single error
 * envelope (issue #8): RFC 7807 problem+json with a stable public code
 * and the request's trace_id. Serves the channel/credential/record
 * surface of issue #61 (S13-01); permission denials conceal as the
 * target resource's own 404 through the authorization module's
 * handler, and this module's concealed misses render identically
 * here.
 */
@Order(0)
@RestControllerAdvice(assignableTypes = DistributionController.class)
public class DistributionExceptionHandler {

    @ExceptionHandler(DistributionException.class)
    ResponseEntity<ProblemDetail> handle(DistributionException exception) {
        return ResponseEntity.status(exception.code().httpStatus)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(exception.problem());
    }
}
