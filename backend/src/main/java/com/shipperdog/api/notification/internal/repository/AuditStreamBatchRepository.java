package com.shipperdog.api.notification.internal.repository;

import com.shipperdog.api.notification.internal.entity.AuditStreamBatchEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The bucket leg's batch-history persistence (V53): the per-export
 * completeness read. Rows are append-only -- the batcher writes one
 * per committed batch and nothing updates them.
 */
public interface AuditStreamBatchRepository extends JpaRepository<AuditStreamBatchEntity, UUID> {

    /** The export's batches, newest first (the console's batch history and completeness view). */
    List<AuditStreamBatchEntity> findByExportIdOrderBySeqDesc(UUID exportId, Pageable pageable);

    /** The export's batch count (the status view's rollup). */
    long countByExportId(UUID exportId);

    /** The completeness rollup: the export's total exported rows (the sum the status view serves). */
    @Query("select coalesce(sum(b.eventCount), 0) from AuditStreamBatchEntity b where b.exportId = :exportId")
    long sumEventCountByExportId(@Param("exportId") UUID exportId);
}
