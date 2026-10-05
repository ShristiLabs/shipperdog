package com.shipperdog.api.tenant.internal.web;

import com.shipperdog.api.application.ApplicationAccess;
import com.shipperdog.api.audit.AuditLogger;
import com.shipperdog.api.identity.PlatformAdmins;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the tenant-context interceptors (issues #10 and #13). They
 * run after the security chain (interceptors see only authenticated
 * traffic on guarded routes) and before handler invocation, so
 * method-security denials and successful handling alike find a bound
 * context — and {@code afterCompletion} always clears it.
 */
@Configuration
public class TenantWebConfig implements WebMvcConfigurer {

    private final PlatformAdmins platformAdmins;
    private final AuditLogger audit;
    private final ApplicationAccess applications;

    public TenantWebConfig(PlatformAdmins platformAdmins, AuditLogger audit, ApplicationAccess applications) {
        this.platformAdmins = platformAdmins;
        this.audit = audit;
        this.applications = applications;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new TenantContextInterceptor(platformAdmins, audit));
        registry.addInterceptor(new ApplicationTenantContextInterceptor(applications, platformAdmins, audit));
    }
}
