package com.shipperdog.api.device;

import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders device-module failures in the platform's single error
 * envelope (issue #8): RFC 7807 problem+json with a stable public code
 * and the request's trace_id. Internal exception messages are logged,
 * never echoed. Serves the device surface of issue #48 (S11-03);
 * permission denials on the tenant-addressed routes conceal as the
 * authorization module's own {@code APPLICATION_NOT_FOUND}-family
 * shape (the permission target of every route here is the tenant).
 */
@Order(0)
@RestControllerAdvice(assignableTypes = DeviceController.class)
public class DeviceExceptionHandler {

    @ExceptionHandler(DeviceException.class)
    ResponseEntity<ProblemDetail> handle(DeviceException exception) {
        return ResponseEntity.status(exception.code().httpStatus)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(exception.problem());
    }
}
