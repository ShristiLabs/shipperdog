package com.shipperdog.api.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Buffers API POST responses so the idempotency interceptor (which runs
 * inside the dispatcher, after this filter) can capture the rendered
 * outcome for later replay. Buffering is confined to {@code POST /api/v1/*}
 * — the only surface where designated idempotent handlers live — and the
 * bytes are flushed to the client in {@code finally} no matter how the
 * request ends.
 */
public final class IdempotencyFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod())
                || !request.getRequestURI().startsWith("/api/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        ContentCachingResponseWrapper buffered = new ContentCachingResponseWrapper(response);
        try {
            filterChain.doFilter(request, buffered);
        } finally {
            buffered.copyBodyToResponse();
        }
    }
}
