package com.shipperdog.api.device.internal.repository;

import com.shipperdog.api.device.internal.entity.DeviceEntity;
import com.shipperdog.api.tenant.TenantScopedRepository;

/**
 * Tenant-scoped access to {@code devices} (V1's table, expanded by
 * V37; issue #48, S11-03): the endpoint-data path for the registration
 * and management surface, filtered to the request's tenant context by
 * construction — a foreign tenant's device is indistinguishable from a
 * missing one (INV-001, ADR-0002). Declares no methods of its own by
 * design — the architecture tests reject derived queries on seam
 * repositories, so every access composes through {@link
 * com.shipperdog.api.tenant.TenantScopedAccess} with {@code
 * Specification} parameters: the listing ({@code findAllScoped}), the
 * duplicate probe ({@code existsScoped}), the addressed row ({@code
 * findScoped}), and the writes ({@code saveScoped}).
 */
public interface DeviceRepository extends TenantScopedRepository<DeviceEntity> {}
