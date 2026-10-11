package com.shipperdog.api.distribution.internal.apple;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The contract-faithful mock App Store Connect server (issue #62,
 * S13-02): Apple's JSON:API shapes — {@code data} arrays, {@code
 * attributes}, relationship bodies — served from an in-memory state a
 * test drives, over the repo's mock precedent (the JDK's own {@code
 * HttpServer}, no new dependency). The mock OWNS the credential's
 * truth: it generates the EC P-256 pair, hands the .p8 PEM to the
 * binding fixture, and VERIFIES every request's bearer token (ES256
 * under the public key, kid, iss, aud asc, the twenty-minute window)
 * — a request without a valid token answers Apple's 401 exactly like
 * the real API would.
 *
 * <p>Scenario knobs answer the failure taxonomy: reject the next token
 * (401), throttle the next call (429 with {@code Retry-After}), fail
 * the next call server-side (500). The request log records every call
 * a test asserts on (assignment bodies included).
 */
public final class AppleAppStoreConnectMock {

    private final HttpServer server;
    private final KeyPair keyPair;
    private final String pem;
    private final String issuerId = "iss-mock-" + UUID.randomUUID().toString().substring(0, 8);
    private final String keyId = "KIDMOCK1";

    // The in-memory ASC state.
    private final Map<String, String> appIdsByBundle = new ConcurrentHashMap<>();
    private final Map<String, String> appBundleByAppId = new ConcurrentHashMap<>();
    /** buildId -> state: the beta-detail states a test moves. */
    private final Map<String, Build> builds = new ConcurrentHashMap<>();

    private final Map<String, BetaGroupState> groups = new ConcurrentHashMap<>();
    /** versionId -> state: the App Store production versions the submission connector drives (S13-03). */
    private final Map<String, AppVersionState> versions = new ConcurrentHashMap<>();

    private final AtomicInteger idCounter = new AtomicInteger(1000);

    // The scenario knobs and the request log.
    private volatile boolean rejectNextToken = false;
    private volatile boolean throttleNextCall = false;
    private final java.util.concurrent.atomic.AtomicInteger throttleBudget =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private volatile boolean failNextCallServerSide = false;
    private volatile boolean rejectNextSubmissionMetadata = false;
    private final List<RecordedCall> calls = new CopyOnWriteArrayList<>();

