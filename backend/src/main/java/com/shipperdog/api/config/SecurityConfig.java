package com.shipperdog.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Baseline security posture for everything outside {@code /api/v1/**}
 * (which gets its own chain from the identity module, see
 * {@code IdentitySecurityConfig}): only infrastructure probe surfaces are
 * public, everything else requires authentication. Business endpoints get
 * their authorization rules in their own stories. Denials render in the
 * single error envelope via {@link ProblemJsonSecurityHandlers} (issue #8).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    @Order(2)
    SecurityFilterChain securityFilterChain(HttpSecurity http, ProblemJsonSecurityHandlers problemHandlers)
            throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        auth -> auth.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info")
                                .permitAll()
                                // Metrics scrape endpoint (issue #7): served
                                // only where the operator puts it. The local
                                // stack moves all management endpoints to a
                                // separate management port (MANAGEMENT_SERVER_PORT)
                                // that is never published beyond the host /
                                // compose network, so the endpoint is internal
                                // by construction; on the API port it exists
                                // only when an operator explicitly adds it to
                                // MANAGEMENT_ENDPOINTS_EXPOSE, who then owns
                                // protecting it upstream.
                                .requestMatchers("/actuator/prometheus")
                                .permitAll()
                                // OpenAPI contract endpoints (issue #8): the
                                // generated spec is public by design — CI
                                // publishes it as an artifact for frontend,
                                // CLI, and consumers, it carries no secrets,
                                // and Dwara can restrict the surface in
                                // production if the operator prefers. The
                                // bare and .yaml paths sit outside the
                                // /v3/api-docs/** prefix pattern.
                                .requestMatchers("/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/**")
                                .permitAll()
                                // Inbound webhook receivers (S12-03, FR-022,
                                // PRD 19): POST /webhooks/github and the
                                // receivers S12-04/E13 add. These routes sit
                                // outside /api/v1 by design -- the delivery's
                                // own HMAC-SHA256 signature IS the
                                // authentication (the shared secret only the
                                // sender and this deployment hold), verified
                                // by the integration module before any
                                // processing; every authenticity failure
                                // answers its uniform 401. Anonymous by
                                // construction, like the download proxy's
                                // HMAC-bearing route above them.
                                .requestMatchers(org.springframework.http.HttpMethod.POST, "/webhooks/**")
                                .permitAll()
                                .anyRequest()
                                .authenticated())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .headers(ApiSecurityHeaders::apply)
                .exceptionHandling(handling ->
                        handling.authenticationEntryPoint(problemHandlers).accessDeniedHandler(problemHandlers));
        return http.build();
    }

    /**
     * The API-surface web-security headers (issue #39, S8-05): the
     * JSON API answers with the same hardening posture the origins
     * fronting it carry. Spring Security's defaults already send
     * {@code X-Content-Type-Options: nosniff}, {@code X-Frame-Options:
     * DENY}, and no-store caching; this tightens the remaining set —
     * HSTS (the platform terminates TLS at its ingress, so the header
     * is written on every response regardless of the hop's own
     * scheme, not only on already-secure requests), a
     * {@code no-referrer} policy, an empty Permissions-Policy, and a
     * {@code default-src 'none'} CSP that is inert for a JSON payload
     * but grades the origin (the securityheaders.com check of the
     * story runs against the portal; the API holds the same bar).
     */
    public static final class ApiSecurityHeaders {

        /** Applies the header set to one chain's {@code headers} configurer. */
        public static void apply(
                org.springframework.security.config.annotation.web.configurers.HeadersConfigurer<HttpSecurity>
                        headers) {
            headers.contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                    .referrerPolicy(referrer -> referrer.policy(
                            org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy
                                    .NO_REFERRER))
                    .addHeaderWriter(new org.springframework.security.web.header.writers.StaticHeadersWriter(
                            "Strict-Transport-Security", "max-age=31536000; includeSubDomains"))
                    .addHeaderWriter(new org.springframework.security.web.header.writers.StaticHeadersWriter(
                            "Permissions-Policy", "geolocation=(), camera=(), microphone=(), payment=(), usb=()"));
        }
    }
}
