package com.shipperdog.api.tester;

import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders tester-module failures in the platform's single error
 * envelope (issue #8, issue #26): RFC 7807 problem+json with a stable
 * public code and the request's trace_id. Internal exception messages
 * are logged, never echoed.
 */
@Order(0)
@RestControllerAdvice(assignableTypes = TesterController.class)
public class TesterExceptionHandler {

    @ExceptionHandler(TesterException.class)
    ResponseEntity<ProblemDetail> handle(TesterException exception) {
        return ResponseEntity.status(exception.code().httpStatus)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(exception.problem());
    }
}
