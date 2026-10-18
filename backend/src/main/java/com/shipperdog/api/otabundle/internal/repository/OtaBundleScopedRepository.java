package com.shipperdog.api.otabundle.internal.repository;

import com.shipperdog.api.otabundle.internal.entity.OtaBundleEntity;
import com.shipperdog.api.tenant.TenantScopedRepository;

/**
 * Seam-served access to {@code ota_bundles} (S16-03, ADR-0002): reads,
 * listings, and the finalize-time insert compose through the
 * tenant-scoped seam, which filters by the request's bound scope (the
 * owning dimension of the application) by construction. Declares no
 * methods of its own by design: derived queries on seam repositories
 * are rejected by the architecture tests, and extra conditions arrive
 * as {@code Specification} parameters instead.
 */
public interface OtaBundleScopedRepository extends TenantScopedRepository<OtaBundleEntity> {}
