package com.shipperdog.api.tester.internal.repository;

import com.shipperdog.api.tenant.TenantScopedRepository;
import com.shipperdog.api.tester.internal.entity.TenantTesterMembershipEntity;

/**
 * Tenant-scoped access to {@code tenant_tester_memberships} (issue #28,
 * S6-03): the endpoint-data path for membership listings and mutations,
 * filtered to the request's tenant context by construction — a foreign
 * tenant's membership is indistinguishable from a missing one (INV-001,
 * ADR-0002 tenth amendment's reserved landing: the junction carries its
 * own tenant column and therefore joins the seam directly). Declares no
 * methods of its own by design — the architecture tests reject derived
 * queries on seam repositories, so every access composes through
 * {@link com.shipperdog.api.tenant.TenantScopedAccess} (the composite
 * key is addressed as {@code findOneScoped(testerEquals(id))} composed
 * with the seam's tenant predicate).
 */
public interface TenantTesterMembershipScopedRepository extends TenantScopedRepository<TenantTesterMembershipEntity> {}
