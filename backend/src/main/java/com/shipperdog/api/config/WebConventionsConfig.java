package com.shipperdog.api.config;

import com.shipperdog.api.config.idempotency.IdempotencyService;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.ObjectMapper;

/**
 * Registers the API-convention machinery (issue #8): the idempotency
 * response-buffering filter ahead of the dispatcher, and the idempotency
 * interceptor inside it.
 */
@Configuration
public class WebConventionsConfig implements WebMvcConfigurer {

    private final IdempotencyService idempotency;

    private final ObjectMapper mapper;

    public WebConventionsConfig(IdempotencyService idempotency, ObjectMapper mapper) {
        this.idempotency = idempotency;
        this.mapper = mapper;
    }

    @Bean
    FilterRegistrationBean<IdempotencyFilter> idempotencyFilterRegistration() {
        FilterRegistrationBean<IdempotencyFilter> registration = new FilterRegistrationBean<>(new IdempotencyFilter());
        // After the correlation filter (which must keep first place so
        // buffered error paths stay correlated) but ahead of the security
        // chains, so the interceptor's replay and 409 bodies flow through
        // the same buffering path.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.addUrlPatterns("/api/v1/*");
        return registration;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new IdempotencyInterceptor(idempotency, mapper));
    }
}
