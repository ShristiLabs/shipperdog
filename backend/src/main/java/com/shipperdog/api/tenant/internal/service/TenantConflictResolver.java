package com.shipperdog.api.tenant.internal.service;

import com.shipperdog.api.tenant.TenantException;
import com.shipperdog.api.tenant.internal.repository.TenantRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Re-derives which tenant uniqueness guard lost a race (uq_tenants_org_name,
 * uq_tenants_external_id) after a constraint violation, without parsing
 * driver messages. The probes run in their own transaction on purpose:
 * PostgreSQL aborts the failed write's transaction, so any re-derivation
 * query on that transaction dies with "current transaction is aborted" —
 * a fresh transaction sees the concurrent winner's committed row and
 * classifies the conflict precisely (the race discipline introduced with
 * the application registry, issue #13, applied to the tenant guards of
 * issue #9). The constraint index remains the final authority; this only
 * names the loser for the 409 code.
 */
@Component
public class TenantConflictResolver {

    private final TenantRepository tenants;

    TenantConflictResolver(TenantRepository tenants) {
        this.tenants = tenants;
    }

    /** Names the guard a concurrent insert violated. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public TenantException insertConflict(UUID organizationId, String name) {
        if (tenants.existsByOrganizationIdAndName(organizationId, name)) {
            return nameTaken();
        }
        return externalIdTaken();
    }

    /**
     * Names the guard a concurrent update violated, excluding the tenant
     * itself: the external-id guard is checked first with self-exclusion,
     * because an unchanged name trivially still exists in the organization
     * (the tenant itself holds it) and would mislabel an external-id race
     * as a name conflict (review finding on issue #9).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public TenantException updateConflict(UUID organizationId, UUID tenantId, String name, String externalId) {
        boolean externalTaken = tenants.findByExternalId(externalId)
                .filter(other -> !other.getId().equals(tenantId))
                .isPresent();
        if (externalTaken) {
            return externalIdTaken();
        }
        if (tenants.existsByOrganizationIdAndName(organizationId, name)) {
            return nameTaken();
        }
        return externalIdTaken();
    }

    private static TenantException nameTaken() {
        return new TenantException(
                TenantException.Code.NAME_TAKEN, "tenant name is already in use in the organization");
    }

    private static TenantException externalIdTaken() {
        return new TenantException(
                TenantException.Code.EXTERNAL_ID_TAKEN, "externalId is already mapped to another tenant");
    }
}
