package com.shipperdog.api.artifact.internal.download;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The signed-URL TTL's startup bounds (issue #38, S8-04, the S7-04
 * rule): the URL TTL must sit at or under the decision-input cache
 * TTL (a minted URL must never outlive the revocation staleness
 * backstop) and at or under FR-014's hard 15-minute ceiling — a
 * misconfiguration is a startup fault, never a silent clamp.
 */
class ArtifactDownloadPropertiesTest {

    @Test
    void theDefaultPairingPasses() {
        assertThatCode(() ->
                        ArtifactDownloadService.requireTtlWithinBounds(Duration.ofMinutes(15), Duration.ofMinutes(15)))
                .doesNotThrowAnyException();
    }

    @Test
    void aShorterUrlTtlThanTheCachePasses() {
        assertThatCode(() ->
                        ArtifactDownloadService.requireTtlWithinBounds(Duration.ofMinutes(5), Duration.ofMinutes(15)))
                .doesNotThrowAnyException();
    }

    @Test
    void aUrlTtlBeyondTheCacheTtlFailsFast() {
        assertThatThrownBy(() ->
                        ArtifactDownloadService.requireTtlWithinBounds(Duration.ofMinutes(10), Duration.ofMinutes(5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cache TTL");
    }

    @Test
    void aUrlTtlBeyondTheHardCeilingFailsFastEvenUnderALargeCacheTtl() {
        assertThatThrownBy(() ->
                        ArtifactDownloadService.requireTtlWithinBounds(Duration.ofMinutes(30), Duration.ofHours(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FR-014 ceiling");
    }

    @Test
    void aBlankTtlIsRejectedAtBinding() {
        assertThatThrownBy(() -> new ArtifactDownloadProperties(null, "k"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ArtifactDownloadProperties(Duration.ZERO, "k"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
