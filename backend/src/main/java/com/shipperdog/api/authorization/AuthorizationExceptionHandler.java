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

    /**
     * A malformed decision-inspection request (S7-01): the generic 400
     * envelope, the same shape every module's validation refusal
     * renders.
     */
    @ExceptionHandler(AuthorizationDecisionController.AuthorizationInspectionException.class)
    ResponseEntity<ProblemDetail> malformedInspection(
            AuthorizationDecisionController.AuthorizationInspectionException exception) {
        return problem(ApiError.INVALID_REQUEST);
    }

    /**
     * Grant-administration failures (S7-02, issue #31): the stable
     * public codes of the grant surface, concealment included — a
     * foreign tester/application/version is indistinguishable from a
     * missing one.
     */
    @ExceptionHandler(AuthorizationGrantException.class)
    ResponseEntity<ProblemDetail> grant(AuthorizationGrantException exception) {
        ProblemDetail problem = exception.problem();
        return ResponseEntity.status(problem.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
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
            case "application" -> "APPLICATION_NOT_FOUND";
            // Since S5-02 the artifact read routes permission-check the
            // version target: a concealed denial on a version must render
            // exactly like the version module's genuine miss, which is
            // VERSION_NOT_FOUND (the generic fallback would leak that the
            // caller failed a permission check rather than addressing a
            // nonexistent version).
            case "version" -> "VERSION_NOT_FOUND";
            // Since S7-01 (issue #30) the layered engine guards the
            // artifact read; an artifact-grain denial must render the
            // artifact module's own miss code (the route's pre-engine
            // contract distinguished the two grains), never a code
            // that would leak which check failed.
            case "artifact" -> "ARTIFACT_NOT_FOUND";
            // Since S6-02 (issue #27) the tester-group routes check the
            // 'tester-group' target: a concealed denial must render
            // exactly like the tester module's genuine group miss
            // (TESTER_GROUP_NOT_FOUND), never a code that would leak
            // which check failed.
            case "tester-group" -> "TESTER_GROUP_NOT_FOUND";
            default -> ApiError.NOT_FOUND.name();
        };
    }
}
