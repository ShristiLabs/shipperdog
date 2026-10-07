package com.shipperdog.api.workflow.internal.repository;

import com.shipperdog.api.workflow.internal.entity.ApprovalWorkflowEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Storage for approval workflow definitions (V28, issue #40, S10-01).
 * A plain {@link JpaRepository} by design — definitions are
 * organization-grain reference data (the environments standing): the
 * definition routes check the organization explicitly after the
 * permission evaluation, and the rows carry no tenant dimension for a
 * seam to serve.
 */
public interface ApprovalWorkflowRepository extends JpaRepository<ApprovalWorkflowEntity, UUID> {

    /** The organization's definitions, one page in the listing's order. */
    Page<ApprovalWorkflowEntity> findByOrganizationId(UUID organizationId, Pageable pageable);

    /** The definition lookup of the environment binding: the name within the owning organization's vocabulary. */
    Optional<ApprovalWorkflowEntity> findByOrganizationIdAndName(UUID organizationId, String name);

    /** The ring attachment lookup: at most one definition per ring per organization (V28's unique guard). */
    Optional<ApprovalWorkflowEntity> findByOrganizationIdAndRingId(UUID organizationId, UUID ringId);

    /** The detail lookup. */
    Optional<ApprovalWorkflowEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
