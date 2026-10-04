package com.shipperdog.api.tenant;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps tenant-module domain exceptions to HTTP responses with stable
 * machine-readable error codes and no internal detail leakage.
 */
@RestControllerAdvice(assignableTypes = TenantController.class)
public class TenantExceptionHandler {

    @ExceptionHandler(TenantException.class)
    ResponseEntity<ErrorResponse> handle(TenantException exception) {
        return ResponseEntity.status(exception.httpStatus()).body(new ErrorResponse(exception.errorSlug()));
    }

    record ErrorResponse(String error) {}
}
