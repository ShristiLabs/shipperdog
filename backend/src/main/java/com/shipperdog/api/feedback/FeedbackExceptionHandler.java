package com.shipperdog.api.feedback;

import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders feedback-module failures in the platform's single error
 * envelope (issue #8): RFC 7807 problem+json with a stable public code
 * and the request's trace_id. Internal exception messages are logged,
 * never echoed. Serves the feedback surface of issue #46 (S11-01);
 * permission denials on the console read routes conceal as
 * {@code APPLICATION_NOT_FOUND} through the authorization module's
 * handler (the permission target of every release-addressed read is
 * the application), and the submission route's concealments render
 * this module's own codes.
 */
@Order(0)
@RestControllerAdvice(assignableTypes = FeedbackController.class)
public class FeedbackExceptionHandler {

    @ExceptionHandler(FeedbackException.class)
    ResponseEntity<ProblemDetail> handle(FeedbackException exception) {
        return ResponseEntity.status(exception.code().httpStatus)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(exception.problem());
    }
}
