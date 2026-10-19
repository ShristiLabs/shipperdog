package com.shipperdog.api.otabundle.internal.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The device check's configuration (S16-07, issue #169): environment-
 * only per the platform profile — {@code application.yml} reads the
 * {@code OTA_CHECK_POLL_INTERVAL} variable and supplies the
 * compose default.
 *
 * @param pollInterval  the {@code pollIntervalSeconds} the manifest
 *                      carries: how long the SDK waits before its
 *                      next check (D9's check loop; the server owns
 *                      the cadence so an operator can slow a fleet
 *                      without shipping a new binary). A sanity floor
 *                      of one minute refuses a pathological value at
 *                      startup — a poll cadence faster than that is a
 *                      hot loop, never a configuration.
 */
@ConfigurationProperties(prefix = "shipperdog.ota.check")
public record OtaCheckProperties(Duration pollInterval) {

    /** The floor: no check loop faster than one per minute. */
    static final Duration MIN_POLL_INTERVAL = Duration.ofSeconds(60);

    public OtaCheckProperties {
        if (pollInterval == null || pollInterval.compareTo(MIN_POLL_INTERVAL) < 0) {
            throw new IllegalStateException(
                    "shipperdog.ota.check.poll-interval (" + pollInterval + ") must be at least " + MIN_POLL_INTERVAL);
        }
    }

    /** The manifest's field: whole seconds. */
    public long pollIntervalSeconds() {
        return pollInterval.toSeconds();
    }
}
