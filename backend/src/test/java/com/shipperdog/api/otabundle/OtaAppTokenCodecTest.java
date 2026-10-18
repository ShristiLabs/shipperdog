package com.shipperdog.api.otabundle;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.otabundle.internal.service.OtaAppTokenProperties;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The OTA app-token codec's own contract (issue #166, S16-04,
 * ADR-0010 D3), mirroring the telemetry token codec's suite: the
 * mint/parse round trip, the tamper refusals, the wrong-key
 * rejection, and the registry-identity property — two tokens for the
 * same application are always distinct material, because the row id
 * rides the MAC-covered payload (a codec over org+app alone would
 * collapse the registry's unique token_hash into one row).
 */
class OtaAppTokenCodecTest {

    private final OtaAppTokenCodec codec = new OtaAppTokenCodec(new OtaAppTokenProperties("test-ota-app-token-key"));

    private final OtaAppTokenCodec otherKey = new OtaAppTokenCodec(new OtaAppTokenProperties("a-different-key"));

    @Test
    void mintParseRoundTripCarriesEveryBinding() {
        UUID organization = UUID.randomUUID();
        UUID application = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        String token = codec.mint(organization, application, tokenId);

        assertThat(token).startsWith("sdc1.");
        OtaAppTokenCodec.Claims parsed = codec.parse(token).orElseThrow();
        assertThat(parsed.organizationId()).isEqualTo(organization);
        assertThat(parsed.applicationId()).isEqualTo(application);
        assertThat(parsed.tokenId()).isEqualTo(tokenId);
    }

    @Test
    void tamperedPayloadAndSignatureParseToNothing() {
        String token = mintOne(codec);
        String tampered = token.substring(0, token.length() - 3) + "aaa";
        assertThat(codec.parse(tampered)).isEmpty();
        assertThat(codec.parse("sdc1.not-a-token")).isEmpty();
        assertThat(codec.parse("sdc1.")).isEmpty();
        assertThat(codec.parse("sdc1.onlyonepart")).isEmpty();
        assertThat(codec.parse(null)).isEmpty();
        assertThat(codec.parse("sdp_some_other_credential")).isEmpty();
        assertThat(codec.parse("sdt1.aGk.aGk")).isEmpty();
    }

    @Test
    void aDifferentKeyNeverValidatesAnotherDeploymentToken() {
        String foreign = mintOne(otherKey);
        assertThat(codec.parse(foreign)).isEmpty();
    }

    @Test
    void twoMintsOfTheSameApplicationAreDistinctMaterial() {
        UUID organization = UUID.randomUUID();
        UUID application = UUID.randomUUID();
        String first = codec.mint(organization, application, UUID.randomUUID());
        String second = codec.mint(organization, application, UUID.randomUUID());
        assertThat(first).isNotEqualTo(second);
        assertThat(codec.parse(first)).isPresent();
        assertThat(codec.parse(second)).isPresent();
    }

    private static String mintOne(OtaAppTokenCodec codec) {
        return codec.mint(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }
}
