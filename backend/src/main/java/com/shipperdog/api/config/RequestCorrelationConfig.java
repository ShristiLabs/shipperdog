package com.shipperdog.api.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers the {@link CorrelationIdFilter} ahead of every other filter
 * (including the security chains) so correlation ids exist from the first
 * byte of handling, including on authentication and permission denials.
 */
@Configuration
public class RequestCorrelationConfig {

    @Bean
    FilterRegistrationBean<CorrelationIdFilter> correlationIdFilterRegistration() {
        FilterRegistrationBean<CorrelationIdFilter> registration =
                new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
