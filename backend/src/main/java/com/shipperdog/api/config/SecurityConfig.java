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
                                .anyRequest()
                                .authenticated())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .exceptionHandling(handling ->
                        handling.authenticationEntryPoint(problemHandlers).accessDeniedHandler(problemHandlers));
        return http.build();
    }
}
