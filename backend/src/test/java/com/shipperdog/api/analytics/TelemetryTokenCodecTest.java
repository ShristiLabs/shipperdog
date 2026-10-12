package com.shipperdog.api.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.analytics.internal.telemetry.TelemetryTokenProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The telemetry token codec's own contract (issue #72, S15-01): the
 * mint/parse round trip, the tamper and expiry refusals, the optional
 * device binding, and the wrong-key rejection — every failure the
 * uniform-401 doctrine of the authentication filter rests on.
 */
class TelemetryTokenCodecTest {

    private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");

    private final TelemetryTokenCodec codec = new TelemetryTokenCodec(
            new TelemetryTokenProperties(Duration.ofDays(90), "test-telemetry-key"), Clock.fixed(NOW, ZoneOffset.UTC));

    private final TelemetryTokenCodec otherKey = new TelemetryTokenCodec(
            new TelemetryTokenProperties(Duration.ofDays(90), "a-different-key"), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void mintParseRoundTripCarriesEveryBinding() {
        UUID tenant = UUID.randomUUID();
        UUID tester = UUID.randomUUID();
        UUID release = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        String token = codec.mint(
                new TelemetryTokenCodec.Grant(tenant, tester, release, device, NOW.plus(Duration.ofDays(90))));

        assertThat(token).startsWith("sdt1.");
        TelemetryTokenPrincipal parsed = codec.parse(token).orElseThrow();
        assertThat(parsed.tenantId()).isEqualTo(tenant);
        assertThat(parsed.testerUserId()).isEqualTo(tester);
        assertThat(parsed.releaseId()).isEqualTo(release);
        assertThat(parsed.deviceId()).isEqualTo(device);
        assertThat(parsed.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(90)));
    }

    @Test
    void deviceBindingIsOptionalAndRoundTripsNull() {
        String token = mintDeviceless(codec);
        assertThat(codec.parse(token))
                .hasValueSatisfying(parsed -> assertThat(parsed.deviceId()).isNull());
    }

    @Test
    void tamperedPayloadAndSignatureParseToNothing() {
        String token = mintDeviceless(codec);
        String tampered = token.substring(0, token.length() - 3) + "aaa";
        assertThat(codec.parse(tampered)).isEmpty();
        assertThat(codec.parse("sdt1.not-a-token")).isEmpty();
        assertThat(codec.parse("sdt1.")).isEmpty();
        assertThat(codec.parse(null)).isEmpty();
        assertThat(codec.parse("sdp_some_other_credential")).isEmpty();
    }

    @Test
    void expiredTokensParseToNothing() {
        String expired = codec.mint(new TelemetryTokenCodec.Grant(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, NOW.minusSeconds(1)));
        assertThat(codec.parse(expired)).isEmpty();
    }

    @Test
    void aDifferentKeyNeverValidatesAnotherDeploymentToken() {
        String foreign = mintDeviceless(otherKey);
        assertThat(codec.parse(foreign)).isEmpty();
    }

    private static String mintDeviceless(TelemetryTokenCodec codec) {
        return codec.mint(codec.grantForNow(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null));
    }
}
