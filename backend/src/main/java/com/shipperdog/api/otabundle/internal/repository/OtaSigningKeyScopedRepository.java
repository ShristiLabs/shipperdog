package com.shipperdog.api.otabundle.internal.repository;

import com.shipperdog.api.otabundle.internal.entity.OtaSigningKeyEntity;
import com.shipperdog.api.tenant.TenantScopedRepository;

/**
 * Seam-served access to {@code ota_signing_keys} (S16-03, ADR-0002):
 * the active-key resolution and rotation writes compose through the
 * tenant-scoped seam under the owning application's bound scope.
 * Declares no methods of its own by design (the architecture tests
 * reject derived queries on seam repositories); conditions arrive as
 * {@code Specification} parameters.
 */
public interface OtaSigningKeyScopedRepository extends TenantScopedRepository<OtaSigningKeyEntity> {}
