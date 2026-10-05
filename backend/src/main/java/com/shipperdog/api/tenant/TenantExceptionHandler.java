package com.shipperdog.api.tenant;

import com.shipperdog.api.application.TenantApplicationsController;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders tenant-module failures in the platform's single error envelope
 * (issue #8): RFC 7807 problem+json with a stable public code and the
 * request's trace_id. Internal exception messages are logged, never
 * echoed. Also serves the application module's tenant-scoped listing
 * (issue #11): its route addresses a tenant, so its misses render in
 * the tenant vocabulary (TENANT_NOT_FOUND) instead of a second,
 * divergent envelope for the same resource.
 */
@Order(0)
@RestControllerAdvice(assignableTypes = {TenantController.class, TenantApplicationsController.class})
public class TenantExceptionHandler {

    @ExceptionHandler(TenantException.class)
    ResponseEntity<ProblemDetail> handle(TenantException exception) {
        return ResponseEntity.status(exception.code().httpStatus)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(exception.problem());
    }
}
