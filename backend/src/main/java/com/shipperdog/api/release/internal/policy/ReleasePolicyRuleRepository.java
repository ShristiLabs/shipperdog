package com.shipperdog.api.release.internal.policy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Storage for the rule rows (V34). A plain {@link JpaRepository} by
 * design — rules are organization-grain reference data (the rings
 * pattern): the organization dimension only, no tenant dimension for
 * the seam's predicate to filter by, every question an explicit
 * organization-scoped query.
 */
public interface ReleasePolicyRuleRepository extends JpaRepository<ReleasePolicyRuleEntity, UUID> {

    /**
     * The organization's rules in the DEFINED evaluation order (AC5):
     * position ascending, name ascending — a total order the engine
     * walks and the log records.
     */
    List<ReleasePolicyRuleEntity> findByOrganizationIdOrderByPositionAscNameAsc(UUID organizationId);

    /** The organization's rule by name (the unique constraint's pre-check; the lost-race translation answers 409). */
    Optional<ReleasePolicyRuleEntity> findByOrganizationIdAndName(UUID organizationId, String name);

    /** The organization's rule by id: the writable-set membership check (a miss conceals like unknown). */
    Optional<ReleasePolicyRuleEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
