package com.shipperdog.api.organization;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps organization-module domain exceptions to HTTP responses with stable
 * machine-readable error codes and no internal detail leakage.
 */
@RestControllerAdvice(assignableTypes = OrganizationController.class)
public class OrganizationExceptionHandler {

    @ExceptionHandler(OrganizationException.class)
    ResponseEntity<ErrorResponse> handle(OrganizationException exception) {
        return ResponseEntity.status(exception.httpStatus()).body(new ErrorResponse(exception.errorSlug()));
    }

    record ErrorResponse(String error) {}
}
