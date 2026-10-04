package com.shipperdog.api.identity;

import com.shipperdog.api.identity.internal.AuthException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps identity-module domain exceptions to HTTP responses with stable
 * machine-readable error codes and no internal detail leakage.
 */
@RestControllerAdvice(assignableTypes = AuthController.class)
public class IdentityExceptionHandler {

    @ExceptionHandler(AuthException.class)
    ResponseEntity<ErrorResponse> handle(AuthException exception) {
        return ResponseEntity.status(exception.httpStatus()).body(new ErrorResponse(exception.errorSlug()));
    }

    record ErrorResponse(String error) {}
}
