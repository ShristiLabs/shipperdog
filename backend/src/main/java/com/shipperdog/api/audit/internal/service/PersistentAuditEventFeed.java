package com.shipperdog.api.audit.internal.service;

import com.shipperdog.api.audit.AuditEventFeed;
import com.shipperdog.api.audit.internal.entity.AuditEventEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@link AuditEventFeed} port's store-side binding (issue #71,
 * S14-05): one criteria-API read per page, the same discipline as
 * {@link AuditQueryService} (the store's read side never grows
 * derived finders on the insert-only repository). The keyset cursor
 * is {@code (occurredAt, id) strictly after} -- two predicates, never
 * an OFFSET, so a batcher's watermark survives bursts of identical
 * instants and backfills never degrade with depth.
 */
@Service
public class PersistentAuditEventFeed implements AuditEventFeed {

    private final EntityManager entityManager;

    public PersistentAuditEventFeed(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** The page ceiling this feed will accept (the batcher's bound, enforced here too). */
    static final int MAX_PAGE = 1000;

    @Override
    @Transactional(readOnly = true)
    public List<AuditFeedRow> pageAfter(UUID organizationId, Instant afterOccurredAt, UUID afterId, int limit) {
        if (limit < 1 || limit > MAX_PAGE) {
            throw new IllegalArgumentException("limit must be 1.." + MAX_PAGE);
        }
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<AuditEventEntity> query = builder.createQuery(AuditEventEntity.class);
        Root<AuditEventEntity> root = query.from(AuditEventEntity.class);
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(builder.equal(root.get("orgId"), organizationId));
        if (afterOccurredAt != null && afterId != null) {
            // The keyset cursor: strictly after the pair, either by
            // instant or by the tiebreak id inside the same instant.
            predicates.add(builder.or(
                    builder.greaterThan(root.get("occurredAt"), afterOccurredAt),
                    builder.and(
                            builder.equal(root.get("occurredAt"), afterOccurredAt),
                            builder.greaterThan(root.get("id"), afterId))));
        } else if (afterOccurredAt != null || afterId != null) {
            // A half cursor is a malformed cursor; page from the
            // beginning rather than guess the missing member.
            throw new IllegalArgumentException("the keyset cursor carries both members or neither");
        }
        query.where(predicates.toArray(new Predicate[0]));
        query.orderBy(builder.asc(root.get("occurredAt")), builder.asc(root.get("id")));
        TypedQuery<AuditEventEntity> typed = entityManager.createQuery(query);
        typed.setMaxResults(limit);
        return typed.getResultList().stream()
                .map(PersistentAuditEventFeed::rowOf)
                .toList();
    }

    private static AuditFeedRow rowOf(AuditEventEntity row) {
        return new AuditFeedRow(
                row.getId(),
                row.getEventType(),
                row.getOccurredAt(),
                row.getUserId(),
                row.getOrgId(),
                row.getTenantId(),
                row.isSuccess(),
                row.getDetail(),
                row.getIpAddress(),
                row.getApplicationId(),
                row.getVersionId(),
                row.getArtifactId(),
                row.getBeforeSnapshot(),
                row.getAfterSnapshot(),
                row.getReason(),
                row.getCorrelationId());
    }
}
