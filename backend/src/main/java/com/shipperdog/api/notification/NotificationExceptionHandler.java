package com.shipperdog.api.notification;

import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders notification-surface failures in the platform's single error
 * envelope (issue #8): RFC 7807 problem+json with a stable public code
 * and the request's trace_id. Serves the subscription and delivery-log
 * routes of issue #51 (S12-01); permission denials conceal as the
 * opaque 404 through the authorization module's handler (the
 * permission target of every organization-grain route is the
 * organization), and a genuine subscription miss renders
 * {@code NOTIFICATION_NOT_FOUND} here. The self-scoped inbox route
 * carries no resource id and never fails with a concealable shape.
 */
@Order(0)
@RestControllerAdvice(assignableTypes = {NotificationController.class})
public class NotificationExceptionHandler {

    @ExceptionHandler(NotificationException.class)
    ResponseEntity<ProblemDetail> handle(NotificationException exception) {
        return ResponseEntity.status(exception.code().httpStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(exception.problem());
    }
}
