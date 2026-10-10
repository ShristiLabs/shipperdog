package com.shipperdog.api.integration.internal.webhook;

import com.shipperdog.api.config.ApiProblems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * The bounded-read leg of the webhook body ceiling (issue #54,
 * S12-04 -- the S12-03 review's deferred hardening, implemented now
 * that the receiver family is complete): a POST to {@code
 * /webhooks/*} whose DECLARED {@code Content-Length} exceeds the
 * configured ceiling is answered 413 problem+json before a single
 * body byte is buffered. The controllers' own {@code byte[]} ceiling
 * stays the authority on ACTUAL bytes (the filter's check is
 * advisory for chunked or lying senders); this filter bounds the
 * read cost itself, so an oversized flood costs one header parse,
 * not a body-length allocation.
 *
 * <p>Registered for {@code /webhooks/*} directly after the rate
 * limiter (a flood is first throttled per client, then bounded per
 * request); it deliberately fails OPEN on any internal fault -- the
 * ceiling's authority is the controller read, and this tier must not
 * become the outage.
 */
public final class WebhookBodyLimitFilter extends OncePerRequestFilter {

    private final InboundWebhookProperties properties;
    private final ObjectMapper mapper;

    public WebhookBodyLimitFilter(InboundWebhookProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long declared;
        try {
            String header = request.getHeader("Content-Length");
            declared = header == null ? -1 : Long.parseLong(header.trim());
        } catch (RuntimeException e) {
            declared = -1;
        }
        if (declared > properties.maxBodyBytes()) {
            response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            mapper.writeValue(
                    response.getWriter(),
                    ApiProblems.of(
                            HttpStatus.PAYLOAD_TOO_LARGE,
                            "WEBHOOK_PAYLOAD_TOO_LARGE",
                            HttpStatus.PAYLOAD_TOO_LARGE.getReasonPhrase(),
                            "The webhook payload exceeds the configured maximum."));
            return;
        }
        chain.doFilter(request, response);
    }
}
