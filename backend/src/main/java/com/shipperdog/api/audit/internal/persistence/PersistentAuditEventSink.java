package com.shipperdog.api.audit.internal.persistence;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.internal.entity.AuditEventEntity;
import com.shipperdog.api.audit.internal.repository.AuditEventRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The durable audit sink (issue #6): persists every event the publisher
 * binding republishes. The {@link TransactionalEventListener} with
 * {@code BEFORE_COMMIT} makes the insert part of the caller's transaction
 * — the transactional-outbox behavior the issue's technical notes require
 * (an audited business change and its audit row commit atomically, so a
 * crash can never separate them). When no transaction is active (denials,
 * login flows) the fallback executes the insert immediately; either way
 * persistence is synchronous and comfortably inside the 2 s SLO (PRD 42).
 *
 * <p>The async relay of persisted events to the event bus arrives with
 * S12-01 (M2) and will consume this table as its outbox.
 */
@Component
class PersistentAuditEventSink {

    private final AuditEventRepository repository;

    PersistentAuditEventSink(AuditEventRepository repository) {
        this.repository = repository;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    void persist(AuditEvent event) {
        repository.save(AuditEventEntity.from(event));
    }
}
