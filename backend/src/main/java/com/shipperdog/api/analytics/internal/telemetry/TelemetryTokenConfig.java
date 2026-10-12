package com.shipperdog.api.analytics.internal.telemetry;

import com.shipperdog.api.analytics.TelemetryTokenAuthenticationFilter;
import com.shipperdog.api.analytics.TelemetryTokenCodec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the telemetry token's wiring (issue #72, S15-01): the
 * properties beside the codec the download issuance mints through
 * and the ingest filter authenticates with. The FILTER bean is
 * consumed by the identity module's security chain (registered
 * beside the PAT filter — it must sit INSIDE the chain, where a set
 * security context survives), so the filter class itself lives at
 * the analytics module's public root with the codec and principal;
 * the codec is a module bean like every other port implementation.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TelemetryTokenProperties.class)
public class TelemetryTokenConfig {

    @Bean
    TelemetryTokenAuthenticationFilter telemetryTokenAuthenticationFilter(TelemetryTokenCodec tokens) {
        return new TelemetryTokenAuthenticationFilter(tokens);
    }
}
