package com.shipperdog.api.release.internal.repository;

import com.shipperdog.api.release.internal.entity.ReleaseEntity;
import com.shipperdog.api.tenant.TenantScopedRepository;

/**
 * Seam-served access to {@code releases} (issue #55): reads, listings,
 * and creation compose through the tenant-scoped seam, which filters by
 * the request's bound scope (the owning tenant of the application, or
 * the owning organization for a shared one) by construction. Releases
 * are addressed by their own top-level routes, so they carry the
 * owning-dimension snapshot and join the seam — ADR-0002's ninth
 * amendment rule for addressable children. Declares no methods of its
 * own by design: derived queries on seam repositories are rejected by
 * the architecture tests, and extra conditions arrive as
 * {@code Specification} parameters instead.
 */
public interface ReleaseScopedRepository extends TenantScopedRepository<ReleaseEntity> {}
