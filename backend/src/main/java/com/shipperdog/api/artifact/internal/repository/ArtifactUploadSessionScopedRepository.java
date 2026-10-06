package com.shipperdog.api.artifact.internal.repository;

import com.shipperdog.api.artifact.internal.entity.ArtifactUploadSessionEntity;
import com.shipperdog.api.tenant.TenantScopedRepository;

/**
 * Seam-served access to {@code artifact_upload_sessions} (V15, issue
 * #20): the session of the bound scope, found by id or by its
 * pre-allocated artifact id, and deleted at finalize — the only
 * delete this module ever issues, on a row whose whole purpose is to
 * disappear. Declares no methods of its own (the seam's rule);
 * lookups compose as {@code Specification} parameters.
 */
public interface ArtifactUploadSessionScopedRepository extends TenantScopedRepository<ArtifactUploadSessionEntity> {}
