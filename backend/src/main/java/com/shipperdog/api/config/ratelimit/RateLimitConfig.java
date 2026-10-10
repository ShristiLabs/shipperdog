package com.shipperdog.api.config.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import tools.jackson.databind.ObjectMapper;

/**
 * Registers the rate-limit filter (issue #39, S8-05) directly after
 * the correlation filter, ahead of the forwarded-header filter and
 * the security chains: the 429 envelope must carry a {@code
 * trace_id}, the client resolver must see the RAW {@code
 * X-Forwarded-For} chain before any forwarded-header strategy
 * consumes it, and a flood must be refused before it costs
 * authentication work (see {@link RateLimitFilter}).
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(
            RateLimitProperties properties, MeterRegistry meters, ObjectMapper mapper) {
        FilterRegistrationBean<RateLimitFilter> registration =
                new FilterRegistrationBean<>(new RateLimitFilter(properties, meters, mapper));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        // The webhook receivers join the limiter's surface with
        // S12-04 (issue #54): they are anonymous, provider-facing
        // endpoints whose senders retry -- an abusive or mis-wired
        // producer must be throttled before it costs signature or
        // certificate-chain work (the same order rationale as /api/v1).
        registration.addUrlPatterns("/api/v1/*", "/webhooks/*");
        return registration;
    }
}
