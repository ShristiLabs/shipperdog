package com.shipperdog.api.otabundle.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The alert evaluation pass's configuration defaults (S16-11, issue
 * #173): the binder's nulls fall to the five-minute cadence with the
 * pass on -- tests and CI drive the pass through the service with the
 * switch off, the rollup-scheduler pattern.
 */
class OtaAlertPropertiesTest {

    @Test
    void nullsFallToTheFiveMinuteCadenceWithThePassOn() {
        OtaAlertProperties properties = new OtaAlertProperties(null, null);
        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.cron()).isEqualTo("0 */5 * * * *");
        assertThat(new OtaAlertProperties(Boolean.FALSE, "  ").isEnabled()).isFalse();
        assertThat(new OtaAlertProperties(true, "0 */10 * * * *").cron()).isEqualTo("0 */10 * * * *");
    }
}
