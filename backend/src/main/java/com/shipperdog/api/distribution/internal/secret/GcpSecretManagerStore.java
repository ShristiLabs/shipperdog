package com.shipperdog.api.distribution.internal.secret;

import com.google.auth.oauth2.GoogleCredentials;
import com.shipperdog.api.distribution.SecretMaterial;
import java.io.IOException;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The GCP Secret Manager store (issue #61, S13-01, PRD 25, ADR-0008):
 * the production posture -- material lives in Secret Manager (encrypted
 * by Cloud KMS at rest, Google's side of the contract), and the
 * platform holds only the reference. Speaks the Secret Manager v1 REST
 * surface through the platform's RestClient convention with
 * application-default credentials (the same ADC the GCS artifact store
 * rides), so no new client library enters the tree.
 *
 * <p>Reference shape: {@code gcp-secret-manager://projects/<p>/secrets/<s>}
 * -- the secret's resource name without a version; resolution always
 * reads the latest version (rotation is a new version under the same
 * reference, the binding's slot doctrine). {@code store} creates the
 * secret idempotently (an existing secret is the rotation path, not a
 * fault) and adds one version per store call.
 */
@Component
@ConditionalOnProperty(name = "shipperdog.secrets.provider", havingValue = "gcp-secret-manager")
class GcpSecretManagerStore implements ChannelSecretStore {

    private static final Logger log = LoggerFactory.getLogger(GcpSecretManagerStore.class);

    /** The store's reference namespace (the store resolves only its own). */
    static final String REFERENCE_PREFIX = "gcp-secret-manager://";

    private static final String DEFAULT_ENDPOINT = "https://secretmanager.googleapis.com";

    private final RestClient http;
    private final String projectId;
    private final String endpoint;

    /** Cached application-default credentials; null when ADC is absent (the store fails closed, loudly logged). */
    private GoogleCredentials credentials;

    GcpSecretManagerStore(
            org.springframework.web.client.RestClient.Builder builder, DistributionSecretProperties properties) {
        this.http = builder.build();
        this.projectId = properties.gcp().projectId();
        this.endpoint = properties.gcp().endpoint() == null
                        || properties.gcp().endpoint().isBlank()
                ? DEFAULT_ENDPOINT
                : properties.gcp().endpoint().trim();
    }

    @Override
    public String store(UUID organizationId, String provider, SecretMaterial material) {
        String secretId = "shipperdog-" + organizationId + "-" + provider;
        String secretName = "projects/" + projectId + "/secrets/" + secretId;
        // Create is idempotent at the store's grain: a 409 (already
        // exists) is the rotation path -- the new version below is the
        // write that matters.
        try {
            http.post()
                    .uri(endpoint + "/v1/projects/" + projectId + "/secrets?secretId=" + secretId)
                    .headers(headers -> headers.setBearerAuth(bearerToken()))
                    .body(Map.of("replication", Map.of("automatic", Map.of())))
                    .retrieve()
                    .toBodilessEntity();
        } catch (org.springframework.web.client.RestClientResponseException conflict) {
            if (conflict.getStatusCode().value() != 409) {
                throw new IllegalStateException("GCP secret create failed: HTTP "
                        + conflict.getStatusCode().value());
            }
            log.debug("secret {} already exists; adding a rotation version", secretId);
        }
        Map<?, ?> version = http.post()
                .uri(endpoint + "/v1/" + secretName + ":addVersion")
                .headers(headers -> headers.setBearerAuth(bearerToken()))
                .body(Map.of(
                        "payload",
                        Map.of(
                                "data",
                                Base64.getEncoder()
                                        .encodeToString(
                                                material.value().getBytes(java.nio.charset.StandardCharsets.UTF_8)))))
                .retrieve()
                .body(Map.class);
        if (version == null || version.get("name") == null) {
            throw new IllegalStateException("GCP addVersion returned no version name");
        }
        return REFERENCE_PREFIX + secretName;
    }

    @Override
    public Optional<SecretMaterial> resolve(String secretReference) {
        if (secretReference == null || !secretReference.startsWith(REFERENCE_PREFIX)) {
            return Optional.empty();
        }
        String secretName = secretReference.substring(REFERENCE_PREFIX.length());
        try {
            Map<?, ?> accessed = http.post()
                    .uri(endpoint + "/v1/" + secretName + ":access")
                    .headers(headers -> headers.setBearerAuth(bearerToken()))
                    .retrieve()
                    .body(Map.class);
            if (accessed == null) {
                return Optional.empty();
            }
            Map<?, ?> payload = (Map<?, ?>) accessed.get("payload");
            if (payload == null || payload.get("data") == null) {
                return Optional.empty();
            }
            return Optional.of(new SecretMaterial(new String(
                    Base64.getDecoder().decode(payload.get("data").toString()),
                    java.nio.charset.StandardCharsets.UTF_8)));
        } catch (org.springframework.web.client.RestClientException e) {
            // Unknown, deleted, or access-denied: the reference does not
            // resolve, and the caller cannot distinguish (by design).
            log.debug("GCP secret reference failed to resolve: {}", e.getMessage());
            return Optional.empty();
        } catch (RuntimeException e) {
            // A broken ADC or token source at RESOLUTION time is the same
            // answer for the caller -- the reference does not resolve --
            // because resolution runs inside the dispatch crossing and a
            // thrown store fault must never take the crossing down with
            // it (ADR-0008, the sandbox doctrine). Binding (store)
            // stays loud: a store that cannot write is a config fault
            // the operator fixes at bind time.
            log.warn("GCP secret reference {} failed to resolve through a store fault: {}", secretName, e.getMessage());
            return Optional.empty();
        }
    }

    /** The ADC bearer token; lazily sourced and refreshed per call (package-private: tests override the source). */
    String bearerToken() {
        try {
            if (credentials == null) {
                credentials = GoogleCredentials.getApplicationDefault();
            }
            credentials.refreshIfExpired();
            return credentials.getAccessToken().getTokenValue();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "GCP application default credentials are unavailable; the gcp-secret-manager store"
                            + " cannot authenticate (configure ADC or use the envelope store)",
                    e);
        }
    }
}
