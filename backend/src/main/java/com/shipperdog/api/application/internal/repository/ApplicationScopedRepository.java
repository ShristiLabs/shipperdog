package com.shipperdog.api.application.internal.repository;

import com.shipperdog.api.application.internal.entity.ApplicationEntity;
import com.shipperdog.api.tenant.TenantScopedRepository;

/**
 * Tenant-scoped read access to {@code applications} (issue #11): the
 * tenant-owned listing composes through the seam, which filters by the
 * request's tenant context by construction — organization-owned rows
 * (null tenant) never match a context. Declares no methods of its own
 * by design: derived queries on seam repositories are rejected by the
 * architecture tests, and the application write surface (creation,
 * ownership assignment, metadata) arrives with E3 (S3-01/S3-02) and
 * will compose through {@link com.shipperdog.api.tenant.TenantScopedAccess}
 * the same way.
 */
public interface ApplicationScopedRepository extends TenantScopedRepository<ApplicationEntity> {}
