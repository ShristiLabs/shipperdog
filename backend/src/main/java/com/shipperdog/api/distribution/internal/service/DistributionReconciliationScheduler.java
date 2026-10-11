package com.shipperdog.api.distribution.internal.service;

import com.shipperdog.api.distribution.internal.config.DistributionProperties;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The reconciliation pass's clock (issue #62, S13-02, AC5): the
 * scheduled half of store reconciliation, refreshing the records
 * still walking toward their provider answer (PENDING/DISTRIBUTING
 * with an external id) through the adapters' status observations.
 * The rollup precedent holds: each record's refresh is idempotent
 * (the provider's answer overwrites the record forward), so a crashed
 * pass costs nothing but delay.
 *
 * <p><strong>Off by default.</strong> A self-hosted stack must never
 * wake up dialing Apple: the pass runs only where
 * {@code shipperdog.distribution.reconciliation.enabled=true} says a
 * store connector is actually in service ({@code
 * DISTRIBUTION_RECONCILIATION__ENABLED} in the deployment env). The
 * release dashboard's explicit refresh route serves the same refresh
 * everywhere regardless (AC4); this pass is the unattended cadence.
 * Failures log at WARN and move on — one record's provider fault is
 * never the pass's.
 */
@Component
public class DistributionReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(DistributionReconciliationScheduler.class);

    /** The per-pass record cap: one pass's blast radius against a slow provider. */
    static final int PASS_LIMIT = 100;

    private final DistributionDispatchService dispatch;
    private final DistributionProperties properties;

    public DistributionReconciliationScheduler(
            DistributionDispatchService dispatch, DistributionProperties properties) {
        this.dispatch = dispatch;
        this.properties = properties;
    }

    /** The unattended pass; the property guard is the opt-in. */
    @Scheduled(fixedDelayString = "${shipperdog.distribution.reconciliation.fixed-delay:PT15M}")
    void refreshWalkingRecords() {
        if (!properties.reconciliation().enabled()) {
            return;
        }
        List<com.shipperdog.api.distribution.internal.entity.ChannelDistributionEntity> due =
                dispatch.refreshableRecords(PASS_LIMIT);
        for (var record : due) {
            try {
                dispatch.refresh(record.getOrganizationId(), record.getId(), null);
            } catch (RuntimeException e) {
                log.warn(
                        "record {} refresh failed on channel {}: {}",
                        record.getId(),
                        record.getChannel(),
                        e.getMessage());
            }
        }
    }
}
