package com.shipperdog.api.release.internal.service;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.release.ReleaseException;
import com.shipperdog.api.release.ReleasePromotedEvent;
import com.shipperdog.api.release.ReleaseTransitionedEvent;
import com.shipperdog.api.release.internal.entity.ReleaseEntity;
import com.shipperdog.api.release.internal.entity.ReleasePromotionEntity;
import com.shipperdog.api.release.internal.entity.ReleaseStatusHistoryEntity;
import com.shipperdog.api.release.internal.entity.ReleaseTargetEntity;
import com.shipperdog.api.release.internal.entity.RingEntity;
import com.shipperdog.api.release.internal.repository.ReleasePromotionRepository;
import com.shipperdog.api.release.internal.repository.ReleaseScopedRepository;
import com.shipperdog.api.release.internal.repository.ReleaseStatusHistoryRepository;
import com.shipperdog.api.release.internal.repository.ReleaseTargetRepository;
import com.shipperdog.api.release.internal.repository.RingRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The promotion engine's transactional writer (issue #44, S10-05): the
 * two commits a promote attempt performs, as separate transactions the
 * untransactional orchestration in {@link PromotionService} calls —
 * the committed hop (ONE atomic transaction: ring CAS, timeline row,
 * audits, events, and the ACTIVE to PROMOTED crossing commit or roll
 * back together, never a partial hop), and the refused attempt's
 * evidence row (its OWN transaction, {@code REQUIRES_NEW}: the 409 the
 * blocked attempt answers rolls the orchestrating call back by
 * design, and PRD 33's "a failed gate blocks promotion with recorded
 * evidence" demands the record survive that rollback — the refused
 * attempt is a governed fact, not a discarded one; the audit events
 * ride the platform's log-published pipeline and survive either way).
 */
@Component
class PromotionWriter {

    private final ReleaseTargetRepository targets;
    private final ReleaseStatusHistoryRepository statusHistory;
    private final ReleasePromotionRepository promotions;
    private final ReleaseScopedRepository releases;
    private final RingRepository rings;
    private final AuditEventPublisher audit;
    private final ApplicationEventPublisher events;

    /**
     * The proxied self: {@code commitHop} records a stale-refusal's
     * evidence through {@code REQUIRES_NEW}, and a same-class call
     * would bypass the proxy and join this transaction — the rollback
     * would discard the evidence. The provider hands the proxy back.
     */
    private final ObjectProvider<PromotionWriter> self;

    PromotionWriter(
            ReleaseTargetRepository targets,
            ReleaseStatusHistoryRepository statusHistory,
            ReleasePromotionRepository promotions,
            ReleaseScopedRepository releases,
            RingRepository rings,
            AuditEventPublisher audit,
            ApplicationEventPublisher events,
            ObjectProvider<PromotionWriter> self) {
        this.targets = targets;
        this.statusHistory = statusHistory;
        this.promotions = promotions;
        this.releases = releases;
        this.rings = rings;
        this.audit = audit;
        this.events = events;
        this.self = self;
    }

    /**
     * Writes the refused attempt's evidence row (its own committed
     * transaction): the hop's two rings, the attempted tenant set, and
     * the COMPLETE blocker list the 409 answered — the append-only
     * timeline's BLOCKED half. {@code REQUIRES_NEW} is the point: the
     * caller's rollback must never discard the evidence.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    ReleasePromotionEntity recordBlocked(
            ReleaseEntity release,
            UUID fromRingId,
            UUID toRingId,
            List<UUID> tenants,
            Map<String, String> blockers,
            UUID actorId,
            Instant occurredAt) {
        ReleasePromotionEntity record = promotions.saveAndFlush(new ReleasePromotionEntity(
                release.getId(),
                fromRingId,
                toRingId,
                tenants,
                ReleasePromotionEntity.Status.BLOCKED,
                new LinkedHashMap<>(blockers),
                actorId,
                occurredAt));
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("releaseRef", release.releaseRef());
        detail.put("toRing", toRingId == null ? "-" : ringCodeOf(toRingId));
        detail.put("blockers", blockers.toString());
        audit.publish(AuditEvent.builder(AuditEventType.RELEASE_PROMOTION_BLOCKED)
                .occurredAt(occurredAt)
                .userId(actorId)
                .orgId(release.getOrganizationId())
                .tenantId(release.getTenantId())
                .applicationId(release.getApplicationId())
                .versionId(release.getVersionId())
                .success(false)
                .detail(detail)
                .build());
        return record;
    }

    /**
     * Commits one governed hop (AC2), ONE transaction: the precheck
     * RE-RUNS first inside it (the transitions route's shape — the
     * evaluation and the compare-and-set serialize in one transaction,
     * so no leg's answer can go stale between the check and the move;
     * a stale refusal records its evidence row through the proxied
     * self — {@code REQUIRES_NEW}, the rollback must not discard it —
     * and answers the same 409). Then the ring compare-and-set moves
     * the move set's rows — grouped by the ring id each row STORES (a
     * shadowed vocabulary can hold the system row beside the
     * organization's own under one effective code) — and the CAS
     * carries the release-status condition with the ring condition:
     * a release that left the promotable statuses between the promote
     * read and this hop (the operator's rollback committing mid-hop)
     * moves nothing, and the committed status re-read inside the
     * transaction answers the invalid-state refusal — never a
     * COMPLETED hop, timeline row, or audit landing after a rollback;
     * a count mismatch with the status still promotable is the
     * precise concurrent-writer conflict (the whole attempt rolls
     * back, never a partial hop); the timeline row records; the
     * {@code RELEASE_PROMOTED} audit and the {@link
     * ReleasePromotedEvent} publish; and when the hop lands the final
     * ring for EVERY target, the release crosses ACTIVE to PROMOTED
     * with the state machine's own ceremony — the history row, the
     * {@code RELEASE_TRANSITIONED} audit, and the transition event,
     * byte-identical to the transitions route's committed body for
     * the same crossing.
     */
    @Transactional
    ReleasePromotionEntity commitHop(
            ReleaseEntity release,
            List<ReleaseTargetEntity> moveSet,
            RingEntity fromRing,
            RingEntity toRing,
            String finalRingCode,
            UUID actorId,
            Instant occurredAt,
            Supplier<Map<String, String>> revalidation) {
        Map<String, String> stale = revalidation.get();
        if (!stale.isEmpty()) {
            // A leg went stale between the precheck read and this
            // transaction: the same 409, its evidence recorded the same
            // way — through the proxied self, so the row commits in its
            // own transaction while this one rolls back.
            self.getObject()
                    .recordBlocked(
                            release,
                            storedFromRingIdOf(moveSet, fromRing),
                            toRing.getId(),
                            moveSet.stream()
                                    .map(ReleaseTargetEntity::getTenantId)
                                    .toList(),
                            stale,
                            actorId,
                            occurredAt);
            throw new ReleaseException(
                    ReleaseException.Code.RELEASE_PROMOTION_BLOCKED,
                    "the promotion to ring '" + toRing.getCode() + "' is blocked",
                    stale);
        }
        Map<UUID, List<ReleaseTargetEntity>> byStoredRing = new LinkedHashMap<>();
        List<UUID> tenants = new ArrayList<>();
        for (ReleaseTargetEntity target : moveSet) {
            byStoredRing
                    .computeIfAbsent(target.getRingId(), ring -> new ArrayList<>())
                    .add(target);
            tenants.add(target.getTenantId());
        }
        for (Map.Entry<UUID, List<ReleaseTargetEntity>> group : byStoredRing.entrySet()) {
            int moved = targets.moveRingIfCurrent(release.getId(), group.getKey(), toRing.getId(), tenants);
            if (moved != group.getValue().size()) {
                // The CAS refused the move: a concurrent writer moved
                // a target first, or the release left the promotable
                // statuses between the promote read and this
                // transaction (the rollback race the CAS's status
                // condition closes). The committed status — re-read
                // fresh inside this transaction, the bulk update
                // cleared the persistence context — says which; the
                // transaction rolls back either way, never a partial
                // hop.
                String committed = releases.findById(release.getId())
                        .map(ReleaseEntity::getStatus)
                        .map(Enum::name)
                        .orElse(null);
                if (!"ACTIVE".equals(committed) && !"PROMOTED".equals(committed)) {
                    throw new ReleaseException(
                            ReleaseException.Code.RELEASE_PROMOTION_INVALID_STATE,
                            "only an ACTIVE release can be promoted ring-to-ring",
                            Map.of(
                                    "current",
                                    committed == null ? "NONE" : committed,
                                    "required",
                                    "ACTIVE (or PROMOTED, whose hops continue widening rings)"));
                }
                throw new ReleaseException(
                        ReleaseException.Code.RELEASE_PROMOTION_CONFLICT,
                        "a concurrent promotion moved the targets first; re-read and retry");
            }
        }
        boolean allAtFinal = finalRingForAllTargetsOf(release, finalRingCode);
        boolean crossedToPromoted = false;
        if (allAtFinal && release.getStatus() == ReleaseEntity.Status.ACTIVE) {
            crossedToPromoted = statusHistory.transitionStatusIfCurrent(release.getId(), "ACTIVE", "PROMOTED") == 1;
        }
        if (crossedToPromoted) {
            // The state machine's own ceremony for the edge this hop
            // performs (PRD 17): timeline row, transition audit,
            // transition event — the transitions route's committed body
            // for the same crossing, verbatim.
            String reason = "promotion to the final ring '" + finalRingCode + "'";
            statusHistory.save(new ReleaseStatusHistoryEntity(
                    release.getId(),
                    ReleaseEntity.Status.ACTIVE,
                    ReleaseEntity.Status.PROMOTED,
                    reason,
                    actorId,
                    occurredAt));
            Map<String, String> crossingDetail = new LinkedHashMap<>();
            crossingDetail.put("releaseRef", release.releaseRef());
            crossingDetail.put("fromStatus", "ACTIVE");
            crossingDetail.put("toStatus", "PROMOTED");
            crossingDetail.put("reason", reason);
            audit.publish(AuditEvent.builder(AuditEventType.RELEASE_TRANSITIONED)
                    .occurredAt(occurredAt)
                    .userId(actorId)
                    .orgId(release.getOrganizationId())
                    .tenantId(release.getTenantId())
                    .applicationId(release.getApplicationId())
                    .versionId(release.getVersionId())
                    .success(true)
                    .detail(crossingDetail)
                    .build());
        }
        ReleasePromotionEntity record = promotions.saveAndFlush(new ReleasePromotionEntity(
                release.getId(),
                storedFromRingIdOf(moveSet, fromRing),
                toRing.getId(),
                tenants,
                ReleasePromotionEntity.Status.COMPLETED,
                null,
                actorId,
                occurredAt));
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("releaseRef", release.releaseRef());
        detail.put("fromRing", fromRing.getCode());
        detail.put("toRing", toRing.getCode());
        detail.put("tenants", tenants.toString());
        detail.put("crossedToPromoted", String.valueOf(crossedToPromoted));
        audit.publish(AuditEvent.builder(AuditEventType.RELEASE_PROMOTED)
                .occurredAt(occurredAt)
                .userId(actorId)
                .orgId(release.getOrganizationId())
                .tenantId(release.getTenantId())
                .applicationId(release.getApplicationId())
                .versionId(release.getVersionId())
                .success(true)
                .detail(detail)
                .build());
        if (crossedToPromoted) {
            String reason = "promotion to the final ring '" + finalRingCode + "'";
            events(release, fromRing, toRing, tenants, actorId, occurredAt, reason);
        } else {
            events(release, fromRing, toRing, tenants, actorId, occurredAt, null);
        }
        return record;
    }

    private void events(
            ReleaseEntity release,
            RingEntity fromRing,
            RingEntity toRing,
            List<UUID> tenants,
            UUID actorId,
            Instant occurredAt,
            String crossingReason) {
        if (crossingReason != null) {
            events.publishEvent(new ReleaseTransitionedEvent(
                    release.getId(),
                    release.releaseRef(),
                    release.getApplicationId(),
                    release.getVersionId(),
                    release.getOrganizationId(),
                    release.getTenantId(),
                    "ACTIVE",
                    "PROMOTED",
                    crossingReason,
                    actorId,
                    occurredAt));
        }
        events.publishEvent(new ReleasePromotedEvent(
                release.getId(),
                release.releaseRef(),
                release.getApplicationId(),
                release.getVersionId(),
                release.getOrganizationId(),
                release.getTenantId(),
                fromRing.getCode(),
                toRing.getCode(),
                tenants,
                crossingReason != null,
                actorId,
                occurredAt));
    }

    /**
     * Whether every target's effective ring is the final code after
     * the hop — the ACTIVE to PROMOTED crossing's condition (a hop
     * that lands SOME targets on the final ring crosses nothing: the
     * release stays ACTIVE until the last target arrives).
     */
    private boolean finalRingForAllTargetsOf(ReleaseEntity release, String finalRingCode) {
        List<ReleaseTargetEntity> after = targets.findByReleaseIdOrderByTenantIdAsc(release.getId());
        if (after.isEmpty()) {
            return false;
        }
        for (ReleaseTargetEntity target : after) {
            RingEntity referenced = rings.findById(target.getRingId()).orElse(null);
            String code = referenced == null ? null : referenced.getCode();
            if (!finalRingCode.equals(code)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The evidence row's from-ring: the move set's first STORED ring id
     * (a shadowed vocabulary can hold two stored ids under one
     * effective code — the record names the row the targets actually
     * sat on; the view renders the code, which shadowing keeps
     * identical by definition).
     */
    private static UUID storedFromRingIdOf(List<ReleaseTargetEntity> moveSet, RingEntity fromRing) {
        return moveSet.isEmpty() ? fromRing.getId() : moveSet.get(0).getRingId();
    }

    private String ringCodeOf(UUID ringId) {
        return ringId == null
                ? null
                : rings.findById(ringId).map(RingEntity::getCode).orElse(null);
    }
}
