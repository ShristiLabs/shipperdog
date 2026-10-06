package com.shipperdog.api.tester.internal.service;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.tester.internal.entity.TesterGroupEntity;
import com.shipperdog.api.tester.internal.repository.TesterGroupScopedRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional core of group creation (issue #27), isolated in
 * its own bean per the TesterCreationAttempt discipline (issue #26):
 * callers that must answer a uniqueness race with a precise outcome
 * (creation races answer {@code 409 NAME_TAKEN}; the tenant-creation
 * seeding path wants its rollback contained) keep their own
 * transaction boundaries OUTSIDE this bean, so a lost
 * {@code uq_tester_groups_tenant_name} race rolls back only this
 * attempt and the caller's catch sees a clean exception — never a
 * PostgreSQL-aborted transaction committing into a 500.
 */
@Component
public class TesterGroupWriteAttempt {

    private final TesterGroupScopedRepository groups;
    private final AuditEventPublisher audit;
    private final Clock clock;

    TesterGroupWriteAttempt(TesterGroupScopedRepository groups, AuditEventPublisher audit, Clock clock) {
        this.groups = groups;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Inserts the group and audits — atomically. The insert is flushed
     * inside the guard so a lost name race throws HERE (the issue
     * #13/#26 race discipline; Hibernate defers UUID-keyed inserts
     * otherwise). A duplicate name surfaces as
     * {@link DataIntegrityViolationException} for the caller to
     * translate.
     *
     * @param seeded whether this insert is tenant-creation seeding
     *               (audit detail; the groups themselves are ordinary
     *               editable rows the moment they exist)
     */
    @Transactional
    public TesterGroupEntity attempt(UUID actorId, UUID tenantId, String name, boolean seeded) {
        TesterGroupEntity group = groups.saveScoped(new TesterGroupEntity(tenantId, name));
        groups.flush();
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("name", name);
        if (seeded) {
            detail.put("seeded", "true");
        }
        audit.publish(AuditEvent.builder(AuditEventType.TESTER_GROUP_CREATED)
                .userId(actorId)
                .tenantId(tenantId)
                .occurredAt(Instant.now(clock))
                .success(true)
                .detail(detail)
                .build());
        return group;
    }
}
