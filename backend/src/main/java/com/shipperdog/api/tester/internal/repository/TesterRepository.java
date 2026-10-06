package com.shipperdog.api.tester.internal.repository;

import com.shipperdog.api.tester.internal.entity.TesterEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Plain repository: tester records are organization-scoped, not seam-scoped. */
public interface TesterRepository extends JpaRepository<TesterEntity, UUID> {

    Optional<TesterEntity> findByOrganizationIdAndEmail(UUID organizationId, String email);

    Page<TesterEntity> findByOrganizationIdOrderByEmailAsc(UUID organizationId, Pageable pageable);

    Page<TesterEntity> findByOrganizationIdAndStatusOrderByEmailAsc(
            UUID organizationId, TesterEntity.Status status, Pageable pageable);

    /** The user's tester record, if any (login/refresh suspension check). */
    Optional<TesterEntity> findByUserId(UUID userId);
}
