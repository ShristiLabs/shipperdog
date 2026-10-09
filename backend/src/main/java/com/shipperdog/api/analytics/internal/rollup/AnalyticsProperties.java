package com.shipperdog.api.analytics.internal.rollup;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The analytics pipeline's knobs (issue #49, S11-04): the hourly
 * rollup job's schedule and lookback, and the query windows' bounds.
 * Every value is environment-configurable with a safe default, the
 * platform's configuration rule.
 *
 * @param rollup the hourly job's schedule, switch, and lookback
 * @param maxSeriesWindow the widest window a time-series query may
 *     span (default 366 days; anything wider is refused, never
 *     silently clamped — an unbounded scan is the thing the bound
 *     exists to prevent)
 * @param maxHourlySpan the widest span the hourly grain serves
 *     (default 31 days; wider windows must use the daily grain)
 */
@ConfigurationProperties(prefix = "shipperdog.analytics")
public record AnalyticsProperties(Rollup rollup, Duration maxSeriesWindow, Duration maxHourlySpan) {

    public AnalyticsProperties {
        if (rollup == null) {
            rollup = new Rollup(null, null, null);
        }
        if (maxSeriesWindow == null || maxSeriesWindow.isNegative() || maxSeriesWindow.isZero()) {
            maxSeriesWindow = Duration.ofDays(366);
        }
        if (maxHourlySpan == null || maxHourlySpan.isNegative() || maxHourlySpan.isZero()) {
            maxHourlySpan = Duration.ofDays(31);
        }
    }

    /**
     * @param enabled whether the hourly rollup job runs (default
     *     true; docker compose and production run it, tests drive the
     *     computation through the trigger route instead of the clock).
     *     The object type carries the binder's null before the
     *     default applies.
     * @param cron the hourly schedule (spring cron; default on the
     *     hour)
     * @param lookback how far back each run recomputes from source —
     *     the late-event healing window (default 24 hours)
     */
    public record Rollup(Boolean enabled, String cron, Duration lookback) {

        public Rollup {
            if (enabled == null) {
                enabled = true;
            }
            if (cron == null || cron.isBlank()) {
                cron = "0 0 * * * *";
            }
            if (lookback == null || lookback.isNegative() || lookback.isZero()) {
                lookback = Duration.ofHours(24);
            }
        }

        /** The switch (primitive shape; the default is already applied). */
        public boolean isEnabled() {
            return enabled;
        }
    }
}
