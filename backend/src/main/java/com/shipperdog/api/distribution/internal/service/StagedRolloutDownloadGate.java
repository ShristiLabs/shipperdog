package com.shipperdog.api.distribution.internal.service;

import com.shipperdog.api.distribution.StagedRolloutAvailability;
import com.shipperdog.api.distribution.internal.entity.StagedRolloutPlanEntity;
import com.shipperdog.api.distribution.internal.repository.StagedRolloutPlanRepository;
import com.shipperdog.api.tester.TenantTesterMembershipAccess;
import com.shipperdog.api.tester.TesterAccess;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The staged rollout's DIRECT arm (issue #66, S13-06, AC3): the
 * download gate behind {@link StagedRolloutAvailability}. A release
 * running a staged plan serves its direct downloads to the REACHED
 * cohort only — a tester whose every tenant membership sits outside
 * the reached set is cut until a wider stage reaches them. The
 * engine's DOWNLOAD decision has ALREADY allowed the caller when the
 * download path asks this seam: the cohort narrows a granted
 * download, it never widens one (the engine owns authorization; the
 * staged rollout owns availability).
 *
 * <p>A read-only point lookup that never throws — an unavailable
 * source answers not-blocking (this layer restricts; it does not
 * authorize), the seam's own contract. Deliberately no cache: the
 * reached set moves with every stage, and the download path's own
 * cadence is the freshness bound.
 */
@Component
class StagedRolloutDownloadGate implements StagedRolloutAvailability {

    private static final Logger log = LoggerFactory.getLogger(StagedRolloutDownloadGate.class);

    private final StagedRolloutPlanRepository plans;
    private final StagedRolloutReader reader;
    private final TesterAccess testers;
    private final TenantTesterMembershipAccess memberships;

    StagedRolloutDownloadGate(
            StagedRolloutPlanRepository plans,
            StagedRolloutReader reader,
            TesterAccess testers,
            TenantTesterMembershipAccess memberships) {
        this.plans = plans;
        this.reader = reader;
        this.testers = testers;
        this.memberships = memberships;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean blocksDownload(UUID versionId, UUID userId) {
        try {
            return blocks(versionId, userId);
        } catch (RuntimeException e) {
            // This layer restricts; it does not authorize: an unavailable
            // probe must not fault the download.
            log.warn("the staged rollout download gate could not resolve version {}: {}", versionId, e.getMessage());
            return false;
        }
    }

    private boolean blocks(UUID versionId, UUID userId) {
        StagedRolloutPlanEntity plan = plans.findByVersionId(versionId).stream()
                .filter(candidate -> candidate.getStatus() == StagedRolloutPlanEntity.Status.RUNNING)
                .findFirst()
                .orElse(null);
        if (plan == null || plan.getCurrentStage() == null) {
            return false;
        }
        UUID testerId = testers.testerIdOfUser(userId).orElse(null);
        if (testerId == null) {
            // Not a tester persona (a console operator's own fetch): the
            // staged cohort is the tester audience's axis, not the
            // console's.
            return false;
        }
        List<UUID> tenants = memberships.tenantIdsOfTester(testerId);
        if (tenants.isEmpty()) {
            // A tester with no active membership sits in no cohort.
            return true;
        }
        Set<UUID> reached = reader.reachedTenantIdsOf(plan.getId(), plan.getCurrentStage());
        return tenants.stream().noneMatch(reached::contains);
    }
}
