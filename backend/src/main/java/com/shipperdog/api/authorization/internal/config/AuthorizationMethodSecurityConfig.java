package com.shipperdog.api.authorization.internal.config;

import com.shipperdog.api.authorization.internal.evaluator.RbacPermissionEvaluator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Wires the RBAC engine into Spring Security's method interception (issue
 * #5): {@code @EnableMethodSecurity} turns on {@code @PreAuthorize}
 * processing for every module, and the shared {@link
 * MethodSecurityExpressionHandler} binds {@code hasPermission(...)} to
 * the authorization module's evaluator — the single enforcement point.
 */
@Configuration
@EnableMethodSecurity
public class AuthorizationMethodSecurityConfig {

    @Bean
    static MethodSecurityExpressionHandler methodSecurityExpressionHandler(RbacPermissionEvaluator evaluator) {
        DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
        handler.setPermissionEvaluator(evaluator);
        return handler;
    }
}
