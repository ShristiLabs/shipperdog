package com.shipperdog.api.integration;

import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders integration-surface failures in the platform's single error
 * envelope (issue #8): RFC 7807 problem+json with a stable public code
 * and the request's trace_id. Serves the inbound webhook receiver of
 * issue #53 (S12-03, FR-022): the uniform 401 of every rejected
 * signature, the 400 of a verified sender's unreadable body, and the
 * 413 of an oversized delivery. The internal message never echoes --
 * the caller sees the stable public code only.
 */
@Order(0)
@RestControllerAdvice(assignableTypes = {IntegrationWebhookController.class})
public class IntegrationExceptionHandler {

    @ExceptionHandler(IntegrationException.class)
    ResponseEntity<ProblemDetail> handle(IntegrationException exception) {
        return ResponseEntity.status(exception.code().httpStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(exception.problem());
    }
}
