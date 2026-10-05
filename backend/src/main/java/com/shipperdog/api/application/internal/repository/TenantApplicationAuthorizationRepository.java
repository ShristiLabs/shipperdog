package com.shipperdog.api.application.internal.repository;

import com.shipperdog.api.application.internal.entity.TenantApplicationAuthorizationEntity;
import com.shipperdog.api.application.internal.entity.TenantApplicationAuthorizationEntity.Status;
import com.shipperdog.api.tenant.CrossTenantByDesign;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The tenant-application authorization mapping (issue #15, S3-03):
 * deliberately cross-tenant, explicitly marked {@link CrossTenantByDesign}
 * per ADR-0002 (second amendment pattern, fourth amendment scope).
 *
 * <p>These rows ARE authorization data — one input layer of the E7
 * authorization chain (PRD 11/12's "Does tenant have Banking?" step), in
 * the same sense as role resolution: an organization's distribution
 * decisions pointing into many tenants of one organization (PRD 15). No
 * seam mode can serve them — a tenant context scopes to one tenant and
 * misses the organization-level question, and the organization-scope
 * predicate composes {@code tenantId IS NULL} while these rows carry the
 * authorized tenant — so the sanctioned surface is this one marked,
 * reviewable interface. Its questions span tenants by the issue's own
 * definition:
 *
 * <ul>
 *   <li><strong>Grant/revoke state per pair</strong> — the management
 *       surface (AC1) reads and writes exactly one row per
 *       application x tenant pair; the service has already resolved the
 *       application through the seam (the route interceptor bound the
 *       owning scope) and run the tenant checks before any write.</li>
 *   <li><strong>The transition precondition</strong> — whether any tenant
 *       is currently authorized on a shared application blocks the
 *       ORGANIZATION-to-TENANT scope conversion (issue #14's declared
 *       precondition point, now bound).</li>
 *   <li><strong>Effective-visibility inputs</strong> — the per-pair lookups
 *       feeding resolution queries (the listing's authorized-shared arm
 *       composes through {@link ApplicationRegistryRepository}; E7/E8
 *       consume the same inputs).</li>
 * </ul>
 *
 * <p>Like every {@link CrossTenantByDesign} repository, this interface
 * declares only its own queries over the mapping rows; it never serves
 * application rows (those go through the seam), and an architecture test
 * pairs this marker with the seam rule.
 */
@CrossTenantByDesign
public interface TenantApplicationAuthorizationRepository
        extends JpaRepository<TenantApplicationAuthorizationEntity, UUID> {

    /**
     * The pair's single row (uq_tenant_application_authorizations), or
     * empty when the tenant was never authorized. The management surface
     * and the re-grant/revocation flips resolve through this lookup.
     */
    Optional<TenantApplicationAuthorizationEntity> findByApplicationIdAndTenantId(UUID applicationId, UUID tenantId);

    /**
     * Whether any tenant is currently authorized in the given status on
     * the application — the ORGANIZATION-to-TENANT scope-conversion
     * precondition (issue #14's declared point; ACTIVE rows block,
     * REVOKED history does not).
     */
    boolean existsByApplicationIdAndStatus(UUID applicationId, Status status);

    /**
     * The authorized-tenant list of one application (PRD 15's view),
     * paged in the platform listing order (grantedAt ASC, id ASC).
     * REVOKED rows are historical state and never listed.
     */
    Page<TenantApplicationAuthorizationEntity> findByApplicationIdAndStatus(
            UUID applicationId, Status status, Pageable pageable);

    /**
     * The re-grant flip as a conditional state transition: REVOKED to
     * ACTIVE with the new grant stamped and the revocation cleared,
     * only while the row still reads REVOKED. Exactly one concurrent
     * re-grant wins (returns 1); the losers return 0 and resolve as
     * idempotent successes against the winner's ACTIVE row. Bulk update
     * by id after the service's checks — the registry transition
     * precedent — with the persistence context cleared so post-update
     * reads see the committed state.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update TenantApplicationAuthorizationEntity m set m.status = :active, m.grantedBy = :actor,"
            + " m.grantedAt = :at, m.revokedBy = null, m.revokedAt = null, m.updatedAt = :at"
            + " where m.id = :id and m.status = :revoked")
    int regrantIfRevoked(
            @Param("id") UUID id,
            @Param("actor") UUID actor,
            @Param("at") Instant at,
            @Param("active") Status active,
            @Param("revoked") Status revoked);

    /**
     * The de-authorization flip as a conditional state transition:
     * ACTIVE to REVOKED with the revocation stamped, only while the row
     * still reads ACTIVE. Exactly one concurrent revocation wins
     * (returns 1, audited); the losers return 0 and surface the standard
     * miss — the pair's state is a machine, not a counter, and the audit
     * trail carries one event per real transition.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update TenantApplicationAuthorizationEntity m set m.status = :revoked, m.revokedBy = :actor,"
            + " m.revokedAt = :at, m.updatedAt = :at"
            + " where m.id = :id and m.status = :active")
    int revokeIfActive(
            @Param("id") UUID id,
            @Param("actor") UUID actor,
            @Param("at") Instant at,
            @Param("active") Status active,
            @Param("revoked") Status revoked);
}
