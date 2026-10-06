package com.shipperdog.api.tester.internal.repository;

import com.shipperdog.api.tenant.TenantScopedRepository;
import com.shipperdog.api.tester.internal.entity.TesterGroupEntity;

/**
 * Tenant-scoped access to {@code tester_groups} (issue #27, S6-02): the
 * endpoint-data path for group CRUD and listings, filtered to the
 * request's tenant context by construction — a foreign tenant's group
 * is indistinguishable from a missing one (INV-001, ADR-0002 tenth
 * amendment's reserved landing). Declares no methods of its own by
 * design — the architecture tests reject derived queries on seam
 * repositories, so every access composes through
 * {@link com.shipperdog.api.tenant.TenantScopedAccess}.
 */
public interface TesterGroupScopedRepository extends TenantScopedRepository<TesterGroupEntity> {}
