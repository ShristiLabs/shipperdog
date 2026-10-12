package com.shipperdog.api.authorization.internal.abac;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * The {@code abac_policies} store (issue #70, S14-04): plain
 * organization-grain reference-data access — the V34 rule-store
 * pattern, no tenant dimension, explicit organization-scoped queries
 * only (never an unscoped listing).
 */
public interface AbacPolicyRepository extends JpaRepository<AbacPolicyEntity, UUID> {

    /** The organization's policies in the defined evaluation order. */
    List<AbacPolicyEntity> findByOrganizationIdOrderByPositionAscNameAsc(UUID organizationId);

    /** One policy of one organization (a foreign policy is the concealed miss). */
    Optional<AbacPolicyEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /** The name-uniqueness probe of the write surface. */
    Optional<AbacPolicyEntity> findByOrganizationIdAndName(UUID organizationId, String name);

    /** Whether the organization holds ANY enabled policy (the layer's zero-cost fast-path probe). */
    @Query("select count(p) > 0 from AbacPolicyEntity p where p.organizationId = ?1 and p.enabled = true")
    boolean existsEnabledByOrganizationId(UUID organizationId);
}
