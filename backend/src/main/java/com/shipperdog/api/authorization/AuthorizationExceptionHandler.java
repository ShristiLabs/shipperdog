package com.shipperdog.api.authorization;

import com.shipperdog.api.config.ApiError;
import com.shipperdog.api.config.ApiProblems;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders authorization denials in the platform's single error envelope
 * (issue #8) with the PRD 12 opaque-denial rule:
 *
 * <ul>
 *   <li>{@link ConcealedResourceAccessDeniedException} — the caller was
 *       denied on a specific resource and must not learn it exists —
 *       becomes a 404 carrying exactly the same code, title, and detail a
 *       genuine not-found for that resource type produces. The mapping
 *       from guarded type to not-found code lives here, next to the
 *       evaluator that throws it, so the two cannot drift apart.</li>
 *   <li>Every other {@link AccessDeniedException} stays one opaque 403:
 *       FORBIDDEN, no detail about which permission was missing.</li>
 * </ul>
 *
 * <p>Audit-worthy detail (who/what/which permission) belongs to the audit
 * trail — the evaluator records an ACCESS_DENIED event — never to the HTTP
 * response.
 */
@RestControllerAdvice
@Order(0)
public class AuthorizationExceptionHandler {

    @ExceptionHandler(ConcealedResourceAccessDeniedException.class)
    ResponseEntity<ProblemDetail> concealed(ConcealedResourceAccessDeniedException exception) {
        return problem(notFoundCodeFor(exception.targetType()), ApiError.NOT_FOUND);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> handle(AccessDeniedException exception) {
        return problem(ApiError.FORBIDDEN);
    }

    private static ResponseEntity<ProblemDetail> problem(ApiError error) {
        return ResponseEntity.status(error.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ApiProblems.of(error));
    }

    private static ResponseEntity<ProblemDetail> problem(String code, ApiError shape) {
        return ResponseEntity.status(shape.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ApiProblems.of(HttpStatus.NOT_FOUND, code, shape.title(), shape.detail()));
    }

    /**
     * Concealment reuses the module not-found codes so a concealed denial
     * and a genuine miss render identically; unknown target types fall back
     * to the generic cross-cutting NOT_FOUND.
     */
    private static String notFoundCodeFor(String targetType) {
        return switch (targetType == null ? "" : targetType) {
            case "organization" -> "ORGANIZATION_NOT_FOUND";
            case "tenant" -> "TENANT_NOT_FOUND";
            default -> ApiError.NOT_FOUND.name();
        };
    }
}
