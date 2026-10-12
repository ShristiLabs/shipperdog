package com.shipperdog.api.notification.internal.bus;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The SIEM audit stream's knobs (issue #71, S14-05): the bucket
 * batcher's cadence and bound, riding the notification module's
 * property space ({@code shipperdog.notifications.audit-stream.*} in
 * application.yml, {@code NOTIFICATIONS_AUDIT_STREAM_*} in the
 * deployment env). The HTTPS leg shares the standing relay and
 * delivery knobs ({@link NotificationProperties}) -- the stream is
 * the pipeline's passenger, not a second scheduler.
 *
 * @param batchEnabled   whether the scheduled bucket batcher runs
 *                       (tests drive the bean directly; the property
 *                       keeps a node from racing a manual pass)
 * @param batchFixedDelay the batcher's cadence
 * @param batchMaxRows   rows per batch pass -- the bound that keeps
 *                       every pass finite (AC3's bounded buffering on
 *                       the bucket leg; a deeper backlog drains over
 *                       successive passes, never one unbounded read)
 */
@ConfigurationProperties(prefix = "shipperdog.notifications.audit-stream")
public record AuditStreamProperties(
        @DefaultValue("true") boolean batchEnabled,
        @DefaultValue("PT60S") Duration batchFixedDelay,
        @DefaultValue("500") int batchMaxRows) {

    /** The pass ceiling this configuration admits (the feed's own bound is 1000; a larger setting is refused at bind time by use). */
    public int boundedBatchMaxRows() {
        return Math.min(Math.max(batchMaxRows, 1), 1000);
    }
}
