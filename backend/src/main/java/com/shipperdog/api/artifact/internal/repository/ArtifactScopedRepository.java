package com.shipperdog.api.artifact.internal.repository;

import com.shipperdog.api.artifact.internal.entity.ArtifactEntity;
import com.shipperdog.api.tenant.TenantScopedRepository;

/**
 * Seam-served access to {@code artifacts} (issue #20, ADR-0002 ninth
 * amendment): reads, listings, and registration compose through the
 * tenant-scoped seam, which filters by the request's bound scope (the
 * owning dimension of the version's application) by construction.
 * Declares no methods of its own by design: derived queries on seam
 * repositories are rejected by the architecture tests, and extra
 * conditions arrive as {@code Specification} parameters instead.
 */
public interface ArtifactScopedRepository extends TenantScopedRepository<ArtifactEntity> {}
