package com.shipperdog.api.gate.internal.repository;

import com.shipperdog.api.gate.internal.entity.GateDefinitionEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Storage for gate definitions (V31, issue #42, S10-03). A plain
 * {@link JpaRepository} by design — definitions are organization-grain
 * reference data (the {@code approval_workflows} standing): the
 * definition routes check the organization explicitly after the
 * permission evaluation, and the rows carry no tenant dimension for a
 * seam to serve.
 */
public interface GateDefinitionRepository extends JpaRepository<GateDefinitionEntity, UUID> {

    /** The organization's definitions, one page in the listing's order. */
    Page<GateDefinitionEntity> findByOrganizationId(UUID organizationId, Pageable pageable);

    /** The whole organization vocabulary at once — the evaluation's resolution set (one organization is a small catalog). */
    List<GateDefinitionEntity> findAllByOrganizationId(UUID organizationId);

    /** The definition lookup of the update/read routes: the id within the owning organization. */
    Optional<GateDefinitionEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /** The organization-wide definition of a type (the ring_id IS NULL standing). */
    Optional<GateDefinitionEntity> findByOrganizationIdAndGateTypeAndRingIdIsNull(UUID organizationId, String gateType);

    /** The ring-attached definition of a type (the override standing). */
    Optional<GateDefinitionEntity> findByOrganizationIdAndGateTypeAndRingId(
            UUID organizationId, String gateType, UUID ringId);
}
