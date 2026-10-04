package com.shipperdog.api.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a correlation id (issue #6, acceptance criterion 4):
 * accepts a well-formed inbound {@code X-Correlation-Id} or generates one,
 * echoes it on the response, and places it in the MDC so every log line,
 * audit event, and (from S1-07 on) trace of the request shares it. A
 * hostile or malformed inbound value is discarded and regenerated — the
 * id is an internal identifier, not caller-controlled content.
 *
 * <p>Registered at the highest precedence so it wraps the security chains
 * and denials (401/403) are correlated too.
 */
public final class CorrelationIdFilter extends OncePerRequestFilter {

    /** Header accepted from callers and echoed on responses. */
    public static final String HEADER = "X-Correlation-Id";

    /** MDC key under which the current request's id is logged. */
    public static final String MDC_KEY = "correlationId";

    private static final int MIN_LENGTH = 8;
    private static final int MAX_LENGTH = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String correlationId = sanitize(request.getHeader(HEADER));
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * Accepts ids of 8-64 characters over the URL/filename-safe alphabet;
     * anything else is replaced with a fresh UUID. Well-formed values are
     * passed through verbatim (they may come from an upstream gateway).
     */
    static String sanitize(String candidate) {
        if (candidate == null) {
            return UUID.randomUUID().toString();
        }
        String trimmed = candidate.trim();
        boolean acceptable = trimmed.length() >= MIN_LENGTH
                && trimmed.length() <= MAX_LENGTH
                && trimmed.chars().allMatch(CorrelationIdFilter::isSafe);
        return acceptable ? trimmed : UUID.randomUUID().toString();
    }

    private static boolean isSafe(int character) {
        return (character >= '0' && character <= '9')
                || (character >= 'a' && character <= 'z')
                || (character >= 'A' && character <= 'Z')
                || character == '-'
                || character == '_'
                || character == '.';
    }
}
