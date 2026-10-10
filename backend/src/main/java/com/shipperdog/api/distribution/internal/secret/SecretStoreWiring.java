package com.shipperdog.api.distribution.internal.secret;

import com.shipperdog.api.distribution.SecretMaterial;
import com.shipperdog.api.environment.SecretReferenceResolver;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * The secret store's wiring (issue #61, S13-01): this story is the
 * "secrets story" the environment module's port reserved its
 * production backing for (#58's javadoc -- "the production wiring is
 * GCP Secret Manager plus Cloud KMS and lands with the secrets story
 * this one deliberately excludes"). The selected {@link
 * ChannelSecretStore} now backs the platform-wide {@link
 * SecretReferenceResolver} port, so the environment credential verify
 * probe resolves real references for the first time: the honest
 * unbacked stub remains only as the fallback bean for contexts that
 * register no store.
 *
 * <p>Parse order HIGHEST so the store-backed definition registers
 * BEFORE the environment module's {@code ConditionalOnMissingBean}
 * fallback evaluates, deterministically, whatever the component-scan
 * order of the two packages (the condition is contractual only within
 * a known evaluation order -- this pins it).
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DistributionSecretProperties.class)
class SecretStoreWiring {

    /** The platform's reference resolution, backed by the selected store. */
    @Bean
    SecretReferenceResolver storeBackedSecretReferenceResolver(ChannelSecretStore store) {
        return reference -> store.resolve(reference).map(SecretMaterial::value);
    }
}
