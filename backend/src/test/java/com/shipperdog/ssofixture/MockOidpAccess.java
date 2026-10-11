package com.shipperdog.ssofixture;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Opens the mock OIDC provider's endpoints to the test's anonymous
 * HTTP client (the relying party's outbound fetches). Registered
 * alongside {@link MockOidcProvider} via {@code @Import}; ordered
 * ahead of the baseline chain so {@code /mock-idp/**} never falls
 * through to its authenticated default.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MockOidpAccess {

    @Bean
    @Order(0)
    SecurityFilterChain mockIdpSecurityFilterChain(HttpSecurity http) throws Exception {
        return http.securityMatcher("/mock-idp/**")
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }
}
