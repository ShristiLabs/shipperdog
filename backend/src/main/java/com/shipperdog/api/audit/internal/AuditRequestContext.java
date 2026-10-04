package com.shipperdog.api.audit.internal;

import com.shipperdog.api.config.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Resolves request-scoped context ({@code correlationId}, client IP) for
 * audit enrichment. Web-driven flows read the values the
 * {@link CorrelationIdFilter} established for the current thread; flows
 * outside a request (startup, schedulers) get null and the event carries
 * no context rather than a placeholder.
 */
final class AuditRequestContext {

    /** IPv6 textual addresses are the longest the column stores. */
    private static final int MAX_IP_LENGTH = 45;

    private static final String FORWARDED_FOR = "X-Forwarded-For";

    private AuditRequestContext() {}

    /** The correlation id of the current request, or null off-request. */
    static String correlationId() {
        String value = MDC.get(CorrelationIdFilter.MDC_KEY);
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * The originating client address: the first hop of
     * {@code X-Forwarded-For} when a proxy supplied one (the platform runs
     * behind load balancers), otherwise the socket's remote address.
     */
    static String clientIpAddress() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            String forwarded = request.getHeader(FORWARDED_FOR);
            if (forwarded != null && !forwarded.isBlank()) {
                String first = forwarded.split(",")[0].trim();
                if (!first.isEmpty()) {
                    return truncate(first);
                }
            }
            return truncate(request.getRemoteAddr());
        }
        return null;
    }

    private static String truncate(String address) {
        return address.length() <= MAX_IP_LENGTH ? address : address.substring(0, MAX_IP_LENGTH);
    }
}
