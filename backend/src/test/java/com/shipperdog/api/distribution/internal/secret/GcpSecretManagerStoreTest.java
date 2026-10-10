package com.shipperdog.api.distribution.internal.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.shipperdog.api.distribution.SecretMaterial;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * The GCP Secret Manager store's wire contract (issue #61, S13-01,
 * PRD 25, ADR-0008): the create-then-addVersion write, the 409
 * rotation path, the access read, and the fail-closed resolution --
 * pinned against a MockRestServiceServer so the wire shape is exact
 * without a Google side. The bearer token is overridden (ADC is the
 * production concern, not the wire's).
 */
class GcpSecretManagerStoreTest {

    private static final String ENDPOINT = "http://secretmanager.test";

    private GcpSecretManagerStore store;
    private MockRestServiceServer server;

    /** The test's token override: ADC never runs (the subclass's constant answers). */
    private static final class TestStore extends GcpSecretManagerStore {

        TestStore(org.springframework.web.client.RestClient.Builder builder, DistributionSecretProperties properties) {
            super(builder, properties);
        }

        @Override
        String bearerToken() {
            return "test-token";
        }
    }

    @BeforeEach
    void setUp() {
        org.springframework.web.client.RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        store = new TestStore(
                builder,
                new DistributionSecretProperties(
                        DistributionSecretProperties.Provider.GCP_SECRET_MANAGER,
                        new DistributionSecretProperties.Envelope(""),
                        new DistributionSecretProperties.Gcp("proj-1", ENDPOINT)));
    }

    @Test
    void storeCreatesTheSecretThenAddsAVersionAndReturnsTheReference() {
        UUID org = UUID.randomUUID();
        server.expect(requestTo(ENDPOINT + "/v1/projects/proj-1/secrets?secretId=shipperdog-" + org + "-apple"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.replication.automatic").exists())
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT + "/v1/projects/proj-1/secrets/shipperdog-" + org + "-apple:addVersion"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.payload.data").value(Base64.getEncoder().encodeToString("the-key".getBytes())))
                .andRespond(withSuccess(
                        "{\"name\":\"projects/proj-1/secrets/shipperdog-" + org + "-apple/versions/1\"}",
                        MediaType.APPLICATION_JSON));

        String reference = store.store(org, "apple", new SecretMaterial("the-key"));

        assertThat(reference)
                .isEqualTo(GcpSecretManagerStore.REFERENCE_PREFIX + "projects/proj-1/secrets/shipperdog-" + org
                        + "-apple");
        server.verify();
    }

    @Test
    void anExistingSecretIsTheRotationPathNotAFault() {
        UUID org = UUID.randomUUID();
        server.expect(requestTo(ENDPOINT + "/v1/projects/proj-1/secrets?secretId=shipperdog-" + org + "-apple"))
                .andRespond(withStatus(HttpStatus.CONFLICT));
        server.expect(requestTo(ENDPOINT + "/v1/projects/proj-1/secrets/shipperdog-" + org + "-apple:addVersion"))
                .andRespond(withSuccess(
                        "{\"name\":\"projects/proj-1/secrets/shipperdog-" + org + "-apple/versions/2\"}",
                        MediaType.APPLICATION_JSON));

        String reference = store.store(org, "apple", new SecretMaterial("rotated"));
        assertThat(reference).contains("shipperdog-" + org + "-apple");
        server.verify();
    }

    @Test
    void resolveReadsTheLatestVersionAndAnswersTheMaterial() {
        server.expect(requestTo(ENDPOINT + "/v1/projects/proj-1/secrets/the-secret:access"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"payload\":{\"data\":\"" + Base64.getEncoder().encodeToString("the-material".getBytes())
                                + "\"}}",
                        MediaType.APPLICATION_JSON));

        assertThat(store.resolve(GcpSecretManagerStore.REFERENCE_PREFIX + "projects/proj-1/secrets/the-secret"))
                .hasValueSatisfying(material -> assertThat(material.value()).isEqualTo("the-material"));
    }

    @Test
    void foreignReferencesNeverReachTheWireAndProviderFailuresResolveEmpty() {
        assertThat(store.resolve("local-envelope://something")).isEmpty();
        assertThat(store.resolve(null)).isEmpty();

        server.expect(requestTo(ENDPOINT + "/v1/projects/proj-1/secrets/gone:access"))
                .andRespond(withServerError());
        assertThat(store.resolve(GcpSecretManagerStore.REFERENCE_PREFIX + "projects/proj-1/secrets/gone"))
                .isEmpty();
    }

    @Test
    void aBrokenTokenSourceAtResolutionTimeAnswersEmptyNeverAThrow() {
        // The review fix's own pin: resolve runs inside the dispatch
        // crossing, so a broken ADC at call time must answer "does not
        // resolve" instead of taking the crossing's transaction down.
        org.springframework.web.client.RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer.bindTo(builder).build();
        GcpSecretManagerStore broken =
                new GcpSecretManagerStore(
                        builder,
                        new DistributionSecretProperties(
                                DistributionSecretProperties.Provider.GCP_SECRET_MANAGER,
                                new DistributionSecretProperties.Envelope(""),
                                new DistributionSecretProperties.Gcp("proj-1", ENDPOINT))) {

                    @Override
                    String bearerToken() {
                        throw new IllegalStateException("fixture: ADC is broken");
                    }
                };
        assertThat(broken.resolve(GcpSecretManagerStore.REFERENCE_PREFIX + "projects/proj-1/secrets/any"))
                .isEmpty();
    }
}
