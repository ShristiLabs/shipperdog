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

    /**
     * The provider label this client's failures and telemetry carry:
     * the client is the ONE Apple conversation both Apple channels
     * (testflight, appstore) run, so its exceptions and rate-limit
     * counter answer the provider grain, never one channel's name.
     */
    private static final String PROVIDER = "apple";

    private static final tools.jackson.databind.json.JsonMapper MAPPER = new tools.jackson.databind.json.JsonMapper();

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
        // for module clients by design). The JDK's own HttpClient is
        // the transport — unlike the HttpURLConnection factory it
        // speaks PATCH (the version-resource updates the submission
        // connector sends), still with zero new dependencies.
        java.net.http.HttpClient transport = java.net.http.HttpClient.newBuilder()
                .connectTimeout(
                        java.time.Duration.ofMillis(apple.connectTimeout().toMillis()))
                .build();
        org.springframework.http.client.JdkClientHttpRequestFactory factory =
                new org.springframework.http.client.JdkClientHttpRequestFactory(transport);
        factory.setReadTimeout(apple.readTimeout());
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

    /**
     * Removes one build from one beta group (the staged rollout's
     * revert arm, S13-06, issue #66, AC4): the orchestrated rollback's
     * TestFlight half — the release's build leaves the mapped groups,
     * idempotent on ASC's side (a group already without the build
     * answers success).
     */
    public void removeBuildFromGroup(AppleCredentials credential, String groupId, String buildId) {
        exchange(
                credential,
                () -> http.method(org.springframework.http.HttpMethod.DELETE)
                        .uri("/v1/betaGroups/" + groupId + "/relationships/builds")
                        .headers(headers -> headers.setBearerAuth(signer.tokenOf(credential)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(relationshipBody(TYPE_BUILDS, buildId))
                        .retrieve()
                        .body(JsonNode.class));
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

    /**
     * The app's App Store version of a version string (S13-03, #63,
     * FR-020): the production submission's find half — the version a
     * promotion updates when one already exists (ASC allows one
     * editable version per platform). Empty when none exists yet.
     */
    public Optional<AppVersion> versionOf(AppleCredentials credential, String appId, String versionString) {
        JsonNode page = get(
                credential,
                "/v1/apps/{appId}/appStoreVersions?filter[versionString]={version}&filter[platform]=IOS&limit=2",
                appId,
                versionString);
        JsonNode data = page.path("data");
        if (!data.isArray() || data.isEmpty()) {
            return Optional.empty();
        }
        JsonNode version = data.get(0);
        return Optional.of(versionOf(version));
    }

    /** Creates the app's App Store version (the create half of find-or-create; answers the created version). */
    public AppVersion createVersion(AppleCredentials credential, String appId, String versionString) {
        Map<String, Object> body = Map.of(
                "data",
                Map.of(
                        "type", "appStoreVersions",
                        "attributes", Map.of("versionString", versionString, "platform", "IOS"),
                        "relationships", Map.of("app", Map.of("data", Map.of("type", TYPE_APPS, "id", appId)))));
        JsonNode created = post(credential, "/v1/appStoreVersions", body);
        return versionOf(created.path("data"));
    }

    /**
     * Sets the version's release type: {@code AFTER_APPROVAL} when the
     * submission starts the phased-release curve (the version releases
     * itself once approved, the curve governing availability),
     * {@code MANUAL} when the operator releases by hand.
     */
    public void updateVersionReleaseType(AppleCredentials credential, String versionId, String releaseType) {
        patch(
                credential,
                "/v1/appStoreVersions/" + versionId,
                Map.of(
                        "data",
                        Map.of(
                                "type",
                                "appStoreVersions",
                                "id",
                                versionId,
                                "attributes",
                                Map.of("releaseType", releaseType))));
    }

    /**
     * Upserts one localization of the version (per-locale listing
     * metadata, AC1): an existing locale PATCHes in place, a new one
     * POSTs. The screenshot references stay a manifest pin — ASC-side
     * assets upload through ASC's own surfaces (the story's minimal
     * field set).
     */
    public void putLocalization(
            AppleCredentials credential,
            String versionId,
            String locale,
            String description,
            String keywords,
            String whatsNew) {
        for (JsonNode existing : localizationsOf(credential, versionId)) {
            if (locale.equals(existing.path("attributes").path("locale").asString(null))) {
                Map<String, Object> attributes = new java.util.LinkedHashMap<>();
                attributes.put("description", description);
                if (keywords != null) {
                    attributes.put("keywords", keywords);
                }
                if (whatsNew != null) {
                    attributes.put("whatsNew", whatsNew);
                }
                patch(
                        credential,
                        "/v1/appStoreVersionLocalizations/"
                                + existing.path("id").asString(),
                        Map.of(
                                "data",
                                Map.of(
                                        "type",
                                        "appStoreVersionLocalizations",
                                        "id",
                                        existing.path("id").asString(),
                                        "attributes",
                                        attributes)));
                return;
            }
        }
        Map<String, Object> attributes = new java.util.LinkedHashMap<>();
        attributes.put("locale", locale);
        attributes.put("description", description);
        if (keywords != null) {
            attributes.put("keywords", keywords);
        }
        if (whatsNew != null) {
            attributes.put("whatsNew", whatsNew);
        }
        post(
                credential,
                "/v1/appStoreVersions/" + versionId + "/appStoreVersionLocalizations",
                Map.of("data", Map.of("type", "appStoreVersionLocalizations", "attributes", attributes)));
    }

    /** The version's localizations, as the upsert's find leg reads them. */
    public List<JsonNode> localizationsOf(AppleCredentials credential, String versionId) {
        List<JsonNode> localizations = new ArrayList<>();
        String uri = "/v1/appStoreVersions/" + versionId + "/appStoreVersionLocalizations?limit=" + PAGE_LIMIT;
        while (uri != null) {
            JsonNode page = get(credential, uri);
            for (JsonNode localization : page.path("data")) {
                localizations.add(localization);
            }
            uri = nextOf(page);
        }
        return localizations;
    }

    /**
     * Submits the version for App Review (AC1's submission half): the
     * POST that moves the version into Apple's review pipeline. A
     * metadata fault answers PERMANENT with Apple's own reason
     * (AC4's actionable detail).
     */
    public void submitVersionForReview(AppleCredentials credential, String versionId) {
        post(
                credential,
                "/v1/appStoreVersionSubmissions",
                Map.of(
                        "data",
                        Map.of(
                                "type",
                                "appStoreVersionSubmissions",
                                "relationships",
                                Map.of(
                                        "appStoreVersion",
                                        Map.of("data", Map.of("type", "appStoreVersions", "id", versionId))))));
    }

    /**
     * Enables the version's phased release (AC3's start): Apple's
     * fixed 7-day availability curve (1, 2, 5, 10, 20, 50, 100
     * percent), which begins when the approved version releases.
     */
    public void enablePhasedRelease(AppleCredentials credential, String versionId) {
        post(
                credential,
                "/v1/appStoreVersionPhasedReleases",
                Map.of(
                        "data",
                        Map.of(
                                "type",
                                "appStoreVersionPhasedReleases",
                                "relationships",
                                Map.of(
                                        "appStoreVersion",
                                        Map.of("data", Map.of("type", "appStoreVersions", "id", versionId))))));
    }

    /**
     * The version's own state and review verdict (AC2's polling half):
     * Apple's {@code appStoreVersionState} word verbatim for the
     * record, with the rejection reasons a REJECTED version carries
     * (absent when Apple offered none — tolerated, never assumed).
     */
    public AppVersionState versionStateOf(AppleCredentials credential, String versionId) {
        JsonNode detail = get(credential, "/v1/appStoreVersions/{versionId}", versionId);
        JsonNode attributes = detail.path("data").path("attributes");
        List<String> rejectionReasons = new ArrayList<>();
        for (JsonNode reason : attributes.path("rejectionReasons")) {
            rejectionReasons.add(reason.asText());
        }
        return new AppVersionState(
                attributes.path("appStoreVersionState").asString(null),
                attributes.path("releaseType").asString(null),
                List.copyOf(rejectionReasons));
    }

    /**
     * The version's phased release as the curve view reads it (AC3):
     * state ({@code INACTIVE}, {@code ACTIVE}, {@code PAUSED},
     * {@code COMPLETE}), the curve's current day, and its total. Empty
     * when the version carries none (phased release never enabled) —
     * Apple answers that case with a plain 404, which is the
     * resource's ABSENCE here, never a fault.
     */
    public Optional<PhasedRelease> phasedReleaseOf(AppleCredentials credential, String versionId) {
        JsonNode page;
        try {
            page = get(credential, "/v1/appStoreVersions/{versionId}/appStoreVersionPhasedRelease", versionId);
        } catch (AdapterException e) {
            if (isProviderNotFound(e)) {
                return Optional.empty();
            }
            throw e;
        }
        JsonNode data = page.path("data");
        if (!data.isObject() || data.path("id").asString(null) == null) {
            return Optional.empty();
        }
        JsonNode attributes = data.path("attributes");
        return Optional.of(new PhasedRelease(
                data.path("id").asString(),
                attributes.path("phasedReleaseState").asString(null),
                attributes.path("currentDayNumber").asInt(0),
                attributes.path("totalDayCount").asInt(7)));
    }

    /**
     * Whether a taxonomy failure is Apple's own 404 — recognized by
     * the PERMANENT message this class builds for it a few lines up
     * (the coupling is deliberate and local: one file owns both the
     * message's shape and this reading of it).
     */
    private static boolean isProviderNotFound(AdapterException failure) {
        return failure.category() == AdapterException.Category.PERMANENT
                && failure.getMessage() != null
                && failure.getMessage().contains("(HTTP 404)");
    }

    /**
     * Moves the phased release's state (AC3's controls): {@code PAUSED}
     * and {@code ACTIVE} pause and resume the curve, {@code COMPLETE}
     * halts it by releasing to every user at once (Apple's "release to
     * all users" — the documented rollback posture of the production
     * channel).
     */
    public void updatePhasedReleaseState(AppleCredentials credential, String phasedReleaseId, String state) {
        patch(
                credential,
                "/v1/appStoreVersionPhasedReleases/" + phasedReleaseId,
                Map.of(
                        "data",
                        Map.of(
                                "type",
                                "appStoreVersionPhasedReleases",
                                "id",
                                phasedReleaseId,
                                "attributes",
                                Map.of("phasedReleaseState", state))));
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

    /** One PATCH with the bearer token; taxonomy-mapped on failure. */
    private JsonNode patch(AppleCredentials credential, String uri, Map<String, Object> body) {
        return exchange(
                credential,
                () -> http.patch()
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
                    PROVIDER,
                    "the App Store Connect API call failed: " + e.getClass().getSimpleName());
        }
    }

    /**
     * The taxonomy mapping of an ASC HTTP failure (the closed set the
     * sandbox retries on). Apple throttles the API KEY, not the
     * calling channel — the rate-limit counter is therefore
     * provider-grain ({@code apple}), the one key both Apple channels
     * usually share; the per-channel sandbox timer answers the
     * which-channel question.
     */
    private AdapterException taxonomyOf(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        if (status == 401 || status == 403) {
            return new AdapterException(
                    AdapterException.Category.AUTHENTICATION,
                    PROVIDER,
                    "Apple rejected the API key (HTTP " + status + ")");
        }
        if (status == 429) {
            meters.counter(MetricNames.ADAPTER_RATE_LIMIT, "provider", PROVIDER).increment();
            return new AdapterException(
                    AdapterException.Category.RATE_LIMITED, PROVIDER, "Apple throttled the call (HTTP 429)");
        }
        if (status >= 500) {
            return new AdapterException(
                    AdapterException.Category.TRANSIENT, PROVIDER, "App Store Connect failed (HTTP " + status + ")");
        }
        // The remaining 4xx family is PERMANENT, and Apple's own error
        // body is the actionable reason a refused submission carries
        // (S13-03, AC4): "You must provide a value for the key ...",
        // an entity error's title -- whatever the provider said, kept
        // verbatim beside the taxonomy code.
        return new AdapterException(
                AdapterException.Category.PERMANENT,
                PROVIDER,
                "App Store Connect refused the request (HTTP " + status + "): " + errorDetailOf(e));
    }

    /** Apple's first error's own words from a JSON:API error body; the status text when the body carries none. */
    private static String errorDetailOf(RestClientResponseException e) {
        try {
            JsonNode errors = MAPPER.readTree(e.getResponseBodyAsString()).path("errors");
            if (errors.isArray() && !errors.isEmpty()) {
                JsonNode first = errors.get(0);
                String detail = first.path("detail").asString(null);
                if (detail != null && !detail.isBlank()) {
                    return detail;
                }
                String title = first.path("title").asString(null);
                if (title != null && !title.isBlank()) {
                    return title;
                }
            }
        } catch (RuntimeException ignored) {
            // A non-JSON error body (a proxy's HTML, an empty answer):
            // the status line below is the honest fallback.
        }
        return e.getStatusText();
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

    /** One App Store version as the submission addresses it (S13-03). */
    record AppVersion(String id, String versionString, String state, String releaseType) {}

    /**
     * The version's own state and review verdict (S13-03, AC2/AC4):
     * Apple's state word, the release type in force, and the rejection
     * reasons a REJECTED version carries (empty when Apple offered
     * none).
     */
    record AppVersionState(String state, String releaseType, List<String> rejectionReasons) {}

    /**
     * The version's phased release as the curve view addresses it
     * (S13-03, AC3): the provider's resource id, its state word, the
     * curve's current day (0 before the curve starts), and the total
     * day count of Apple's fixed 7-day curve.
     */
    record PhasedRelease(String id, String state, int currentDay, int totalDays) {}

    /** One version resource's facts from its JSON:API shape. */
    private static AppVersion versionOf(JsonNode version) {
        return new AppVersion(
                version.path("id").asString(),
                version.path("attributes").path("versionString").asString(null),
                version.path("attributes").path("appStoreVersionState").asString(null),
                version.path("attributes").path("releaseType").asString(null));
    }
}
