package com.shipperdog.api.config;

import com.shipperdog.api.config.idempotency.IdempotencyService;
import com.shipperdog.api.config.idempotency.IdempotencyService.Reservation;
import com.shipperdog.api.identity.AuthenticatedPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import tools.jackson.databind.ObjectMapper;

/**
 * Enforces the {@code Idempotency-Key} contract (issue #8) on handlers
 * marked {@link Idempotent}: reserve the key before the handler runs,
 * replay a stored outcome on retry, and reject concurrent duplicates with
 * 409 while the original is still executing. Runs inside the dispatcher
 * (after security), reading the buffered response the
 * {@link IdempotencyFilter} provides.
 */
public final class IdempotencyInterceptor implements HandlerInterceptor {

    /** Header callers use to request idempotent processing. */
    public static final String HEADER = "Idempotency-Key";

    /** Response header set on replays so callers can tell storage from execution. */
    public static final String REPLAYED_HEADER = "Idempotency-Replayed";

    /** Request attribute marking this request as owning its key. */
    static final String OWNER_ATTRIBUTE = IdempotencyInterceptor.class.getName() + ".owner";

    private static final int MAX_KEY_LENGTH = 255;

    private final IdempotencyService idempotency;

    private final ObjectMapper mapper;

    public IdempotencyInterceptor(IdempotencyService idempotency, ObjectMapper mapper) {
        this.idempotency = idempotency;
        this.mapper = mapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!(handler instanceof HandlerMethod handlerMethod) || !handlerMethod.hasMethodAnnotation(Idempotent.class)) {
            return true;
        }
        String key = request.getHeader(HEADER);
        if (key == null || key.isBlank()) {
            return true;
        }
        if (key.length() > MAX_KEY_LENGTH) {
            writeProblem(response, ApiError.IDEMPOTENCY_KEY_INVALID);
            return false;
        }
        UUID principal = principalUserId();
        if (principal == null) {
            // Unauthenticated: the security chain has already produced (or
            // will produce) the 401; nothing durable to reserve.
            return true;
        }
        Reservation reservation = idempotency.acquire(principal, request.getMethod(), request.getRequestURI(), key);
        if (reservation.created()) {
            request.setAttribute(OWNER_ATTRIBUTE, key);
            return true;
        }
        if (reservation.inProgress()) {
            writeProblem(response, ApiError.REQUEST_IN_PROGRESS);
            return false;
        }
        response.setStatus(reservation.status() == null ? 204 : reservation.status());
        if (reservation.contentType() != null) {
            response.setContentType(reservation.contentType());
        }
        response.setHeader(REPLAYED_HEADER, "true");
        if (reservation.body() != null) {
            response.getOutputStream().write(reservation.body().getBytes(StandardCharsets.UTF_8));
        }
        return false;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request, HttpServletResponse response, Object handler, Exception exception) {
        String key = (String) request.getAttribute(OWNER_ATTRIBUTE);
        if (key == null) {
            return;
        }
        UUID principal = principalUserId();
        if (principal == null) {
            return;
        }
        String contentType = response.getContentType();
        byte[] body = cachedBody(response);
        // On a handler exception the outcome is the error envelope the
        // advices rendered (deterministic, replayable); a still-unhandled
        // fault yields 500 and is not recorded.
        int status = response.getStatus();
        idempotency.complete(principal, request.getMethod(), request.getRequestURI(), key, status, contentType, body);
    }

    /**
     * The buffering wrapper sits below the security chain's own response
     * decorators, so the capture must unwrap down to it rather than test
     * the outermost wrapper.
     */
    private static byte[] cachedBody(HttpServletResponse response) {
        jakarta.servlet.ServletResponse current = response;
        while (current instanceof jakarta.servlet.http.HttpServletResponseWrapper wrapper) {
            if (wrapper.getResponse() instanceof org.springframework.web.util.ContentCachingResponseWrapper cached) {
                return cached.getContentAsByteArray();
            }
            current = wrapper.getResponse();
        }
        return new byte[0];
    }

    private void writeProblem(HttpServletResponse response, ApiError error) throws IOException {
        response.setStatus(error.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getWriter(), ApiProblems.of(error));
    }

    private static UUID principalUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof Jwt jwt) {
            return UUID.fromString(jwt.getSubject());
        }
        if (principal instanceof AuthenticatedPrincipal patPrincipal) {
            return patPrincipal.userId();
        }
        return null;
    }
}
