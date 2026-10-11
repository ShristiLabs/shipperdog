package com.shipperdog.api.distribution.internal.google;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.distribution.DistributionCommand;
import com.shipperdog.api.distribution.SecretMaterial;
import org.junit.jupiter.api.Test;

/**
 * The credential model's unit proof (issue #64, S13-04, AC1): the
 * service-account JSON document IS the credential — the mock's own
 * document parses into the complete triple, the delegation wrapper's
 * redaction never touches the value, and a material that is not the
 * document is the incomplete configuration fault the adapter never
 * dials on.
 */
class GooglePlayCredentialsTest {

    @Test
    void theServiceAccountDocumentIsTheCompleteCredential() {
        GooglePlayDeveloperApiMock mock = new GooglePlayDeveloperApiMock();
        try {
            String document = mock.serviceAccountJson();
            GooglePlayCredentials credential = GooglePlayCredentials.of(new DistributionCommand.ResolvedCredential(
                    "google-play", "Org Play", "envelope://x", new SecretMaterial(document)));
            assertThat(credential.complete()).isTrue();
            assertThat(credential.clientEmail()).contains("@");
            assertThat(credential.tokenEndpoint())
                    .startsWith("http://127.0.0.1:")
                    .endsWith("/token");
            // The digest is a stable identity of the document: the same
            // document digests equal, a rotated one (a fresh key) does not.
            assertThat(credential.cacheKey())
                    .isEqualTo(GooglePlayCredentials.of(new DistributionCommand.ResolvedCredential(
                                    "google-play", "Org Play", "envelope://x", new SecretMaterial(document)))
                            .cacheKey());
            assertThat(credential.cacheKey())
                    .isNotEqualTo(GooglePlayCredentials.of(new DistributionCommand.ResolvedCredential(
                                    "google-play",
                                    "Org Play",
                                    "envelope://x",
                                    new SecretMaterial(mock.serviceAccountJson())))
                            .cacheKey());
            assertThat(credential.toString()).contains("<redacted>");
        } finally {
            mock.stop();
        }
    }

    @Test
    void aNonDocumentMaterialIsTheIncompleteConfigurationFault() {
        assertThat(GooglePlayCredentials.of(new DistributionCommand.ResolvedCredential(
                                "google-play", "Org Play", "envelope://x", new SecretMaterial("just-some-material")))
                        .complete())
                .isFalse();
        assertThat(GooglePlayCredentials.of(new DistributionCommand.ResolvedCredential(
                                "google-play",
                                "Org Play",
                                "envelope://x",
                                new SecretMaterial("{\"client_email\": \"a@b.c\"}")))
                        .complete())
                .isFalse();
        assertThat(GooglePlayCredentials.of(null).complete()).isFalse();
    }
}
