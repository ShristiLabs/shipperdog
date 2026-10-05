package com.shipperdog.api.tenant.internal.repository;

import com.shipperdog.api.tenant.TenantScopedRepository;
import com.shipperdog.api.tenant.internal.entity.TenantMemberEntity;

/**
 * Tenant-scoped access to {@code tenant_members} (issue #10): the
 * endpoint-data path for membership listings and mutations, filtered to
 * the request's tenant context by construction. Declares no methods of
 * its own by design — the architecture tests reject derived queries on
 * seam repositories, so every access composes through
 * {@link com.shipperdog.api.tenant.TenantScopedAccess}.
 *
 * <p>Role resolution for the authorization engine intentionally uses the
 * cross-tenant {@link TenantMemberRepository} instead: memberships are
 * the authorization data itself.
 */
public interface TenantMemberScopedRepository extends TenantScopedRepository<TenantMemberEntity> {}
