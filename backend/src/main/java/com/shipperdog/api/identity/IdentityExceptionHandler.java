package com.shipperdog.api.identity;

import com.shipperdog.api.identity.internal.AuthException;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders identity-module failures in the platform's single error envelope
 * (issue #8): RFC 7807 problem+json with a stable public code and the
 * request's trace_id. Internal exception messages are logged, never
 * echoed; the uniform-401 rule (identical body for every login-failure
 * cause) is preserved by the shared registry detail.
 */
@Order(0)
@RestControllerAdvice(
        assignableTypes = {AuthController.class, SsoController.class, SamlController.class, SsoConfigController.class})
public class IdentityExceptionHandler {

    @ExceptionHandler(AuthException.class)
    ResponseEntity<ProblemDetail> handle(AuthException exception) {
        return ResponseEntity.status(exception.httpStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(exception.problem());
    }
}
