package com.shipperdog.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * Baseline security posture for everything outside {@code /api/v1/**}
 * (which gets its own chain from the identity module, see
 * {@code IdentitySecurityConfig}): only infrastructure probe surfaces are
 * public, everything else requires authentication. Business endpoints get
 * their authorization rules in their own stories.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    @Order(2)
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
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
                                .anyRequest()
                                .authenticated())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .exceptionHandling(handling ->
                        handling.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }
}
