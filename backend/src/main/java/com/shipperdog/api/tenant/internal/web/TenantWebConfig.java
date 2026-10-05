package com.shipperdog.api.tenant.internal.web;

import com.shipperdog.api.audit.AuditLogger;
import com.shipperdog.api.identity.PlatformAdmins;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the tenant-context interceptor (issue #10). Runs after the
 * security chain (interceptors see only authenticated traffic on guarded
 * routes) and before handler invocation, so method-security denials and
 * successful handling alike find a bound context — and
 * {@code afterCompletion} always clears it.
 */
@Configuration
public class TenantWebConfig implements WebMvcConfigurer {

    private final PlatformAdmins platformAdmins;
    private final AuditLogger audit;

    public TenantWebConfig(PlatformAdmins platformAdmins, AuditLogger audit) {
        this.platformAdmins = platformAdmins;
        this.audit = audit;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new TenantContextInterceptor(platformAdmins, audit));
    }
}
