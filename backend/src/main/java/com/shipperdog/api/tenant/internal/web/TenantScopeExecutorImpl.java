package com.shipperdog.api.tenant.internal.web;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.audit.AuditLogger;
import com.shipperdog.api.identity.PlatformAdmins;
import com.shipperdog.api.tenant.TenantContext;
import com.shipperdog.api.tenant.TenantIsolationException;
import com.shipperdog.api.tenant.TenantScopeExecutor;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The {@link TenantScopeExecutor} implementation (issue #13): the tenant
 * module's own scope-binding code path for creation routes whose target
 * tenant arrives in the request body and for future background jobs.
 * Lives beside the route interceptor because both are the binding
 * authority the isolation seam trusts — everything else fails closed
 * without them.
 */
@Component
public class TenantScopeExecutorImpl implements TenantScopeExecutor {

    private final PlatformAdmins platformAdmins;
    private final AuditLogger audit;

    public TenantScopeExecutorImpl(PlatformAdmins platformAdmins, AuditLogger audit) {
        this.platformAdmins = platformAdmins;
        this.audit = audit;
    }

    @Override
    public <T> T inScope(UUID tenantId, UUID actorId, Supplier<T> work) {
        if (tenantId == null || actorId == null) {
            throw new TenantIsolationException("an explicit tenant scope requires tenantId and actorId");
        }
        if (TenantContext.current().isPresent()) {
            // Establish-only: a second binding would be a re-scope, which
            // the seam's architecture rules exist to prevent.
            throw new TenantIsolationException(
                    "a tenant context is already bound to this thread; explicit scoping refuses to re-scope");
        }
        boolean platformAdmin = platformAdmins.isPlatformAdmin(actorId);
        if (platformAdmin) {
            audit.record(AuditEvent.builder(AuditEventType.CROSS_TENANT_ACCESS)
                    .userId(actorId)
                    .tenantId(tenantId)
                    .success(true)
                    .detail(detailOf())
                    .build());
        }
        TenantContext.begin(tenantId, actorId, platformAdmin);
        try {
            return work.get();
        } finally {
            TenantContext.end();
        }
    }

    @Override
    public <T> T inOrganizationScope(UUID organizationId, UUID actorId, Supplier<T> work) {
        if (organizationId == null || actorId == null) {
            throw new TenantIsolationException("an explicit organization scope requires organizationId and actorId");
        }
        if (TenantContext.current().isPresent()) {
            throw new TenantIsolationException(
                    "a tenant context is already bound to this thread; explicit scoping refuses to re-scope");
        }
        boolean platformAdmin = platformAdmins.isPlatformAdmin(actorId);
        if (platformAdmin) {
            audit.record(AuditEvent.builder(AuditEventType.CROSS_TENANT_ACCESS)
                    .userId(actorId)
                    .orgId(organizationId)
                    .success(true)
                    .detail(detailOf())
                    .build());
        }
        TenantContext.beginOrganization(organizationId, actorId, platformAdmin);
        try {
            return work.get();
        } finally {
            TenantContext.end();
        }
    }

    private static Map<String, String> detailOf() {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("scope", "explicit");
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            detail.put("method", attributes.getRequest().getMethod());
            detail.put("uri", attributes.getRequest().getRequestURI());
        }
        return detail;
    }
}
