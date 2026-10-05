package com.shipperdog.api.tenant.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.audit.AuditLogger;
import com.shipperdog.api.tenant.TenantContext;
import com.shipperdog.api.tenant.TenantIsolationException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The explicit scope executor's establish-only safety (issue #13,
 * ADR-0002): it binds a tenant scope for creation routes and background
 * work, refuses a second binding, requires both ids, and audits every
 * platform-administered execution as CROSS_TENANT_ACCESS.
 */
class TenantScopeExecutorImplTest {

    private final List<AuditEvent> published = new ArrayList<>();

    private final TenantScopeExecutorImpl executor =
            new TenantScopeExecutorImpl(actorId -> false, new AuditLogger(published::add, Clock.systemUTC()));

    @AfterEach
    void tearDown() {
        TenantContext.end();
    }

    @Test
    void workRunsInsideTheBoundScopeAndTheScopeEndsWithIt() {
        UUID tenantId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        TenantContext seen = executor.inScope(tenantId, actorId, () -> TenantContext.require());

        assertThat(seen.tenantId()).isEqualTo(tenantId);
        assertThat(seen.actorId()).isEqualTo(actorId);
        assertThat(seen.crossTenantOverride())
                .as("a non-platform-admin actor never receives the override")
                .isFalse();
        assertThat(TenantContext.current())
                .as("the executor always unbinds its scope")
                .isEmpty();
        assertThat(published)
                .as("ordinary scoped work is not cross-tenant access")
                .isEmpty();
    }

    @Test
    void organizationScopeWorkRunsBoundToTheOrganizationAndUnbindsAfter() {
        UUID orgId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        TenantContext seen = executor.inOrganizationScope(orgId, actorId, () -> TenantContext.require());

        assertThat(seen.organizationId()).isEqualTo(orgId);
        assertThat(seen.organizationScoped()).isTrue();
        assertThat(seen.tenantId()).isNull();
        assertThat(seen.crossTenantOverride()).isFalse();
        assertThat(TenantContext.current()).isEmpty();
        assertThat(published).isEmpty();
    }

    @Test
    void organizationScopeRequiresBothIds() {
        assertThatThrownBy(() -> executor.inOrganizationScope(null, UUID.randomUUID(), () -> "x"))
                .isInstanceOf(TenantIsolationException.class)
                .hasMessageContaining("organizationId and actorId");
        assertThatThrownBy(() -> executor.inOrganizationScope(UUID.randomUUID(), null, () -> "x"))
                .isInstanceOf(TenantIsolationException.class)
                .hasMessageContaining("organizationId and actorId");
    }

    @Test
    void organizationScopeRefusesToReplaceAnAlreadyBoundScope() {
        TenantContext.begin(UUID.randomUUID(), UUID.randomUUID(), false);
        assertThatThrownBy(() -> executor.inOrganizationScope(UUID.randomUUID(), UUID.randomUUID(), () -> "x"))
                .isInstanceOf(TenantIsolationException.class)
                .hasMessageContaining("refuses to re-scope");
    }

    @Test
    void platformAdministeredOrganizationScopeIsAuditedWithTheOrganization() {
        TenantScopeExecutorImpl platformExecutor =
                new TenantScopeExecutorImpl(actorId -> true, new AuditLogger(published::add, Clock.systemUTC()));
        UUID orgId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        platformExecutor.inOrganizationScope(orgId, actorId, () -> TenantContext.require());

        assertThat(published).hasSize(1);
        AuditEvent event = published.get(0);
        assertThat(event.type()).isEqualTo(AuditEventType.CROSS_TENANT_ACCESS);
        assertThat(event.orgId()).isEqualTo(orgId);
        assertThat(event.userId()).isEqualTo(actorId);
    }

    @Test
    void anExplicitScopeRefusesToReplaceAnAlreadyBoundScope() {
        UUID outerTenant = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        TenantContext.begin(outerTenant, actorId, false);

        assertThatThrownBy(() -> executor.inScope(UUID.randomUUID(), actorId, () -> "unreachable"))
                .isInstanceOf(TenantIsolationException.class)
                .hasMessageContaining("already bound");

        assertThat(TenantContext.require().tenantId())
                .as("the refused call leaves the original binding intact")
                .isEqualTo(outerTenant);
    }

    @Test
    void anExplicitScopeRequiresBothTenantAndActor() {
        assertThatThrownBy(() -> executor.inScope(null, UUID.randomUUID(), () -> "unreachable"))
                .isInstanceOf(TenantIsolationException.class);
        assertThatThrownBy(() -> executor.inScope(UUID.randomUUID(), null, () -> "unreachable"))
                .isInstanceOf(TenantIsolationException.class);
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void platformAdministratorsAreAuditedAsCrossTenantAccessWithOverride() {
        UUID tenantId = UUID.randomUUID();
        UUID platformAdminId = UUID.randomUUID();
        TenantScopeExecutorImpl platformAdminAware = new TenantScopeExecutorImpl(
                actorId -> actorId.equals(platformAdminId), new AuditLogger(published::add, Clock.systemUTC()));

        Boolean override = platformAdminAware
                .inScope(tenantId, platformAdminId, TenantContext::require)
                .crossTenantOverride();

        assertThat(override)
                .as("the override derives from users.platform_admin, never from a role")
                .isTrue();
        assertThat(published).hasSize(1);
        assertThat(published.get(0).type()).isEqualTo(AuditEventType.CROSS_TENANT_ACCESS);
        assertThat(published.get(0).tenantId()).isEqualTo(tenantId);
        assertThat(published.get(0).userId()).isEqualTo(platformAdminId);
        assertThat(published.get(0).detail()).containsEntry("scope", "explicit");
    }
}
