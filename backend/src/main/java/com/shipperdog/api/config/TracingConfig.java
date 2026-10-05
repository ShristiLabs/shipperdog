package com.shipperdog.api.config;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.ObservationFilter;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.MDC;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Joins traces to the correlation id issued by {@link CorrelationIdFilter}
 * (issue #7, acceptance criterion 2, and the #6 follow-up "reuse the
 * correlationId MDC key without mapping"): every observation opened while
 * a request is being served — starting with the HTTP server observation
 * that becomes the request's span — carries the id as a high-cardinality
 * key value, which Micrometer Tracing copies onto the span as the
 * {@code correlation_id} attribute. The value is the exact id from the
 * MDC, so a span, every log line, and the request's audit events name
 * the same correlation id with no translation.
 *
 * <p>High cardinality keeps the id out of metric tags (per-request ids
 * must never reach the {@code shipperdog_*} metric label space).
 */
@Configuration
public class TracingConfig {

    @Bean
    ObservationRegistryCustomizer<ObservationRegistry> correlationObservationCustomizer() {
        return registry -> registry.observationConfig().observationFilter(correlationFilter());
    }

    static ObservationFilter correlationFilter() {
        return context -> {
            String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
            if (correlationId != null) {
                context.addHighCardinalityKeyValue(KeyValue.of("correlation_id", correlationId));
            }
            return context;
        };
    }
}
