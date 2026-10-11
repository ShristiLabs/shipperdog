package com.shipperdog.api.distribution.internal.apple;

import com.shipperdog.api.config.MetricNames;
import com.shipperdog.api.distribution.AdapterException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/**
 * The App Store Connect API client (S13-02, #62, FR-020): the thin
 * REST conversation the TestFlight connector runs — app lookup by
 * bundle id, build lookup by the artifact's own version facts, beta
 * groups (list, create, membership), build-to-group assignment, and
 * the build-beta-detail observation. Every request carries the ES256
 * bearer token ({@link AppleJwtSigner}); every failure exits through
 * the adapter taxonomy — 401/403 AUTHENTICATION (retrying a rejected
 * credential is a lockout generator), 429 RATE_LIMITED (counted, the
 * quota telemetry of AC6, beside the sandbox's retried backoff), 5xx
 * and transport faults TRANSIENT, the remaining 4xx PERMANENT.
 *
 * <p>Wire shapes are Apple's JSON:API conventions — {@code data}
 * arrays, {@code attributes} objects, relationship bodies addressed
 * by {@code type}. Lists page through {@code links.next}; the client
 * follows until the server stops offering one. The class is
 * transport-only: no platform tables, no policy — the adapter and the
 * group service decide what a call's answer MEANS.
 */
@Component
public class AppStoreConnectClient {

    private static final Logger log = LoggerFactory.getLogger(AppStoreConnectClient.class);

    private static final String TYPE_APPS = "apps";
    private static final String TYPE_BUILDS = "builds";
    private static final String TYPE_BETA_GROUPS = "betaGroups";
    private static final String TYPE_BETA_TESTERS = "betaTesters";
    private static final int PAGE_LIMIT = 200;

    private final RestClient http;
    private final AppleJwtSigner signer;
    private final MeterRegistry meters;

