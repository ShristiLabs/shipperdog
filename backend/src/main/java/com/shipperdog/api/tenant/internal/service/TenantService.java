package com.shipperdog.api.tenant.internal.service;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.config.CursorPageable;
import com.shipperdog.api.config.TraceAttributes;
import com.shipperdog.api.organization.OrganizationAccess;
import com.shipperdog.api.tenant.TenantException;
import com.shipperdog.api.tenant.internal.entity.TenantEntity;
import com.shipperdog.api.tenant.internal.repository.TenantRepository;
import com.shipperdog.api.tester.TesterGroupProvisioning;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant lifecycle (issue #9, PRD 8.2 and FR-002): creation under an
 * organization, organization-scoped listing with a lifecycle filter, and
 * partial update including the reversible ACTIVE/DEACTIVATED transition.
 * Uniqueness is database-backed end to end: the within-organization
 * identifier (name, uq_tenants_org_name) and the globally-unique
 * customer-system mapping (external_id, uq_tenants_external_id) are both
 * pre-checked for precise 409 codes and re-derived after a race, with
 * the constraint as the final authority. A tenant is never a user (PRD
 * 8.2/8.8): no login surface, no identity coupling.
 *
 * <p>Endpoint authorization is declared with {@code @PreAuthorize}
 * permission annotations enforced by the authorization module — the
 * single enforcement point. Lifecycle management is organization-level
 * (org:tenants:create / org:tenants:read on the owning organization,
 * tenant:tenants:update on the tenant); tenant administrators hold only
 * tenant:tenants:read. Every mutation emits an audit event carrying
 * who/what and before/after detail.
 *
 * <p>Deactivation semantics (issue #9, acceptance criterion 3):
 * deactivating preserves all data and revokes nothing by itself; it
 * blocks NEW affiliations — membership additions today, tester
 * invitations and distributions in their owning stories (S2-03, S7) —
 * and is reversible. Existing members keep their access until the
 * isolation story (S2-02) tightens reads.
 */
@Service
public class TenantService {

    private final TenantRepository tenants;
    private final OrganizationAccess organizations;
    private final TenantConflictResolver conflicts;
    private final TesterGroupProvisioning testerGroups;
    private final AuditEventPublisher audit;
    private final Clock clock;
    private final TraceAttributes trace;

    TenantService(
            TenantRepository tenants,
            OrganizationAccess organizations,
            TenantConflictResolver conflicts,
            TesterGroupProvisioning testerGroups,
            AuditEventPublisher audit,
            Clock clock,
            TraceAttributes trace) {
        this.tenants = tenants;
        this.organizations = organizations;
        this.conflicts = conflicts;
        this.testerGroups = testerGroups;
        this.audit = audit;
        this.clock = clock;
        this.trace = trace;
    }

    /**
     * Creates a tenant under an organization (AC1); the tenant starts
     * ACTIVE. Caller permission (org:tenants:create on the target
     * organization) is enforced at the controller.
     */
    @Transactional
    public TenantEntity create(UUID actorId, UUID organizationId, String name, String externalId) {
        if (organizationId == null) {
            throw new TenantException(TenantException.Code.INVALID_REQUEST, "organizationId is required");
        }
        if (!organizations.organizationExists(organizationId)) {
            // Only reachable by platform administrators (the evaluator
            // conceal-denies everyone else on a missing organization);
            // same envelope shape as the module's other misses.
            throw new TenantException(TenantException.Code.NOT_FOUND, "organization not found");
        }
        String validatedName = requireName(name);
        String validatedExternalId = requireExternalId(externalId);
        if (tenants.existsByOrganizationIdAndName(organizationId, validatedName)) {
            throw nameTaken();
        }
        if (tenants.existsByExternalId(validatedExternalId)) {
            throw externalIdTaken();
        }
        TenantEntity tenant;
        try {
            tenant = tenants.save(new TenantEntity(organizationId, validatedName, validatedExternalId));
            // Push the insert to the database inside the guard so a lost
            // uniqueness race translates here, not at commit (issue #13
            // race discipline; UUID ids mean save() alone defers the SQL).
            tenants.flush();
        } catch (DataIntegrityViolationException e) {
            // Fresh-transaction re-derivation: the racing transaction is
            // aborted and cannot answer probes (PostgreSQL).
            throw conflicts.insertConflict(organizationId, validatedName);
        }
        // The created id is only known here, after the insert (issue #7
        // convention); the request's trace already carries the owning
        // organization from the guarded target.
        trace.tenant(tenant.getId());
        // Default tester groups are part of the tenant's provisioning
        // (issue #27, S6-02): seeded inside this transaction through the
        // tester module's callback, so a seeding failure fails the whole
        // creation — a tenant never lands half-provisioned.
        testerGroups.seedDefaultGroups(tenant.getId(), actorId);
        Map<String, String> created = new LinkedHashMap<>();
        created.put("name", validatedName);
        created.put("externalId", validatedExternalId);
        created.put("status", tenant.getStatus().name());
        audit(AuditEventType.TENANT_CREATED, actorId, organizationId, tenant.getId(), true, Map.copyOf(created));
        return tenant;
    }

    /** Read is permission-gated at the controller (tenant:tenants:read). */
    @Transactional(readOnly = true)
    public TenantEntity get(UUID tenantId) {
        return mustFind(tenantId);
    }

    /**
     * Lists the organization's tenants, optionally filtered by lifecycle
     * status (AC5); every query is organization-scoped from day one so
     * cross-organization leakage is structurally impossible (INV-001
     * groundwork; S2-02 hardens the rest).
     */
    @Transactional(readOnly = true)
    public Page<TenantEntity> list(UUID organizationId, String status, long offset, int limit) {
        Pageable pageable = new CursorPageable((int) offset, limit);
        if (status == null) {
            return tenants.findByOrganizationIdOrderByCreatedAtAscIdAsc(organizationId, pageable);
        }
        return tenants.findByOrganizationIdAndStatusOrderByCreatedAtAscIdAsc(
                organizationId, parseLifecycleStatus(status), pageable);
    }

    /**
     * Partial update (name, externalId, status); null fields are left
     * unchanged. The status transition is the reversible deactivation
     * lifecycle of acceptance criterion 3. Caller permission
     * (tenant:tenants:update) is enforced at the controller.
     */
    @Transactional
    public TenantEntity update(UUID actorId, UUID tenantId, String name, String externalId, String status) {
        TenantEntity tenant = mustFind(tenantId);
        String beforeName = tenant.getName();
        String beforeExternalId = tenant.getExternalId();
        Map<String, String> changes = new LinkedHashMap<>();
        if (name != null) {
            String validated = requireName(name);
            if (!validated.equals(beforeName)
                    && tenants.existsByOrganizationIdAndName(tenant.getOrganizationId(), validated)) {
                throw nameTaken();
            }
            putChange(changes, "name", beforeName, validated);
            tenant.setName(validated);
        }
        if (externalId != null) {
            String validated = requireExternalId(externalId);
            if (!validated.equals(beforeExternalId)
                    && tenants.findByExternalId(validated)
                            .filter(other -> !other.getId().equals(tenantId))
                            .isPresent()) {
                throw externalIdTaken();
            }
            putChange(changes, "externalId", beforeExternalId, validated);
            tenant.setExternalId(validated);
        }
        if (status != null) {
            TenantEntity.Status parsed = parseLifecycleStatus(status);
            putChange(changes, "status", tenant.getStatus().name(), parsed.name());
            tenant.setStatus(parsed);
        }
        try {
            tenant = tenants.save(tenant);
            // Same race discipline as creation: the constraints speak at
            // the database inside the guard, translated here for precise
            // codes, and the re-derivation runs on a fresh transaction.
            tenants.flush();
        } catch (DataIntegrityViolationException e) {
            throw conflicts.updateConflict(
                    tenant.getOrganizationId(), tenantId, tenant.getName(), tenant.getExternalId());
        }
        audit(
                AuditEventType.TENANT_UPDATED,
                actorId,
                tenant.getOrganizationId(),
                tenant.getId(),
                true,
                Map.copyOf(changes));
        return tenant;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private TenantEntity mustFind(UUID tenantId) {
        return tenants.findById(tenantId)
                .orElseThrow(() -> new TenantException(TenantException.Code.NOT_FOUND, "tenant not found"));
    }

    private static TenantException nameTaken() {
        return new TenantException(
                TenantException.Code.NAME_TAKEN, "tenant name is already in use in the organization");
    }

    private static TenantException externalIdTaken() {
        return new TenantException(
                TenantException.Code.EXTERNAL_ID_TAKEN, "externalId is already mapped to another tenant");
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank() || name.trim().length() > 200) {
            throw new TenantException(TenantException.Code.INVALID_REQUEST, "name is required (max 200 characters)");
        }
        return name.trim();
    }

    /** Tenants always carry the customer-system mapping (NOT NULL, V1). */
    private static String requireExternalId(String externalId) {
        if (externalId == null || externalId.isBlank() || externalId.trim().length() > 100) {
            throw new TenantException(
                    TenantException.Code.INVALID_REQUEST, "externalId is required (1-100 characters)");
        }
        return externalId.trim();
    }

    /** Only the issue #9 lifecycle values; DISABLED stays reserved-unused. */
    private static TenantEntity.Status parseLifecycleStatus(String status) {
        if ("ACTIVE".equals(status) || "DEACTIVATED".equals(status)) {
            return TenantEntity.Status.valueOf(status);
        }
        throw new TenantException(TenantException.Code.INVALID_REQUEST, "status must be ACTIVE or DEACTIVATED");
    }

    /** Before/after pair for audit detail; a null "before" means unset. */
    private static void putChange(Map<String, String> changes, String field, String before, String after) {
        changes.put(field + ".before", before == null ? "" : before);
        changes.put(field + ".after", after == null ? "" : after);
    }

    private void audit(
            AuditEventType type,
            UUID actorId,
            UUID organizationId,
            UUID tenantId,
            boolean success,
            Map<String, String> detail) {
        audit.publish(new AuditEvent(type, Instant.now(clock), actorId, organizationId, tenantId, success, detail));
    }
}
