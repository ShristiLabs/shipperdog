package com.shipperdog.api.tester.internal.repository;

import com.shipperdog.api.tenant.CrossTenantByDesign;
import com.shipperdog.api.tester.internal.entity.TesterGroupEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Cross-tenant, by-design catalog lookups over {@code tester_groups}
 * (issue #27, S6-02) — the group analog of the tenant module's
 * {@code TenantMemberRepository}: the readers below CANNOT run under
 * the seam because they run before any tenant context exists.
 *
 * <ul>
 *   <li>The RBAC evaluator resolves a {@code 'tester-group'} target's
 *       owning tenant (permission evaluation happens outside any bound
 *       scope — the evaluator is the seam's gatekeeper, not its
 *       client).</li>
 *   <li>The tenant module's route interceptor resolves the group's
 *       tenant to BIND the context the seam then enforces.</li>
 *   <li>The groups-per-tester listing answers at the organization
 *       grain — a tester's groups span the organization's tenants, so
 *       no single tenant context can scope it (the tester person
 *       record is organization-scoped per the ADR-0002 tenth
 *       amendment; the caller's organization permission narrows the
 *       query to that organization's tenants before any row moves).</li>
 * </ul>
 *
 * <p>Marked {@link CrossTenantByDesign} so the isolation architecture
 * test accepts it as authorization-engine surface; endpoint data paths
 * use {@link TesterGroupScopedRepository} instead. Declares ONLY
 * derived queries of its own (never the inherited {@code findById}
 * family — the shared repository base class enforces a tenant context
 * on inherited reads of {@code TenantScope} rows, and these lookups by
 * definition run without one), and it answers only its sanctioned
 * questions — never rows for endpoint responses.
 */
@CrossTenantByDesign
public interface TesterGroupCatalog extends JpaRepository<TesterGroupEntity, UUID> {

    /** The group row behind the id, for ownership hops (evaluator, binding). */
    Optional<TesterGroupEntity> findGroupById(UUID id);

    /**
     * The groups among {@code ids} owned by the listed tenants, for the
     * organization-grain groups-per-tester listing (ids arrive from the
     * junction's tester index, tenants from the tester's own
     * organization).
     */
    List<TesterGroupEntity> findGroupByIdInAndTenantIdIn(Collection<UUID> ids, Collection<UUID> tenantIds, Sort sort);
}
