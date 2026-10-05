package com.shipperdog.api.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * The fallback tier of the single error envelope (issue #8): everything the
 * module advices do not claim — framework validation, routing faults, and
 * unhandled faults — renders as the same RFC 7807 problem+json with a
 * stable code and the request's trace_id. Ordered last so module-owned
 * exceptions keep reaching their module advices.
 *
 * <p>The 500 path is the leak-proof one: callers get the static
 * INTERNAL_ERROR text and their trace_id; the stack trace goes to the log
 * where the correlation id joins it to the audit trail and trace.
 */
@RestControllerAdvice
@Order
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> invalidBody(MethodArgumentNotValidException exception) {
        Map<String, String> errors = new LinkedHashMap<>();
        exception
                .getBindingResult()
                .getFieldErrors()
                .forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return badRequest(errors);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ProblemDetail> invalidParameters(HandlerMethodValidationException exception) {
        Map<String, String> errors = new LinkedHashMap<>();
        exception
                .getParameterValidationResults()
                .forEach(result -> errors.putIfAbsent(
                        result.getMethodParameter().getParameterName(),
                        result.getResolvableErrors().getFirst().getDefaultMessage()));
        return badRequest(errors);
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MissingServletRequestParameterException.class,
        MethodArgumentTypeMismatchException.class,
        IllegalArgumentException.class
    })
    ResponseEntity<ProblemDetail> unreadable(Exception exception) {
        return badRequest(null);
    }

    @ExceptionHandler(CursorCodec.InvalidCursorException.class)
    ResponseEntity<ProblemDetail> invalidCursor(CursorCodec.InvalidCursorException exception) {
        return problem(ApiError.INVALID_CURSOR);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ProblemDetail> noRoute(NoResourceFoundException exception) {
        return problem(ApiError.NOT_FOUND);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemDetail> methodNotAllowed(HttpRequestMethodNotSupportedException exception) {
        return problem(ApiError.METHOD_NOT_ALLOWED);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProblemDetail> mediaType(HttpMediaTypeNotSupportedException exception) {
        return problem(ApiError.UNSUPPORTED_MEDIA_TYPE);
    }

    /**
     * Method-security denials that reach the dispatcher without a module
     * advice claiming them (module advices and
     * {@code AuthorizationExceptionHandler} are more specific; this keeps
     * the envelope guaranteed for any AccessDeniedException).
     */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> denied(AccessDeniedException exception) {
        return problem(ApiError.FORBIDDEN);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unhandled(Exception exception) {
        log.error("unhandled exception", exception);
        return problem(ApiError.INTERNAL_ERROR);
    }

    private static ResponseEntity<ProblemDetail> badRequest(Map<String, String> errors) {
        ProblemDetail problem = ApiProblems.of(ApiError.INVALID_REQUEST);
        ApiProblems.withErrors(problem, errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private static ResponseEntity<ProblemDetail> problem(ApiError error) {
        return ResponseEntity.status(error.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ApiProblems.of(error));
    }
}
