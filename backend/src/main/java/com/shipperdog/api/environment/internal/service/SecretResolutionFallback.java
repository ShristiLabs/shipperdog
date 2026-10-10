package com.shipperdog.api.environment.internal.service;

import com.shipperdog.api.environment.SecretReferenceResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * The resolver fallback's registration (issue #61, S13-01): the
 * environment module's port stays its own; the distribution module's
 * store-backed resolver wins whenever a secret store is selected
 * (always, in a full application context), and the honest unbacked
 * stub exists for contexts that register none -- the {@code
 * ConditionalOnMissingBean} order the story's wiring composes
 * through. Parse order LOWEST (the distribution wiring pins itself
 * HIGHEST) so the condition evaluates against the store-backed
 * definition deterministically, whatever the scan order.
 */
@Order(Ordered.LOWEST_PRECEDENCE)
@Configuration(proxyBeanMethods = false)
class SecretResolutionFallback {

    @Bean
    @ConditionalOnMissingBean(SecretReferenceResolver.class)
    SecretReferenceResolver unbackedSecretReferenceResolver() {
        return new UnbackedSecretReferenceResolver();
    }
}
