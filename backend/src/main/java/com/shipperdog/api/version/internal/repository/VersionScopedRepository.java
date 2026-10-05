package com.shipperdog.api.version.internal.repository;

import com.shipperdog.api.tenant.TenantScopedRepository;
import com.shipperdog.api.version.internal.entity.VersionEntity;

/**
 * Seam-served access to {@code versions} (issue #17): reads, listings,
 * and creation compose through the tenant-scoped seam, which filters by
 * the request's bound scope (the owning tenant of the application, or
 * the owning organization for a shared one) by construction. Declares
 * no methods of its own by design: derived queries on seam repositories
 * are rejected by the architecture tests, and extra conditions arrive
 * as {@code Specification} parameters instead.
 */
public interface VersionScopedRepository extends TenantScopedRepository<VersionEntity> {}
