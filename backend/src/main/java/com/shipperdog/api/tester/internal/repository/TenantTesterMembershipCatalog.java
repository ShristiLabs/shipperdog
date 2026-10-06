package com.shipperdog.api.tester.internal.repository;

import com.shipperdog.api.tenant.CrossTenantByDesign;
import com.shipperdog.api.tester.internal.entity.TenantTesterMembershipEntity;
import com.shipperdog.api.tester.internal.entity.TenantTesterMembershipId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * Cross-tenant, by-design catalog lookups over
 * {@code tenant_tester_memberships} (issue #28, S6-03) — the membership
 * analog of the tenant module's {@code TenantMemberRepository} and the
 * tester module's {@code TesterGroupCatalog}: the readers below CANNOT
 * run under the seam because they run without a bound tenant context.
 *
 * <ul>
 *   <li>The {@code TenantTesterMembershipAccess} surface answers the
 *       E7 authorization chain's precondition question — is this
 *       tester an ACTIVE member of this tenant — before any request
 *       scope exists (it is authorization data, exactly like
 *       {@code TenantMemberRepository}'s role resolution).</li>
 *   <li>The tenants-of-tester listing answers at the organization
 *       grain — a tester's memberships span their organization's
 *       tenants, so no single tenant context can scope it (the
 *       groups-per-tester precedent, issue #27).</li>
 *   <li>The bulk status flip is the S6-04 deactivation cascade's
 *       sanctioned write (issue #29): tester deactivation is an
 *       organization-grain lifecycle act spanning the organization's
 *       tenants, so no single tenant context can scope the write any
 *       more than the reads. It flips status only — a lifecycle
 *       transition on retained rows, never a row-creating or
 *       row-destroying mutation — and runs inside the deactivating
 *       transaction.</li>
 * </ul>
 *
 * <p>Marked {@link CrossTenantByDesign} so the isolation architecture
 * test accepts it as authorization-engine surface; endpoint data paths
 * use {@link TenantTesterMembershipScopedRepository} instead. Declares
 * ONLY derived queries and the one sanctioned cascade update of its
 * own (never the inherited {@code findById} family — the shared
 * repository base class enforces a tenant context on inherited reads
 * and writes of {@code TenantScope} rows, and these operations by
 * definition run without one), and it answers only its sanctioned
 * questions — never rows for endpoint responses.
 */
@CrossTenantByDesign
public interface TenantTesterMembershipCatalog
        extends JpaRepository<TenantTesterMembershipEntity, TenantTesterMembershipId> {

    /** The membership row behind the pair, for the access surface's live checks. */
    Optional<TenantTesterMembershipEntity> findByTenantIdAndTesterId(UUID tenantId, UUID testerId);

    /**
     * The tester's membership rows (any status — history included),
     * served by {@code idx_ttm_tester_id}; the caller narrows to the
     * tester's own organization's tenants before any row moves.
     */
    List<TenantTesterMembershipEntity> findByTesterIdOrderByCreatedAtAscTenantIdAsc(UUID testerId);

    /**
     * The S6-04 deactivation cascade: suspends every ACTIVE membership
     * of the tester in one statement (the bulk path sets
     * {@code updated_at} explicitly — JPQL bulk updates bypass the
     * entity lifecycle callbacks). Returns the number of rows flipped.
     */
    @Modifying(flushAutomatically = true)
    @Query("update TenantTesterMembershipEntity m"
            + " set m.status = :suspended, m.updatedAt = :now"
            + " where m.testerId = :testerId and m.status = :active")
    int suspendActiveByTesterId(
            UUID testerId,
            TenantTesterMembershipEntity.Status active,
            TenantTesterMembershipEntity.Status suspended,
            Instant now);
}