    public AppleAppStoreConnectMock() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            this.keyPair = generator.generateKeyPair();
            this.pem = "-----BEGIN PRIVATE KEY-----\n"
                    + Base64.getMimeEncoder()
                            .encodeToString(keyPair.getPrivate().getEncoded())
                    + "\n-----END PRIVATE KEY-----\n";
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.server.createContext("/", this::handle);
            this.server.start();
        } catch (IOException | java.security.GeneralSecurityException e) {
            throw new IllegalStateException("the mock ASC server could not start", e);
        }
    }

    public void stop() {
        server.stop(0);
    }

    /** The between-tests reset: the wire log and the in-memory state start every test clean. */
    public void reset() {
        calls.clear();
        groups.clear();
        builds.clear();
        appIdsByBundle.clear();
        appBundleByAppId.clear();
        versions.clear();
        rejectNextToken = false;
        throttleNextCall = false;
        failNextCallServerSide = false;
        rejectNextSubmissionMetadata = false;
    }

    /** The base URL the Apple connector's property points at. */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** The binding fixture's facts: issuer id, key id, and the .p8 material (test-only, generated here). */
    public String issuerId() {
        return issuerId;
    }

    public String keyId() {
        return keyId;
    }

    public String pem() {
        return pem;
    }

    /** Registers an app for a bundle id, answering the ASC app id. */
    public String registerApp(String bundleId) {
        String appId = "app-" + idCounter.incrementAndGet();
        appIdsByBundle.put(bundleId, appId);
        appBundleByAppId.put(appId, bundleId);
        return appId;
    }

    /** Registers an uploaded build: the ASC build id for (app, build number), with its states. */
    public String registerBuild(String appId, String buildNumber, String externalBuildState) {
        String buildId = "build-" + idCounter.incrementAndGet();
        builds.put(buildId, new Build(appId, buildNumber, externalBuildState, externalBuildState));
        return buildId;
    }

    /** Moves a build's external beta state (the refresh scenarios). */
    public void setExternalBuildState(String buildId, String state) {
        Build build = builds.get(buildId);
        if (build != null) {
            builds.put(buildId, build.withExternal(state));
        }
    }

    /** Registers an external beta group, answering its ASC id. */
    public String registerGroup(String appId, String name) {
        String groupId = "group-" + idCounter.incrementAndGet();
        groups.put(groupId, new BetaGroupState(appId, name));
        return groupId;
    }

    /** Adds a tester email to a group (the manual ASC console action). */
    public void addTesterToGroup(String groupId, String email) {
        BetaGroupState group = groups.get(groupId);
        if (group != null) {
            group.testers.add(email);
        }
    }

    /** Removes a build from a group (the manual un-assignment drift). */
    public void unassignBuild(String groupId, String buildId) {
        BetaGroupState group = groups.get(groupId);
        if (group != null) {
            group.builds.remove(buildId);
        }
    }

    public void rejectNextToken() {
        rejectNextToken = true;
    }

    public void throttleNextCall() {
        throttleNextCall = true;
    }

    /** Throttles the next N calls (the exhaustion path: every sandbox attempt eats one). */
    public void throttleNextCalls(int calls) {
        throttleBudget.set(calls);
    }

    public void failNextCallServerSide() {
        failNextCallServerSide = true;
    }

    /**
     * The metadata-rejection knob (S13-03, AC4): the next submission
     * POST answers Apple's entity-error 409 with an actionable detail
     * — the refused-submission family that vetoes the crossing.
     */
    public void rejectNextSubmissionMetadata() {
        rejectNextSubmissionMetadata = true;
    }

    /** Moves a version's App Store state (the review-journey scenarios), with optional rejection reasons. */
    public void setVersionState(String versionId, String state, String... rejectionReasons) {
        AppVersionState version = versions.get(versionId);
        if (version != null) {
            versions.put(versionId, version.withState(state, List.of(rejectionReasons)));
        }
    }

    /** Advances a version's phased-release curve one day (the progress scenarios); completes at the total. */
    public void advancePhasedDay(String versionId) {
        AppVersionState version = versions.get(versionId);
        if (version != null && version.phased() != null) {
            versions.put(
                    versionId, version.withPhasedDay(Math.min(version.phased().currentDay() + 1, 7)));
        }
    }

    /** The version's phased-release facts as the curve view expects them (null when none). */
    public PhasedRelease phasedReleaseOf(String versionId) {
        AppVersionState version = versions.get(versionId);
        return version == null ? null : version.phased();
    }

    /** Every recorded call: method, path, and the assignment bodies a test asserts on. */
    public List<RecordedCall> calls() {
        return List.copyOf(calls);
    }

    public List<RecordedCall> callsOf(String method, String pathContains) {
        return calls.stream()
                .filter(call -> call.method().equals(method) && call.path().contains(pathContains))
                .toList();
    }

    // -------------------------------------------------------------------
    // The wire
    // -------------------------------------------------------------------

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            calls.add(new RecordedCall(method, path, body));

            if (!"GET".equals(method) || path.contains("relationships")) {
                // The bearer check applies to every call; GETs of the
                // token-less shape never happen in this connector.
            }
            if (rejectNextToken) {
                rejectNextToken = false;
                answer(exchange, 401, errors("NOT_AUTHORIZED", "authentication failed"));
                return;
            }
            if (!tokenValid(exchange.getRequestHeaders().getFirst("Authorization"))) {
                answer(exchange, 401, errors("NOT_AUTHORIZED", "authentication failed"));
                return;
            }
            if (throttleNextCall || throttleBudget.getAndUpdate(n -> n > 0 ? n - 1 : n) > 0) {
                throttleNextCall = false;
                exchange.getResponseHeaders().set("Retry-After", "1");
                answer(exchange, 429, errors("RATE_LIMITED", "rate limited"));
                return;
            }
            if (failNextCallServerSide) {
                failNextCallServerSide = false;
                answer(exchange, 500, errors("INTERNAL", "server error"));
                return;
            }

            if ("GET".equals(method) && path.equals("/v1/apps")) {
                handleApps(exchange);
            } else if ("GET".equals(method) && path.equals("/v1/builds")) {
                handleBuilds(exchange);
            } else if ("GET".equals(method) && path.endsWith("/buildBetaDetail")) {
                handleBetaDetail(exchange, path);
            } else if ("GET".equals(method) && path.equals("/v1/betaGroups")) {
                handleBetaGroups(exchange);
            } else if ("POST".equals(method) && path.equals("/v1/betaGroups")) {
                handleCreateGroup(exchange, body);
            } else if ("GET".equals(method) && path.matches("/v1/betaGroups/[^/]+/builds")) {
                handleGroupBuilds(exchange, path);
            } else if ("POST".equals(method) && path.matches("/v1/betaGroups/[^/]+/relationships/builds")) {
                handleAssignBuild(exchange, path, body);
            } else if ("GET".equals(method) && path.matches("/v1/betaGroups/[^/]+/betaTesters")) {
                handleGroupTesters(exchange, path);
            } else if ("POST".equals(method) && path.matches("/v1/betaGroups/[^/]+/relationships/betaTesters")) {
                handleAddTesters(exchange, path, body);
            } else if ("GET".equals(method) && path.matches("/v1/apps/[^/]+/appStoreVersions")) {
                handleListVersions(exchange, path);
            } else if ("POST".equals(method) && path.equals("/v1/appStoreVersions")) {
                handleCreateVersion(exchange, body);
            } else if ("GET".equals(method) && path.matches("/v1/appStoreVersions/[^/]+")) {
                handleGetVersion(exchange, path);
            } else if ("PATCH".equals(method) && path.matches("/v1/appStoreVersions/[^/]+")) {
                handlePatchVersion(exchange, path, body);
            } else if ("GET".equals(method)
                    && path.matches("/v1/appStoreVersions/[^/]+/appStoreVersionLocalizations")) {
                handleListLocalizations(exchange, path);
            } else if ("POST".equals(method)
                    && path.matches("/v1/appStoreVersions/[^/]+/appStoreVersionLocalizations")) {
                handleCreateLocalization(exchange, path, body);
            } else if ("PATCH".equals(method) && path.matches("/v1/appStoreVersionLocalizations/[^/]+")) {
                handlePatchLocalization(exchange, path, body);
            } else if ("POST".equals(method) && path.equals("/v1/appStoreVersionSubmissions")) {
                handleSubmitVersion(exchange, body);
            } else if ("GET".equals(method)
                    && path.matches("/v1/appStoreVersions/[^/]+/appStoreVersionPhasedRelease")) {
                handleGetPhasedRelease(exchange, path);
            } else if ("POST".equals(method) && path.equals("/v1/appStoreVersionPhasedReleases")) {
                handleEnablePhasedRelease(exchange, body);
            } else if ("PATCH".equals(method) && path.matches("/v1/appStoreVersionPhasedReleases/[^/]+")) {
                handlePatchPhasedRelease(exchange, path, body);
            } else {
                answer(exchange, 404, errors("NOT_FOUND", path));
            }
        } finally {
            exchange.close();
        }
    }

    private void handleApps(HttpExchange exchange) throws IOException {
        String bundleId = queryOf(exchange, "filter[bundleId]");
        String appId = appIdsByBundle.get(bundleId);
        if (appId == null) {
            answer(exchange, 200, page(List.of()));
            return;
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("bundleId", bundleId);
        answer(exchange, 200, page(List.of(resource("apps", appId, attributes))));
    }

    private void handleBuilds(HttpExchange exchange) throws IOException {
        String appId = queryOf(exchange, "filter[app]");
        String version = queryOf(exchange, "filter[version]");
        List<Map<String, Object>> data = new ArrayList<>();
        for (Build build : builds.values()) {
            if (build.appId().equals(appId) && build.version().equals(version)) {
                Map<String, Object> attributes = new LinkedHashMap<>();
                attributes.put("version", build.version());
                attributes.put("processingState", "VALID");
                data.add(resource("builds", buildIdOf(build), attributes));
            }
        }
        answer(exchange, 200, page(data));
    }

    private void handleBetaDetail(HttpExchange exchange, String path) throws IOException {
        String buildId = path.substring("/v1/builds/".length(), path.length() - "/buildBetaDetail".length());
        Build build = builds.get(buildId);
        if (build == null) {
            answer(exchange, 404, errors("NOT_FOUND", buildId));
            return;
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("internalBuildState", build.internalState());
        attributes.put("externalBuildState", build.externalState());
        answer(exchange, 200, Map.of("data", resource("buildBetaDetails", buildId + "-detail", attributes)));
    }

    private void handleBetaGroups(HttpExchange exchange) throws IOException {
        String appId = queryOf(exchange, "filter[app]");
        List<Map<String, Object>> data = new ArrayList<>();
        for (BetaGroupState group : groups.values()) {
            if (group.appId.equals(appId)) {
                Map<String, Object> attributes = new LinkedHashMap<>();
                attributes.put("name", group.name);
                attributes.put("isInternalGroup", false);
                data.add(resource("betaGroups", groupIdOf(group), attributes));
            }
        }
        answer(exchange, 200, page(data));
    }

    private void handleCreateGroup(HttpExchange exchange, String body) throws IOException {
        JsonNode request = MAPPER.readTree(body);
        String name = request.path("data").path("attributes").path("name").asString();
        String appId = request.path("data")
                .path("relationships")
                .path("app")
                .path("data")
                .path("id")
                .asString();
        String groupId = "group-" + idCounter.incrementAndGet();
        groups.put(groupId, new BetaGroupState(appId, name));
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("name", name);
        attributes.put("isInternalGroup", false);
        answer(exchange, 201, Map.of("data", resource("betaGroups", groupId, attributes)));
    }

    private void handleGroupBuilds(HttpExchange exchange, String path) throws IOException {
        String groupId = groupIdOfPath(path);
        BetaGroupState group = groups.get(groupId);
        List<Map<String, Object>> data = new ArrayList<>();
        if (group != null) {
            for (String buildId : group.builds) {
                Map<String, Object> attributes = new LinkedHashMap<>();
                attributes.put(
                        "version",
                        builds.containsKey(buildId) ? builds.get(buildId).version() : "0");
                data.add(resource("builds", buildId, attributes));
            }
        }
        answer(exchange, 200, page(data));
    }

    private void handleAssignBuild(HttpExchange exchange, String path, String body) throws IOException {
        String groupId = groupIdOfPath(path);
        BetaGroupState group = groups.get(groupId);
        JsonNode request = MAPPER.readTree(body);
        for (JsonNode item : request.path("data")) {
            String buildId = item.path("id").asString();
            if (group != null && builds.containsKey(buildId)) {
                group.builds.add(buildId);
            } else if (group != null) {
                answer(exchange, 404, errors("NOT_FOUND", buildId));
                return;
            }
        }
        exchange.sendResponseHeaders(204, -1);
    }

    private void handleGroupTesters(HttpExchange exchange, String path) throws IOException {
        String groupId = groupIdOfPath(path);
        BetaGroupState group = groups.get(groupId);
        List<Map<String, Object>> data = new ArrayList<>();
        if (group != null) {
            for (String email : group.testers) {
                Map<String, Object> attributes = new LinkedHashMap<>();
                attributes.put("email", email);
                data.add(resource("betaTesters", "tester-" + email.hashCode(), attributes));
            }
        }
        answer(exchange, 200, page(data));
    }

    private void handleAddTesters(HttpExchange exchange, String path, String body) throws IOException {
        String groupId = groupIdOfPath(path);
        BetaGroupState group = groups.get(groupId);
        JsonNode request = MAPPER.readTree(body);
        for (JsonNode item : request.path("data")) {
            String email = item.path("attributes").path("email").asString();
            if (group != null) {
                group.testers.add(email);
            }
        }
        exchange.sendResponseHeaders(204, -1);
    }

    // -------------------------------------------------------------------
    // The App Store production version surfaces (S13-03)
    // -------------------------------------------------------------------

    private void handleListVersions(HttpExchange exchange, String path) throws IOException {
        String appId = path.substring("/v1/apps/".length(), path.length() - "/appStoreVersions".length());
        String versionString = queryOf(exchange, "filter[versionString]");
        String platform = queryOf(exchange, "filter[platform]");
        List<Map<String, Object>> data = new ArrayList<>();
        for (AppVersionState version : versions.values()) {
            if (version.appId().equals(appId)
                    && (versionString == null || version.versionString().equals(versionString))
                    && (platform == null || "IOS".equals(platform))) {
                data.add(versionResource(versionIdOf(version), version));
            }
        }
        answer(exchange, 200, page(data));
    }

    private void handleCreateVersion(HttpExchange exchange, String body) throws IOException {
        JsonNode request = MAPPER.readTree(body);
        String versionString =
                request.path("data").path("attributes").path("versionString").asString(null);
        String platform =
                request.path("data").path("attributes").path("platform").asString("IOS");
        String appId = request.path("data")
                .path("relationships")
                .path("app")
                .path("data")
                .path("id")
                .asString();
        if (versionString == null || versionString.isBlank()) {
            answer(exchange, 409, entityError("You must provide a value for the key versionString"));
            return;
        }
        if (!"IOS".equals(platform)) {
            answer(exchange, 409, entityError("the platform must be IOS"));
            return;
        }
        for (AppVersionState existing : versions.values()) {
            if (existing.appId().equals(appId) && existing.versionString().equals(versionString)) {
                answer(
                        exchange,
                        409,
                        entityError("There is another version with the same version string " + versionString));
                return;
            }
        }
        String versionId = "version-" + idCounter.incrementAndGet();
        versions.put(versionId, new AppVersionState(appId, versionString, "PREPARE_FOR_SUBMISSION", null));
        answer(exchange, 201, Map.of("data", versionResource(versionId, versions.get(versionId))));
    }

    private void handleGetVersion(HttpExchange exchange, String path) throws IOException {
        String versionId = lastSegmentOf(path);
        AppVersionState version = versions.get(versionId);
        if (version == null) {
            answer(exchange, 404, errors("NOT_FOUND", versionId));
            return;
        }
        answer(exchange, 200, Map.of("data", versionResource(versionId, version)));
    }

    private void handlePatchVersion(HttpExchange exchange, String path, String body) throws IOException {
        String versionId = lastSegmentOf(path);
        AppVersionState version = versions.get(versionId);
        if (version == null) {
            answer(exchange, 404, errors("NOT_FOUND", versionId));
            return;
        }
        JsonNode attributes = MAPPER.readTree(body).path("data").path("attributes");
        if (attributes.has("releaseType")) {
            versions.put(
                    versionId,
                    version.withReleaseType(attributes.path("releaseType").asString()));
        }
        exchange.sendResponseHeaders(204, -1);
    }

    private void handleListLocalizations(HttpExchange exchange, String path) throws IOException {
        String versionId = versionIdOfLocalizationPath(path);
        AppVersionState version = versions.get(versionId);
        List<Map<String, Object>> data = new ArrayList<>();
        if (version != null) {
            for (Map.Entry<String, Localization> locale :
                    version.localizations().entrySet()) {
                data.add(localizationResource(versionId + "-" + locale.getKey(), locale.getKey(), locale.getValue()));
            }
        }
        answer(exchange, 200, page(data));
    }

    private void handleCreateLocalization(HttpExchange exchange, String path, String body) throws IOException {
        String versionId = versionIdOfLocalizationPath(path);
        AppVersionState version = versions.get(versionId);
        if (version == null) {
            answer(exchange, 404, errors("NOT_FOUND", versionId));
            return;
        }
        JsonNode attributes = MAPPER.readTree(body).path("data").path("attributes");
        String locale = attributes.path("locale").asString(null);
        String description = attributes.path("description").asString(null);
        String keywords = attributes.path("keywords").asString(null);
        String whatsNew = attributes.path("whatsNew").asString(null);
        // Apple's own metadata contract: the description is required,
        // the keywords capped — the refused-submission family (AC4).
        if (description == null || description.isBlank()) {
            answer(exchange, 409, entityError("You must provide a value for the key description"));
            return;
        }
        if (keywords != null && keywords.length() > 100) {
            answer(exchange, 409, entityError("The keywords must not exceed 100 characters"));
            return;
        }
        if (version.localizations().containsKey(locale)) {
            answer(exchange, 409, entityError("There is already a localization for the locale " + locale));
            return;
        }
        version.localizations().put(locale, new Localization(description, keywords, whatsNew));
        answer(
                exchange,
                201,
                Map.of(
                        "data",
                        localizationResource(
                                versionId + "-" + locale,
                                locale,
                                version.localizations().get(locale))));
    }

    private void handlePatchLocalization(HttpExchange exchange, String path, String body) throws IOException {
        String localizationId = lastSegmentOf(path);
        // localizationId is <versionId>-<locale>: split at the FIRST
        // hyphen boundary that names a known version.
        for (Map.Entry<String, AppVersionState> entry : versions.entrySet()) {
            if (localizationId.startsWith(entry.getKey() + "-")) {
                String locale = localizationId.substring(entry.getKey().length() + 1);
                AppVersionState version = entry.getValue();
                Localization standing = version.localizations().get(locale);
                if (standing == null) {
                    break;
                }
                JsonNode attributes = MAPPER.readTree(body).path("data").path("attributes");
                String description = attributes.has("description")
                        ? attributes.path("description").asString()
                        : standing.description();
                String keywords =
                        attributes.has("keywords") ? attributes.path("keywords").asString() : standing.keywords();
                String whatsNew =
                        attributes.has("whatsNew") ? attributes.path("whatsNew").asString() : standing.whatsNew();
                if (description == null || description.isBlank()) {
                    answer(exchange, 409, entityError("You must provide a value for the key description"));
                    return;
                }
                if (keywords != null && keywords.length() > 100) {
                    answer(exchange, 409, entityError("The keywords must not exceed 100 characters"));
                    return;
                }
                version.localizations().put(locale, new Localization(description, keywords, whatsNew));
                exchange.sendResponseHeaders(204, -1);
                return;
            }
        }
        answer(exchange, 404, errors("NOT_FOUND", localizationId));
    }

    private void handleSubmitVersion(HttpExchange exchange, String body) throws IOException {
        String versionId = MAPPER.readTree(body)
                .path("data")
                .path("relationships")
                .path("appStoreVersion")
                .path("data")
                .path("id")
                .asString();
        AppVersionState version = versions.get(versionId);
        if (version == null) {
            answer(exchange, 404, errors("NOT_FOUND", versionId));
            return;
        }
        // The metadata-rejection knob: Apple's entity error with an
        // actionable detail — the refused submission that vetoes the
        // crossing (AC4).
        if (rejectNextSubmissionMetadata) {
            rejectNextSubmissionMetadata = false;
            answer(
                    exchange,
                    409,
                    entityError("You must provide a value for the key CFBundleShortVersionString - The value must"
                            + " match the version of the uploaded build"));
            return;
        }
        boolean described = version.localizations().values().stream()
                .anyMatch(localization -> localization.description() != null
                        && !localization.description().isBlank());
        if (!described) {
            answer(exchange, 409, entityError("You must provide a value for the key description"));
            return;
        }
        versions.put(versionId, version.withState("WAITING_FOR_REVIEW", List.of()));
        exchange.sendResponseHeaders(204, -1);
    }

    private void handleGetPhasedRelease(HttpExchange exchange, String path) throws IOException {
        String versionId = path.substring(
                "/v1/appStoreVersions/".length(), path.length() - "/appStoreVersionPhasedRelease".length());
        AppVersionState version = versions.get(versionId);
        if (version == null) {
            answer(exchange, 404, errors("NOT_FOUND", versionId));
            return;
        }
        if (version.phased() == null) {
            answer(exchange, 404, errors("NOT_FOUND", "phasedRelease"));
            return;
        }
        answer(exchange, 200, Map.of("data", phasedResource(version.phased())));
    }

    private void handleEnablePhasedRelease(HttpExchange exchange, String body) throws IOException {
        String versionId = MAPPER.readTree(body)
                .path("data")
                .path("relationships")
                .path("appStoreVersion")
                .path("data")
                .path("id")
                .asString();
        AppVersionState version = versions.get(versionId);
        if (version == null) {
            answer(exchange, 404, errors("NOT_FOUND", versionId));
            return;
        }
        if (version.phased() != null) {
            answer(exchange, 409, entityError("The phased release already exists"));
            return;
        }
        versions.put(versionId, version.withPhased(new PhasedRelease("phased-" + versionId, "INACTIVE", 0, 7)));
        answer(
                exchange,
                201,
                Map.of("data", phasedResource(versions.get(versionId).phased())));
    }

    private void handlePatchPhasedRelease(HttpExchange exchange, String path, String body) throws IOException {
        String phasedId = lastSegmentOf(path);
        String state = MAPPER.readTree(body)
                .path("data")
                .path("attributes")
                .path("phasedReleaseState")
                .asString(null);
        for (Map.Entry<String, AppVersionState> entry : versions.entrySet()) {
            PhasedRelease phased = entry.getValue().phased();
            if (phased != null && phased.id().equals(phasedId)) {
                if (!"PAUSED".equals(state) && !"ACTIVE".equals(state) && !"COMPLETE".equals(state)) {
                    answer(exchange, 409, entityError("The phased release state must be ACTIVE, PAUSED, or COMPLETE"));
                    return;
                }
                int day = "COMPLETE".equals(state) ? phased.totalDays() : phased.currentDay();
                versions.put(
                        entry.getKey(),
                        entry.getValue().withPhased(new PhasedRelease(phasedId, state, day, phased.totalDays())));
                exchange.sendResponseHeaders(204, -1);
                return;
            }
        }
        answer(exchange, 404, errors("NOT_FOUND", phasedId));
    }

    private Map<String, Object> versionResource(String versionId, AppVersionState version) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("versionString", version.versionString());
        attributes.put("platform", "IOS");
        attributes.put("appStoreVersionState", version.state());
        if (version.releaseType() != null) {
            attributes.put("releaseType", version.releaseType());
        }
        if (!version.rejectionReasons().isEmpty()) {
            attributes.put("rejectionReasons", version.rejectionReasons());
        }
        return resource("appStoreVersions", versionId, attributes);
    }

    private static Map<String, Object> localizationResource(String id, String locale, Localization localization) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("locale", locale);
        attributes.put("description", localization.description());
        if (localization.keywords() != null) {
            attributes.put("keywords", localization.keywords());
        }
        if (localization.whatsNew() != null) {
            attributes.put("whatsNew", localization.whatsNew());
        }
        return resource("appStoreVersionLocalizations", id, attributes);
    }

    private static Map<String, Object> phasedResource(PhasedRelease phased) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("phasedReleaseState", phased.state());
        attributes.put("currentDayNumber", phased.currentDay());
        attributes.put("totalDayCount", phased.totalDays());
        return resource("appStoreVersionPhasedReleases", phased.id(), attributes);
    }

    private static Map<String, Object> entityError(String detail) {
        return Map.of(
                "errors", List.of(Map.of("code", "ENTITY_ERROR", "title", "An error occurred", "detail", detail)));
    }

    private String versionIdOf(AppVersionState version) {
        for (Map.Entry<String, AppVersionState> entry : versions.entrySet()) {
            if (entry.getValue() == version) {
                return entry.getKey();
            }
        }
        return "unknown";
    }

    private static String versionIdOfLocalizationPath(String path) {
        return path.substring(
                "/v1/appStoreVersions/".length(), path.length() - "/appStoreVersionLocalizations".length());
    }

    private static String lastSegmentOf(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    private static final JsonMapper MAPPER = new JsonMapper();

    private String buildIdOf(Build build) {
        for (Map.Entry<String, Build> entry : builds.entrySet()) {
            if (entry.getValue() == build) {
                return entry.getKey();
            }
        }
        return "unknown";
    }

    private String groupIdOf(BetaGroupState group) {
        for (Map.Entry<String, BetaGroupState> entry : groups.entrySet()) {
            if (entry.getValue() == group) {
                return entry.getKey();
            }
        }
        return "unknown";
    }

    private static String groupIdOfPath(String path) {
        return path.substring("/v1/betaGroups/".length(), path.indexOf('/', "/v1/betaGroups/".length()));
    }

    private static String queryOf(HttpExchange exchange, String key) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String name = java.net.URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8);
            if (name.equals(key)) {
                return java.net.URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private boolean tokenValid(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return false;
        }
        try {
            String[] parts = authorization.substring("Bearer ".length()).split("\\.");
            String header = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            JsonNode headerNode = MAPPER.readTree(header);
            JsonNode payloadNode = MAPPER.readTree(payload);
            if (!"ES256".equals(headerNode.path("alg").asString())
                    || !keyId.equals(headerNode.path("kid").asString())) {
                return false;
            }
            if (!issuerId.equals(payloadNode.path("iss").asString())
                    || !"asc".equals(payloadNode.path("aud").asString())) {
                return false;
            }
            long now = System.currentTimeMillis() / 1000;
            long iat = payloadNode.path("iat").asLong();
            long exp = payloadNode.path("exp").asLong();
            if (now < iat || now > exp || exp - iat > 20 * 60) {
                return false;
            }
            byte[] raw = Base64.getUrlDecoder().decode(parts[2]);
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(keyPair.getPublic());
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            return verifier.verify(rawToDer(raw));
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] rawToDer(byte[] raw) {
        byte[] r = derInteger(java.util.Arrays.copyOfRange(raw, 0, 32));
        byte[] s = derInteger(java.util.Arrays.copyOfRange(raw, 32, 64));
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(0x30);
        out.write(r.length + s.length);
        out.write(r, 0, r.length);
        out.write(s, 0, s.length);
        return out.toByteArray();
    }

    private static byte[] derInteger(byte[] magnitude) {
        int leading = 0;
        while (leading < magnitude.length - 1 && magnitude[leading] == 0) {
            leading++;
        }
        byte[] minimal = java.util.Arrays.copyOfRange(magnitude, leading, magnitude.length);
        boolean pad = (minimal[0] & 0x80) != 0;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(0x02);
        out.write(minimal.length + (pad ? 1 : 0));
        if (pad) {
            out.write(0);
        }
        out.write(minimal, 0, minimal.length);
        return out.toByteArray();
    }

    private static Map<String, Object> resource(String type, String id, Map<String, Object> attributes) {
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("type", type);
        resource.put("id", id);
        resource.put("attributes", attributes);
        return resource;
    }

    private static Map<String, Object> page(List<Map<String, Object>> data) {
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("data", data);
        page.put("links", Map.of("self", "https://api.test/v1/self"));
        return page;
    }

    private static Map<String, Object> errors(String code, String title) {
        return Map.of("errors", List.of(Map.of("code", code, "title", title)));
    }

    private void answer(HttpExchange exchange, int status, Map<String, Object> body) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** One recorded wire call (assertions' evidence). */
    public record RecordedCall(String method, String path, String body) {}

    private record Build(String appId, String version, String externalState, String internalState) {
        Build withExternal(String state) {
            return new Build(appId, version, state, internalState);
        }
    }

    private static final class BetaGroupState {
        final String appId;
        final String name;
        final List<String> testers = new CopyOnWriteArrayList<>();
        final List<String> builds = new CopyOnWriteArrayList<>();

        BetaGroupState(String appId, String name) {
            this.appId = appId;
            this.name = name;
        }
    }

    /** One App Store production version's mock state (S13-03): the review journey, localizations, and curve. */
    static final class AppVersionState {
        final String appId;
        final String versionString;
        final String state;
        final String releaseType;
        final Map<String, Localization> localizations = new ConcurrentHashMap<>();
        final List<String> rejectionReasons;
        final PhasedRelease phased;

        AppVersionState(String appId, String versionString, String state, String releaseType) {
            this(appId, versionString, state, releaseType, List.of(), null);
        }

        AppVersionState(
                String appId,
                String versionString,
                String state,
                String releaseType,
                List<String> rejectionReasons,
                PhasedRelease phased) {
            this.appId = appId;
            this.versionString = versionString;
            this.state = state;
            this.releaseType = releaseType;
            this.rejectionReasons = rejectionReasons;
            this.phased = phased;
        }

        AppVersionState carrying(AppVersionState prior) {
            // The with* copies replace the state row WHOLE; the
            // localizations a test's version already carries ride the
            // SAME map reference (the wire handlers mutate it in
            // place), never a fresh empty one.
            localizations.putAll(prior.localizations);
            return this;
        }

        AppVersionState withState(String newState, List<String> reasons) {
            return new AppVersionState(appId, versionString, newState, releaseType, reasons, phased).carrying(this);
        }

        AppVersionState withReleaseType(String newReleaseType) {
            return new AppVersionState(appId, versionString, state, newReleaseType, rejectionReasons, phased)
                    .carrying(this);
        }

        AppVersionState withPhased(PhasedRelease newPhased) {
            return new AppVersionState(appId, versionString, state, releaseType, rejectionReasons, newPhased)
                    .carrying(this);
        }

        AppVersionState withPhasedDay(int day) {
            return new AppVersionState(
                            appId,
                            versionString,
                            state,
                            releaseType,
                            rejectionReasons,
                            new PhasedRelease(phased.id(), "ACTIVE", day, phased.totalDays()))
                    .carrying(this);
        }

        String appId() {
            return appId;
        }

        String versionString() {
            return versionString;
        }

        String state() {
            return state;
        }

        String releaseType() {
            return releaseType;
        }

        Map<String, Localization> localizations() {
            return localizations;
        }

        List<String> rejectionReasons() {
            return rejectionReasons;
        }

        PhasedRelease phased() {
            return phased;
        }
    }

    /** One version localization's mock state. */
    private record Localization(String description, String keywords, String whatsNew) {}

    /** The version's phased-release mock state: Apple's fixed 7-day curve. */
    public record PhasedRelease(String id, String state, int currentDay, int totalDays) {}
}
