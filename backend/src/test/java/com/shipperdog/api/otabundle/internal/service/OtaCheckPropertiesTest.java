package com.shipperdog.api.otabundle.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The device check's configuration floor (S16-07, issue #169, D9): the
 * server owns the poll cadence, and a cadence faster than one check per
 * minute is a hot loop the startup refuses — never a configuration a
 * fleet ships. The manifest's field is the cadence's whole seconds.
 */
class OtaCheckPropertiesTest {

    @Test
    void pollIntervalBelowTheFloorIsRefusedAtStartup() {
        assertThatThrownBy(() -> new OtaCheckProperties(Duration.ofSeconds(59)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be at least");
        assertThatThrownBy(() -> new OtaCheckProperties(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theFloorItselfAndSteadyStateCadencesAreAccepted() {
        assertThat(new OtaCheckProperties(Duration.ofSeconds(60)).pollIntervalSeconds())
                .isEqualTo(60L);
        assertThat(new OtaCheckProperties(Duration.ofHours(1)).pollIntervalSeconds())
                .isEqualTo(3600L);
    }
}
