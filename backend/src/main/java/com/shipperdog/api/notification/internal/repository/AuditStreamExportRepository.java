package com.shipperdog.api.notification.internal.repository;

import com.shipperdog.api.notification.AuditStreamKind;
import com.shipperdog.api.notification.internal.entity.AuditStreamExportEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The export table's persistence (V53): org-scoped reads and writes
 * (the rings pattern -- reference data of one organization, never
 * another's), the bridge's org gate, and the two machinery reads --
 * the dispatcher's routing query and the batcher's scan.
 */
public interface AuditStreamExportRepository extends JpaRepository<AuditStreamExportEntity, UUID> {

    /** The organization's exports, newest first (the console listing). */
    List<AuditStreamExportEntity> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId, Pageable pageable);

    long countByOrganizationId(UUID organizationId);

    /**
     * One export inside its organization: an export of another
     * organization simply does not answer -- the concealed 404 the
     * service renders.
     */
    Optional<AuditStreamExportEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /** The bridge's org gate: does this organization stream at all? */
    boolean existsByOrganizationIdAndEnabledTrue(UUID organizationId);

    /** The dispatcher's routing read: the organization's live exports (HTTPS and BUCKET alike; the dispatcher filters by kind). */
    List<AuditStreamExportEntity> findByOrganizationIdAndEnabledTrue(UUID organizationId);

    /** The batcher's scan: every enabled bucket export, id order (one deterministic pass per drain). */
    List<AuditStreamExportEntity> findByKindAndEnabledTrueOrderByIdAsc(AuditStreamKind kind);
}
