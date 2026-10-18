package com.shipperdog.api.otabundle.internal.service;

import com.shipperdog.api.otabundle.OtaAppTokenAuthenticationFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the OTA app token's wiring (issue #166, S16-04): the
 * properties beside the codec the issue endpoint mints through and
 * the check filter authenticates with. The FILTER bean is consumed by
 * the identity module's security chain (registered beside the PAT and
 * telemetry filters — it must sit INSIDE the chain, where a set
 * security context survives), so the filter class itself lives at the
 * otabundle module's public root with the codec and principal; the
 * codec is a module bean like every other port implementation (the
 * {@code TelemetryTokenConfig} shape).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OtaAppTokenProperties.class)
public class OtaAppTokenConfig {

    @Bean
    OtaAppTokenAuthenticationFilter otaAppTokenAuthenticationFilter(OtaAppTokenService tokens) {
        return new OtaAppTokenAuthenticationFilter(tokens);
    }
}
