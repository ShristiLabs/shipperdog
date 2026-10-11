package com.shipperdog.api.identity.internal.saml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The Redirect-binding AuthnRequest's own invariants (S14-02): the
 * DEFLATE+b64 roundtrip the IdP reverses, the request-ID entropy, and
 * the refusal of a payload that is not redirect-binding encoded.
 */
class SamlAuthnRequestTest {

    @Test
    void deflateAndBase64RoundtripPreservesTheRequestXml() {
        String xml = SamlAuthnRequest.xml(
                "https://sp.example/metadata", "https://sp.example/acs", "https://idp.example/sso", "_request-1");

        assertThat(SamlAuthnRequest.decodeFromRedirect(SamlAuthnRequest.encodeForRedirect(xml)))
                .isEqualTo(xml);
    }

    @Test
    void requestIdsCarryFreshEntropy() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            String id = SamlAuthnRequest.newRequestId();
            assertThat(id).startsWith("_").hasSize(28);
            ids.add(id);
        }
        assertThat(ids).as("160 bits of entropy never collides at this scale").hasSize(100);
    }

    @Test
    void aNonRedirectEncodedPayloadIsRefused() {
        assertThatThrownBy(() -> SamlAuthnRequest.decodeFromRedirect("not-redirect-encoded"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
