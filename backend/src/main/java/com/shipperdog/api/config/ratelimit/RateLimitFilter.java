package com.shipperdog.api.config.ratelimit;

import com.shipperdog.api.config.ApiError;
import com.shipperdog.api.config.ApiProblems;
import com.shipperdog.api.config.MetricNames;
import io.github.bucket4j.Bucket;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * The in-app request rate limiter (issue #39, S8-05): a token bucket
 * per (rule, client IP) over the two abuse surfaces a public M1
 * deployment must not run unthrottled — the credential-stuffing door
 * ({@code POST /api/v1/auth/login}) and the anonymous download tier
 * (the emulator proxy {@code GET /api/v1/downloads/*} plus the
 * issuance {@code GET /api/v1/artifacts/{id}/download} it feeds). A
 * refused request answers {@code 429 RATE_LIMITED} in the single
 * problem+json envelope with a {@code Retry-After} header carrying
 * the seconds until the next token — the issue's contract.
 *
 * <p>Buckets are local and in-memory (bucket4j): the deployment
 * topologies of M1 (compose, single Cloud Run service) make that the
 * honest bound, cross-instance aggregation belongs to the gateway
 * (Dwara fronts production — the S8-05 scope note), and the limiter
 * deliberately fails OPEN: any internal failure serves the request
 * rather than inventing an outage. The bucket cache is LRU-bounded so
 * a forged-header flood cannot grow it without limit.
 *
 * <p>The filter runs after the correlation filter (so the 429 carries
 * a {@code trace_id}) and BEFORE the security chains: an unthrottled
 * flood should cost one map lookup, not a JWT decode or a password
 * hash — the identity lockout behind it stays the per-ACCOUNT
 * authority while this is the per-CLIENT one.
 */
final class RateLimitFilter extends OncePerRequestFilter {

    private static final String ARTIFACTS_PREFIX = "/api/v1/artifacts/";
    private static final String DOWNLOAD_SUFFIX = "/download";

    private final RateLimitProperties properties;
    private final ClientAddressResolver clients;
    private final Map<String, Bucket> buckets;
    private final MeterRegistry meters;
    private final ObjectMapper mapper;

    RateLimitFilter(RateLimitProperties properties, MeterRegistry meters, ObjectMapper mapper) {
        this.properties = properties;
        this.clients = new ClientAddressResolver(properties.trustedProxyCount());
        this.buckets = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
                return size() > properties.maxTrackedClients();
            }
        };
        this.meters = meters;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!properties.enabled()) {
            chain.doFilter(request, response);
            return;
        }
        String rule = ruleFor(request.getMethod(), request.getRequestURI());
        if (rule == null) {
            chain.doFilter(request, response);
            return;
        }
        Bucket bucket;
        try {
            bucket = bucketFor(rule, clients.clientOf(request));
        } catch (RuntimeException limiterFailure) {
            // Fail open: a limiter fault must not become an API outage.
            chain.doFilter(request, response);
            return;
        }
        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
            return;
        }
        refuse(rule, bucket, response);
    }

    /**
     * The rule table: the login door, both legs of the download tier
     * (the anonymous emulator proxy and the authenticated issuance
     * behind it), and the inbound webhook receivers (S12-04, issue
     * #54) -- anonymous, provider-facing POSTs whose senders retry,
     * throttled per client before they cost signature or chain work.
     * Everything else passes — the limiter protects specific abuse
     * surfaces, it is not a general tariff.
     */
    private static String ruleFor(String method, String path) {
        if ("POST".equals(method) && "/api/v1/auth/login".equals(path)) {
            return "login";
        }
        if ("POST".equals(method) && path.startsWith("/webhooks/")) {
            return "webhooks";
        }
        if ("GET".equals(method) && path.startsWith("/api/v1/downloads/")) {
            return "downloads";
        }
        if ("GET".equals(method) && isArtifactIssuance(path)) {
            return "downloads";
        }
        return null;
    }

    /** {@code /api/v1/artifacts/{id}/download} — exactly one path segment as the id. */
    private static boolean isArtifactIssuance(String path) {
        if (!path.startsWith(ARTIFACTS_PREFIX) || !path.endsWith(DOWNLOAD_SUFFIX)) {
            return false;
        }
        String between = path.substring(ARTIFACTS_PREFIX.length(), path.length() - DOWNLOAD_SUFFIX.length());
        return !between.isEmpty() && between.indexOf('/') == -1;
    }

    private synchronized Bucket bucketFor(String rule, String client) {
        return buckets.computeIfAbsent(rule + ":" + client, key -> {
            RateLimitProperties.Rule shape = properties.rules().getOrDefault(rule, defaultShape());
            return Bucket.builder()
                    .addLimit(limit -> limit.capacity(shape.capacity()).refillGreedy(1, shape.refill()))
                    .build();
        });
    }

    private static RateLimitProperties.Rule defaultShape() {
        return new RateLimitProperties.Rule(10, Duration.ofSeconds(6));
    }

    private void refuse(String rule, Bucket bucket, HttpServletResponse response) throws IOException {
        meters.counter(MetricNames.RATE_LIMIT_REJECTED, "rule", rule).increment();
        long retryAfterSeconds = Math.max(
                1, (bucket.estimateAbilityToConsume(1).getNanosToWaitForRefill() + 999_999_999) / 1_000_000_000);
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getWriter(), ApiProblems.of(ApiError.RATE_LIMITED));
    }
}
