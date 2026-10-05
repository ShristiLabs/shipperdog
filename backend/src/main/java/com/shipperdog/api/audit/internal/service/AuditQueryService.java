package com.shipperdog.api.audit.internal.service;

import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.audit.internal.entity.AuditEventEntity;
import com.shipperdog.api.config.CursorPageable;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Read side of the audit framework (issue #6): the admin query behind
 * {@code GET /api/v1/audit}. Builds its filter predicates with the
 * criteria API so absent filters simply add no predicate (a static query
 * would need untyped null binds PostgreSQL rejects). Read-only; the write
 * side is the publisher binding plus the persistent sink, which this
 * service never touches.
 */
@Service
public class AuditQueryService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EntityManager entityManager;

    public AuditQueryService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Conjunctive filters; every parameter optional. {@code from} and
     * {@code to} bound {@code occurredAt} inclusively. Paged by opaque
     * cursor offsets (issue #8); the limit bounds are validated upstream by
     * {@code ListQuery}.
     */
    @Transactional(readOnly = true)
    public Page<AuditEventView> search(
            UUID actorId,
            UUID organizationId,
            UUID tenantId,
            AuditEventType eventType,
            UUID applicationId,
            UUID versionId,
            UUID artifactId,
            Instant from,
            Instant to,
            long offset,
            int limit) {
        Pageable pageable = new CursorPageable((int) offset, limit);

        CriteriaBuilder builder = entityManager.getCriteriaBuilder();

        CriteriaQuery<AuditEventEntity> query = builder.createQuery(AuditEventEntity.class);
        Root<AuditEventEntity> root = query.from(AuditEventEntity.class);
        query.where(filters(
                builder,
                root,
                actorId,
                organizationId,
                tenantId,
                eventType,
                applicationId,
                versionId,
                artifactId,
                from,
                to));
        query.orderBy(builder.desc(root.get("occurredAt")), builder.desc(root.get("id")));
        TypedQuery<AuditEventEntity> typed = entityManager.createQuery(query);
        typed.setFirstResult((int) pageable.getOffset());
        typed.setMaxResults(pageable.getPageSize());
        List<AuditEventEntity> content = typed.getResultList();

        CriteriaQuery<Long> countQuery = builder.createQuery(Long.class);
        Root<AuditEventEntity> countRoot = countQuery.from(AuditEventEntity.class);
        countQuery.select(builder.count(countRoot));
        countQuery.where(filters(
                builder,
                countRoot,
                actorId,
                organizationId,
                tenantId,
                eventType,
                applicationId,
                versionId,
                artifactId,
                from,
                to));
        Long total = entityManager.createQuery(countQuery).getSingleResult();

        return new PageImpl<>(content.stream().map(AuditQueryService::toView).toList(), pageable, total);
    }

    private static Predicate[] filters(
            CriteriaBuilder builder,
            Root<AuditEventEntity> root,
            UUID actorId,
            UUID organizationId,
            UUID tenantId,
            AuditEventType eventType,
            UUID applicationId,
            UUID versionId,
            UUID artifactId,
            Instant from,
            Instant to) {
        List<Predicate> predicates = new ArrayList<>();
        if (actorId != null) {
            predicates.add(builder.equal(root.get("userId"), actorId));
        }
        if (organizationId != null) {
            predicates.add(builder.equal(root.get("orgId"), organizationId));
        }
        if (tenantId != null) {
            predicates.add(builder.equal(root.get("tenantId"), tenantId));
        }
        if (eventType != null) {
            predicates.add(builder.equal(root.get("eventType"), eventType));
        }
        if (applicationId != null) {
            predicates.add(builder.equal(root.get("applicationId"), applicationId));
        }
        if (versionId != null) {
            predicates.add(builder.equal(root.get("versionId"), versionId));
        }
        if (artifactId != null) {
            predicates.add(builder.equal(root.get("artifactId"), artifactId));
        }
        if (from != null) {
            predicates.add(builder.greaterThanOrEqualTo(root.get("occurredAt"), from));
        }
        if (to != null) {
            predicates.add(builder.lessThanOrEqualTo(root.get("occurredAt"), to));
        }
        return predicates.toArray(new Predicate[0]);
    }

    private static AuditEventView toView(AuditEventEntity entity) {
        return new AuditEventView(
                entity.getId(),
                entity.getEventType(),
                entity.getOccurredAt(),
                entity.getCreatedAt(),
                entity.getUserId(),
                entity.getOrgId(),
                entity.getTenantId(),
                entity.getApplicationId(),
                entity.getVersionId(),
                entity.getArtifactId(),
                entity.getIpAddress(),
                entity.getCorrelationId(),
                entity.getReason(),
                entity.isSuccess(),
                parse(entity.getDetail()),
                parse(entity.getBeforeSnapshot()),
                parse(entity.getAfterSnapshot()));
    }

    /** Stored JSON columns surface as raw JSON in the response body. */
    private static JsonNode parse(String json) {
        return json == null ? null : MAPPER.readTree(json);
    }

    /** The admin-facing projection of one immutable audit record. */
    public record AuditEventView(
            UUID id,
            AuditEventType eventType,
            Instant occurredAt,
            Instant createdAt,
            UUID userId,
            UUID orgId,
            UUID tenantId,
            UUID applicationId,
            UUID versionId,
            UUID artifactId,
            String ipAddress,
            String correlationId,
            String reason,
            boolean success,
            JsonNode detail,
            JsonNode before,
            JsonNode after) {}
}
