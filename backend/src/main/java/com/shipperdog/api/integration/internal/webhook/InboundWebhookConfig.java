package com.shipperdog.api.integration.internal.webhook;

import java.io.InputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.io.Resource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Binds the inbound webhook receivers' properties (issue #53, S12-03;
 * extended issue #54, S12-04): {@code shipperdog.webhooks.*} -- the
 * per-provider credential lists (rotation is overlap), the
 * timestamp-window replay seam, and the body ceiling. See {@link
 * InboundWebhookProperties}. This config also assembles the two
 * provider verifiers whose dependencies come FROM those properties
 * (the Apple JWS verifier's trusted roots, the Google JWT verifier's
 * audience/JWKS endpoint) and registers the bounded-read body filter
 * for {@code /webhooks/*}.
 */
@Configuration
@EnableConfigurationProperties(InboundWebhookProperties.class)
public class InboundWebhookConfig {

    /**
     * The App Store verifier's trust anchors: the configured root
     * certificate resources (Apple Root CA G3 by default -- a public
     * certificate, not a secret), loaded and parsed at startup,
     * FAIL-CLOSED: a configured resource that does not exist or does
     * not parse stops the application rather than letting the
     * receiver run with an empty trust store.
     */
    @Bean
    AppleJwsVerifier appleJwsVerifier(
            InboundWebhookProperties properties, ObjectMapper mapper, ApplicationContext context) {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            List<X509Certificate> roots = new ArrayList<>();
            for (String location : properties.appstoreTrustedRoots()) {
                Resource resource = context.getResource(location);
                try (InputStream in = resource.getInputStream()) {
                    factory.generateCertificates(in).stream()
                            .filter(X509Certificate.class::isInstance)
                            .map(X509Certificate.class::cast)
                            .forEach(roots::add);
                }
            }
            if (roots.isEmpty()) {
                throw new IllegalStateException(
                        "no trusted root certificates loaded from " + properties.appstoreTrustedRoots());
            }
            return new AppleJwsVerifier(roots, mapper);
        } catch (Exception e) {
            throw new IllegalStateException("cannot load the appstore trusted roots", e);
        }
    }

    /**
     * The Google Play verifier, from the same properties: the
     * required audience (empty keeps the receiver off), the optional
     * sender-email allowlist, the JWKS endpoint (cached at verify
     * time, never at startup -- an off receiver makes no network
     * call), and the replay window for the token's issued-at.
     */
    @Bean
    GoogleJwtVerifier googleJwtVerifier(InboundWebhookProperties properties, ObjectMapper mapper, Clock clock) {
        return new GoogleJwtVerifier(
                properties.googlePlayAudience(),
                properties.googlePlayTrustedEmails(),
                properties.googlePlayJwksUri(),
                properties.maxTimestampSkew(),
                mapper,
                RestClient.create(),
                clock);
    }

    /**
     * Registers the bounded-read body filter for the receiver family,
     * directly after the rate limiter: an oversized declared
     * Content-Length is refused before its bytes are buffered, and a
     * flood is throttled even before that.
     */
    @Bean
    FilterRegistrationBean<WebhookBodyLimitFilter> webhookBodyLimitFilterRegistration(
            InboundWebhookProperties properties, ObjectMapper mapper) {
        FilterRegistrationBean<WebhookBodyLimitFilter> registration =
                new FilterRegistrationBean<>(new WebhookBodyLimitFilter(properties, mapper));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        registration.addUrlPatterns("/webhooks/*");
        return registration;
    }
}
