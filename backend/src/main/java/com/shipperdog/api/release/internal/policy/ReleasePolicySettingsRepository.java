package com.shipperdog.api.release.internal.policy;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Storage for the organization settings rows (V34): at most one row
 * per organization, created lazily by the first edit (absence is the
 * empty set — the default posture).
 */
public interface ReleasePolicySettingsRepository extends JpaRepository<ReleasePolicySettingsEntity, UUID> {

    /** The organization's settings row, when it has ever been edited. */
    Optional<ReleasePolicySettingsEntity> findByOrganizationId(UUID organizationId);
}
