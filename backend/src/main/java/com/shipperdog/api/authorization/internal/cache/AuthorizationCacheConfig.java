package com.shipperdog.api.authorization.internal.cache;

import com.shipperdog.api.application.ApplicationAccess;
import com.shipperdog.api.authorization.AuthorizationDecisionInvalidation;
import com.shipperdog.api.authorization.internal.grant.VersionAccessGrantCatalog;
import com.shipperdog.api.tester.TenantTesterMembershipAccess;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Wires the decision-input cache (issue #32, S7-03): the facade bean
 * the engine's decision path resolves through, exposed cross-module
 * as the {@link AuthorizationDecisionInvalidation} port the mutating
 * services call — one bean, two faces, so the readers and the
 * invalidators can never drift apart.
 */
@Configuration
@EnableConfigurationProperties(DecisionCacheProperties.class)
class AuthorizationCacheConfig {

    @Bean
    AuthorizationDecisionCache authorizationDecisionCache(
            StringRedisTemplate redis,
            DecisionCacheProperties settings,
            VersionAccessGrantCatalog grants,
            ObjectProvider<TenantTesterMembershipAccess> memberships,
            ObjectProvider<ApplicationAccess> applications,
            MeterRegistry meters) {
        return new AuthorizationDecisionCache(redis, settings, grants, memberships, applications, meters);
    }
}
