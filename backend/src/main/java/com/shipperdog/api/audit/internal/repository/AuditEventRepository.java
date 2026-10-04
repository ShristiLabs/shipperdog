package com.shipperdog.api.audit.internal.repository;

import com.shipperdog.api.audit.internal.entity.AuditEventEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/**
 * The audit store's only persistence surface (issue #6, acceptance
 * criterion 2): INSERT, nothing else. Extending the plain {@link Repository}
 * marker (not {@code JpaRepository} or {@code CrudRepository}) is
 * deliberate — no {@code delete*}, {@code update}, or flush methods exist
 * on this type, so no code path can mutate audit history through Spring
 * Data. The read side ({@code GET /api/v1/audit}) queries through the
 * criteria API in {@code AuditQueryService} rather than derived finder
 * methods, keeping this interface insert-only. The JPA entity
 * additionally rejects update/remove callbacks, and the migration
 * documents the production REVOKE as deployment hardening.
 */
public interface AuditEventRepository extends Repository<AuditEventEntity, UUID> {

    AuditEventEntity save(AuditEventEntity entity);

    List<AuditEventEntity> saveAll(Iterable<AuditEventEntity> entities);
}
