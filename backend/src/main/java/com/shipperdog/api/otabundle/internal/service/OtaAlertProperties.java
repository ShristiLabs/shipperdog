package com.shipperdog.api.otabundle.internal.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The OTA alert evaluation's knobs (S16-11, issue #173): the pass's
 * schedule and switch. Every value is environment-configurable with a
 * safe default, the platform's configuration rule.
 *
 * @param enabled whether the evaluation pass runs (default true;
 *     docker compose and production run it, tests drive the pass
 *     through the service instead of the clock). The object type
 *     carries the binder's null before the default applies.
 * @param cron the schedule (spring cron; default every five minutes
 *     -- the windows are minutes-grained, so a pass each five
 *     observes them at worst five late)
 */
@ConfigurationProperties(prefix = "shipperdog.ota.alerts")
public record OtaAlertProperties(Boolean enabled, String cron) {

    public OtaAlertProperties {
        if (enabled == null) {
            enabled = true;
        }
        if (cron == null || cron.isBlank()) {
            cron = "0 */5 * * * *";
        }
    }

    /** The switch (primitive shape; the default is already applied). */
    public boolean isEnabled() {
        return enabled;
    }
}
