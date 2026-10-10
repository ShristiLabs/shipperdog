package com.shipperdog.api.distribution.internal.repository;

import com.shipperdog.api.distribution.internal.entity.StoreAccountEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Storage for the delegation chain's bindings (V43). A plain {@link
 * JpaRepository}: organization-grain configuration (the environments
 * standing -- explicit organization checks, no isolation seam), one
 * row per provider per level, replaced through rotation rather than
 * addressed per-column.
 */
public interface StoreAccountRepository extends JpaRepository<StoreAccountEntity, UUID> {

    /** The organization's every binding, provider-ordered for the stable chain view. */
    List<StoreAccountEntity> findByOrganizationIdOrderByProviderAsc(UUID organizationId);

    /**
     * The exact-level binding of one provider -- the one row the
     * chain's resolution asks a level for (null parents match only
     * the null-parent row; the unique constraint is the authority).
     */
    Optional<StoreAccountEntity> findByOrganizationIdAndApplicationIdAndEnvironmentIdAndProvider(
            UUID organizationId, UUID applicationId, UUID environmentId, String provider);

    /** One binding by id inside the organization (the concealed-miss guard of the item routes). */
    Optional<StoreAccountEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
