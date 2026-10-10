package com.shipperdog.api.distribution.internal.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * The distribution module's wiring (issue #61, S13-01): the adapter
 * sandbox's properties. The secret store's properties register beside
 * its own wiring ({@code SecretStoreWiring}).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DistributionProperties.class)
class DistributionConfig {}
