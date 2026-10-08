package com.shipperdog.api.gate;

import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders gate-module failures in the platform's single error envelope
 * (issue #8): RFC 7807 problem+json with a stable public code and the
 * request's trace_id. Internal exception messages are logged, never
 * echoed. Serves the gate surfaces of issue #42 (S10-03): the
 * organization-grain definition routes (permission target the
 * organization; a denial conceals as {@code ORGANIZATION_NOT_FOUND}
 * through the authorization module's handler) and the release-addressed
 * result, read, and override routes (permission target the
 * application; a denial conceals as {@code APPLICATION_NOT_FOUND}
 * there, a genuine release miss renders {@code RELEASE_NOT_FOUND}
 * here — this module's own value of the release read's public code).
 */
@Order(0)
@RestControllerAdvice(assignableTypes = GateController.class)
public class GateExceptionHandler {

    @ExceptionHandler(GateException.class)
    ResponseEntity<ProblemDetail> handle(GateException exception) {
        return ResponseEntity.status(exception.code().httpStatus)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(exception.problem());
    }
}