    public AppStoreConnectClient(
            com.shipperdog.api.distribution.internal.config.DistributionProperties properties,
            MeterRegistry meters,
            java.time.Clock clock) {
        var apple = properties.apple();
        // The webhook-delivery precedent: build the client statically
        // with a plain request factory (no context builder bean exists
        // for module clients by design).
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) apple.connectTimeout().toMillis());
        factory.setReadTimeout((int) apple.readTimeout().toMillis());
        this.http = RestClient.builder()
                .baseUrl(apple.baseUrl())
                .requestFactory(factory)
                .build();
        this.signer = new AppleJwtSigner(clock);
        this.meters = meters;
    }

    /** The ASC app of a bundle id (application lookup, FR-020): the id builds and groups hang off. */
    public Optional<String> appIdOfBundleId(AppleCredentials credential, String bundleId) {
        JsonNode page = get(credential, "/v1/apps?filter[bundleId]={bundleId}&limit=1", bundleId);
        JsonNode data = page.path("data");
        if (!data.isArray() || data.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(data.get(0).path("id").asString());
    }

    /**
     * The ASC build of an app by the artifact's own facts: the
     * uploaded binary's build number (ASC's {@code filter[version]},
     * the {@code CFBundleVersion} string). The connector distributes
     * EXISTING builds — CI or Xcode Cloud uploaded the artifact to
     * ASC, and this lookup binds the ShipperDog artifact to its ASC
     * peer. Empty when no such build exists yet.
     */
    public Optional<BetaBuild> buildOf(AppleCredentials credential, String appId, long buildNumber) {
        JsonNode page = get(
                credential,
                "/v1/builds?filter[app]={appId}&filter[version]={version}&limit=2",
                appId,
                Long.toString(buildNumber));
        JsonNode data = page.path("data");
        if (!data.isArray() || data.isEmpty()) {
            return Optional.empty();
        }
        JsonNode build = data.get(0);
        return Optional.of(new BetaBuild(
                build.path("id").asString(),
                build.path("attributes").path("version").asString(),
                build.path("attributes").path("processingState").asString(null)));
    }

    /** The build's beta states (the beta-review observation, FR-020): internal and external. */
    public BetaStates betaStatesOf(AppleCredentials credential, String buildId) {
        JsonNode detail = get(credential, "/v1/builds/{buildId}/buildBetaDetail", buildId);
        JsonNode attributes = detail.path("data").path("attributes");
        return new BetaStates(
                attributes.path("internalBuildState").asString(null),
                attributes.path("externalBuildState").asString(null));
    }

    /** The app's external beta groups (TestFlight groups), paged; internal groups are not mapped (sync-only note). */
    public List<BetaGroup> betaGroupsOf(AppleCredentials credential, String appId) {
        List<BetaGroup> groups = new ArrayList<>();
        String uri = "/v1/betaGroups?filter[app]=" + appId + "&limit=" + PAGE_LIMIT;
        while (uri != null) {
            JsonNode page = get(credential, uri);
            for (JsonNode group : page.path("data")) {
                JsonNode attributes = group.path("attributes");
                groups.add(new BetaGroup(
                        group.path("id").asString(),
                        attributes.path("name").asString(null),
                        attributes.path("isInternalGroup").asBoolean(false)));
            }
            uri = nextOf(page);
        }
        return groups;
    }

    /** Creates an external beta group (the create-on-demand option, AC2); answers the created group. */
    public BetaGroup createBetaGroup(AppleCredentials credential, String appId, String name) {
        Map<String, Object> body = Map.of(
                "data",
                Map.of(
                        "type", TYPE_BETA_GROUPS,
                        "attributes", Map.of("name", name, "isInternalGroup", false),
                        "relationships", Map.of("app", Map.of("data", Map.of("type", TYPE_APPS, "id", appId)))));
        JsonNode created = post(credential, "/v1/betaGroups", body);
        return new BetaGroup(
                created.path("data").path("id").asString(),
                created.path("data").path("attributes").path("name").asString(null),
                false);
    }

    /** Assigns one build to one beta group (the dispatch's core provider call, AC3): idempotent on ASC's side. */
    public void assignBuildToGroup(AppleCredentials credential, String groupId, String buildId) {
        post(credential, "/v1/betaGroups/" + groupId + "/relationships/builds", relationshipBody(TYPE_BUILDS, buildId));
    }

    /** The builds currently assigned to a beta group (the drift half of reconciliation, AC5). */
    public List<String> buildIdsOfGroup(AppleCredentials credential, String groupId) {
        List<String> builds = new ArrayList<>();
        String uri = "/v1/betaGroups/" + groupId + "/builds?limit=" + PAGE_LIMIT;
        while (uri != null) {
            JsonNode page = get(credential, uri);
            for (JsonNode build : page.path("data")) {
                builds.add(build.path("id").asString());
            }
            uri = nextOf(page);
        }
        return builds;
    }

    /** The testers of a beta group with the emails Apple matches on. */
    public List<String> testerEmailsOfGroup(AppleCredentials credential, String groupId) {
        List<String> emails = new ArrayList<>();
        String uri = "/v1/betaGroups/" + groupId + "/betaTesters?limit=" + PAGE_LIMIT;
        while (uri != null) {
            JsonNode page = get(credential, uri);
            for (JsonNode tester : page.path("data")) {
                emails.add(tester.path("attributes").path("email").asString(null));
            }
            uri = nextOf(page);
        }
        return emails;
    }

    /** Adds testers to a beta group by email (ASC accepts unmatched-by-id testers as attributes). */
    public void addTestersToGroup(AppleCredentials credential, String groupId, List<String> emails) {
        if (emails.isEmpty()) {
            return;
        }
        List<Map<String, Object>> data = emails.stream()
                .map(email -> Map.<String, Object>of("type", TYPE_BETA_TESTERS, "attributes", Map.of("email", email)))
                .toList();
        post(credential, "/v1/betaGroups/" + groupId + "/relationships/betaTesters", Map.of("data", data));
    }

    /** One paged GET with the bearer token; taxonomy-mapped on failure. */
    private JsonNode get(AppleCredentials credential, String uri, Object... uriVariables) {
        return exchange(
                credential,
                () -> http.get()
                        .uri(uri, uriVariables)
                        .headers(headers -> headers.setBearerAuth(signer.tokenOf(credential)))
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .body(JsonNode.class));
    }

    /** One POST with the bearer token; taxonomy-mapped on failure. */
    private JsonNode post(AppleCredentials credential, String uri, Map<String, Object> body) {
        return exchange(
                credential,
                () -> http.post()
                        .uri(uri)
                        .headers(headers -> headers.setBearerAuth(signer.tokenOf(credential)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(JsonNode.class));
    }

    private JsonNode exchange(AppleCredentials credential, Call call) {
        try {
            JsonNode answer = call.execute();
            return answer == null ? tools.jackson.databind.node.JsonNodeFactory.instance.objectNode() : answer;
        } catch (RestClientResponseException e) {
            throw taxonomyOf(e);
        } catch (RuntimeException e) {
            // Transport fault (connect refused, read timeout): the honest
            // TRANSIENT, with the fault's identity logged for the operator.
            log.warn(
                    "the App Store Connect call failed in transport: {} ({})",
                    e.getMessage(),
                    e.getCause() == null
                            ? e.getClass().getSimpleName()
                            : e.getCause().toString());
            throw new AdapterException(
                    AdapterException.Category.TRANSIENT,
                    "testflight",
                    "the App Store Connect API call failed: " + e.getClass().getSimpleName());
        }
    }

    /** The taxonomy mapping of an ASC HTTP failure (the closed set the sandbox retries on). */
    private AdapterException taxonomyOf(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        if (status == 401 || status == 403) {
            return new AdapterException(
                    AdapterException.Category.AUTHENTICATION,
                    "testflight",
                    "Apple rejected the API key (HTTP " + status + ")");
        }
        if (status == 429) {
            meters.counter(MetricNames.ADAPTER_RATE_LIMIT, "channel", "testflight")
                    .increment();
            return new AdapterException(
                    AdapterException.Category.RATE_LIMITED, "testflight", "Apple throttled the call (HTTP 429)");
        }
        if (status >= 500) {
            return new AdapterException(
                    AdapterException.Category.TRANSIENT,
                    "testflight",
                    "App Store Connect failed (HTTP " + status + ")");
        }
        return new AdapterException(
                AdapterException.Category.PERMANENT,
                "testflight",
                "App Store Connect refused the request (HTTP " + status + ")");
    }

    /** The next page's URI from Apple's {@code links.next}, or null at the end. */
    private static String nextOf(JsonNode page) {
        String next = page.path("links").path("next").asString(null);
        if (next == null || next.isBlank()) {
            return null;
        }
        // The mock and Apple both answer absolute next-URIs; the query
        // is what matters, so the client re-anchors it on its base.
        int query = next.indexOf('?');
        if (next.contains("/v1/") && query > 0) {
            return next.substring(next.indexOf("/v1/"));
        }
        return null;
    }

    private static Map<String, Object> relationshipBody(String type, String id) {
        return Map.of("data", List.of(Map.of("type", type, "id", id)));
    }

    @FunctionalInterface
    private interface Call {
        JsonNode execute();
    }

    /** One ASC build as the connector addresses it. */
    record BetaBuild(String id, String version, String processingState) {}

    /** The beta-review states of one build (internal and external testing legs). */
    record BetaStates(String internalBuildState, String externalBuildState) {}

    /** One beta group as the mapping surface addresses it. */
    record BetaGroup(String id, String name, boolean internalGroup) {}
}
