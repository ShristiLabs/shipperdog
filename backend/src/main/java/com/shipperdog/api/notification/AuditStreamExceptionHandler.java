package com.shipperdog.api.notification;

import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders audit-stream-surface failures in the platform's single
 * error envelope (issue #8): RFC 7807 problem+json with a stable
 * public code and the request's trace_id. Serves the export,
 * delivery-log, replay, and batch routes of issue #71 (S14-05);
 * permission denials conceal as the opaque 404 through the
 * authorization module's handler (the permission target of every
 * organization-grain route is the organization), and a genuine export
 * miss renders {@code AUDIT_STREAM_EXPORT_NOT_FOUND} here.
 */
@Order(0)
@RestControllerAdvice(assignableTypes = {AuditStreamController.class})
public class AuditStreamExceptionHandler {

    @ExceptionHandler(AuditStreamException.class)
    ResponseEntity<ProblemDetail> handle(AuditStreamException exception) {
        return ResponseEntity.status(exception.code().httpStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(exception.problem());
    }
}
