package com.shipperdog.api.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shipperdog.api.analytics.internal.telemetry.TelemetryTokenProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The telemetry token's configuration guard (issue #72, S15-01): the
 * constructor-enforced TTL bounds — a telemetry credential must be
 * positive and must never outlive a release's plausible test window
 * (the one-year ceiling), so a misconfigured deployment fails at
 * startup instead of minting tokens that never expire.
 */
class TelemetryTokenPropertiesTest {

    @Test
    void acceptsTheDefaultBetaCycleHorizon() {
        TelemetryTokenProperties properties = new TelemetryTokenProperties(Duration.ofDays(90), "key");
        assertThat(properties.tokenTtl()).isEqualTo(Duration.ofDays(90));
        assertThat(properties.tokenKey()).isEqualTo("key");
    }

    @Test
    void refusesNullZeroAndNegativeTtls() {
        assertThatThrownBy(() -> new TelemetryTokenProperties(null, "key"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TelemetryTokenProperties(Duration.ZERO, "key"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TelemetryTokenProperties(Duration.ofSeconds(-1), "key"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesATtlBeyondTheOneYearCeiling() {
        assertThatThrownBy(
                        () -> new TelemetryTokenProperties(TelemetryTokenProperties.MAX_TOKEN_TTL.plusDays(1), "key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds the ceiling");
    }
}
