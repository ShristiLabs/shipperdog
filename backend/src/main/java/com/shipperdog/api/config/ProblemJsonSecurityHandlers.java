package com.shipperdog.api.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Renders the security chain's own 401/403 decisions in the platform's
 * single error envelope (issue #8). These fire before the dispatcher, so
 * the @RestControllerAdvice tiers cannot format them; without this
 * component a missing bearer token would be a bare status with no body,
 * breaking the every-error-one-envelope rule. The {@code WWW-Authenticate}
 * challenge header is preserved per RFC 6750.
 *
 * <p>401 bodies deliberately carry no hint about <em>why</em> the caller is
 * unauthenticated (missing, malformed, expired, revoked) — one shape, one
 * code, uniform denials.
 */
@Component
public final class ProblemJsonSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper mapper;

    ProblemJsonSecurityHandlers(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "Bearer");
        write(request, response, ApiError.UNAUTHENTICATED);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        write(request, response, ApiError.FORBIDDEN);
    }

    private void write(HttpServletRequest request, HttpServletResponse response, ApiError error) throws IOException {
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        // trace_id comes from the MDC the correlation filter owns; it wraps
        // the security chains, so it is always present here.
        mapper.writeValue(response.getWriter(), ApiProblems.of(error));
    }
}
