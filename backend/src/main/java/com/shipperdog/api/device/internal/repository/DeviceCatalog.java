package com.shipperdog.api.device.internal.repository;

import com.shipperdog.api.device.internal.entity.DeviceEntity;
import com.shipperdog.api.tenant.CrossTenantByDesign;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Cross-tenant, by-design fact lookups over {@code devices} (issue #48,
 * S11-03) — the device analog of the tester module's membership
 * catalog: the readers below CANNOT run under the seam because they run
 * without the caller's own tenant context bound to the device's owner.
 *
 * <ul>
 *   <li>The feedback submission link (S11-01/S11-03): the tester
 *       persona's device_id is validated against the caller's OWN
 *       claimed tenant before the submission's tenant scope is
 *       established — an authorization-precondition question exactly
 *       like the membership catalog's is-effectively-active, answered
 *       from the caller's named tenant rather than a bound context.</li>
 *   <li>The feedback detail's device context (AC4): an
 *       already-validated link resolves its display facts from the
 *       release-grain console read, which runs under the APPLICATION's
 *       owning scope — a shared application's organization scope is
 *       not the submitting tenant's scope the device row lives in.</li>
 * </ul>
 *
 * <p>Marked {@link CrossTenantByDesign} so the isolation architecture
 * test accepts it; endpoint data paths use {@link DeviceRepository}
 * instead. It answers its sanctioned question as a PROJECTION — the
 * context tuple the feedback surfaces carry, deliberately WITHOUT the
 * identifier (semi-sensitive; feedback readers span beyond the tenant
 * administrators the identifier is visible to) — never a row, and
 * declares no other operation.
 */
@CrossTenantByDesign
public interface DeviceCatalog extends JpaRepository<DeviceEntity, UUID> {

    /** The device's context facts (no identifier), or empty when no such device exists. */
    @Query("select d.id as id, d.testerId as testerId, d.tenantId as tenantId, d.status as status, "
            + "d.platform as platform, d.model as model, d.osVersion as osVersion "
            + "from DeviceEntity d where d.id = :id")
    Optional<Facts> factsOf(@Param("id") UUID id);

    /** The projection the sanctioned question answers: the feedback surfaces' context tuple. */
    interface Facts {

        UUID getId();

        UUID getTesterId();

        UUID getTenantId();

        String getStatus();

        String getPlatform();

        String getModel();

        String getOsVersion();
    }
}
