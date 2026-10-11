package com.shipperdog.api.distribution.internal.google;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;

/**
 * The contract-faithful mock Google Play Developer API server (issue
 * #64, S13-04): Google's REST shapes — edits, the AAB media upload,
 * track releases, tester email lists — served from an in-memory state
 * a test drives, over the repo's mock precedent (the JDK's own {@code
 * HttpServer}, no new dependency). The mock OWNS the credential's
 * truth: it generates the RSA key pair, hands the SERVICE-ACCOUNT
 * JSON to the binding fixture, and VERIFIES the OAuth2 conversation
 * exactly like Google would — the /token endpoint checks the RS256
 * assertion's signature under the public key, the issuer, the
 * audience, the {@code androidpublisher} scope, and the one-hour
 * window before it mints a bearer, and every API request must carry
 * a bearer the endpoint minted. A request without a valid token
 * answers Google's 401 exactly like the real API would.
 *
 * <p>Scenario knobs answer the failure taxonomy: reject the next
 * assertion (400 invalid_grant), reject the next bearer (401),
 * throttle the next calls (429 with {@code Retry-After}), fail the
 * next call server-side (500). The track-release journey is drivable
 * (a closed track's release lands {@code inProgress} — in review —
 * and the knob completes or fails it). The request log records every
 * call a test asserts on.
 */
public final class GooglePlayDeveloperApiMock {

    private final HttpServer server;
    private final String clientEmail =
            "play-mock@" + UUID.randomUUID().toString().substring(0, 8) + ".iam.gserviceaccount.com";
    /** Every trusted public key: one per service-account document the mock minted (rotation's proof). */
    private final Set<java.security.PublicKey> trustedKeys = ConcurrentHashMap.newKeySet();

    // The in-memory Play state.
    private final Map<String, AppState> apps = new ConcurrentHashMap<>();
    private final Map<String, String> editsOfPackage = new ConcurrentHashMap<>();
    /**
     * The live bearers the /token endpoint minted. Deliberately NOT
     * cleared by reset(): a bearer's validity is the mock instance's
     * own lifetime (the connector's token cache lives in the Spring
     * context across tests, and the mock's crypto does not roll).
     */
    private final Set<String> liveTokens = ConcurrentHashMap.newKeySet();
    /** Every uploaded bundle: the wire evidence a test asserts on. */
    private final List<Uploaded> uploads = new CopyOnWriteArrayList<>();

    private final AtomicInteger idCounter = new AtomicInteger(1000);

    // The scenario knobs and the request log.
    private volatile boolean rejectNextAssertion = false;
    private volatile boolean failNextTokenServerSide = false;
    private volatile boolean rejectNextBearer = false;
    private volatile boolean throttleNextCall = false;
    private final AtomicInteger throttleBudget = new AtomicInteger(0);
    private volatile boolean failNextCallServerSide = false;
    private volatile boolean rejectNextTrackWrite = false;
    private final List<RecordedCall> calls = new CopyOnWriteArrayList<>();

    public GooglePlayDeveloperApiMock() {
        try {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.server.createContext("/", this::handle);
            this.server.start();
        } catch (IOException e) {
            throw new IllegalStateException("the mock Play server could not start", e);
        }
    }

    public void stop() {
        server.stop(0);
    }

    /** The between-tests reset: the wire log and the in-memory state start every test clean. */
    public void reset() {
        calls.clear();
        apps.clear();
        editsOfPackage.clear();
        uploads.clear();
        rejectNextAssertion = false;
        failNextTokenServerSide = false;
        rejectNextBearer = false;
        throttleNextCall = false;
        failNextCallServerSide = false;
        rejectNextTrackWrite = false;
    }

    /** The base URL the Play connector's property points at (the API root ends with the applications path). */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/androidpublisher/v3/applications";
    }

    /**
     * The binding fixture's material: the whole service-account JSON
     * document, minting a FRESH RSA key every call (test-only). A
     * second call is a ROTATION by construction — a different key the
     * mock trusts, so a rotated binding's first exchange mints a new
     * bearer and the cache's old entry never answers again.
     */
    public String serviceAccountJson() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            trustedKeys.add(pair.getPublic());
            String pem = "-----BEGIN PRIVATE KEY-----\n"
                    + Base64.getMimeEncoder().encodeToString(pair.getPrivate().getEncoded())
                    + "\n-----END PRIVATE KEY-----\n";
            return "{\n"
                    + "  \"type\": \"service_account\",\n"
                    + "  \"client_email\": \""
                    + clientEmail
                    + "\",\n"
                    + "  \"private_key\": "
                    + jsonString(pem)
                    + ",\n"
                    + "  \"token_uri\": \"http://127.0.0.1:"
                    + server.getAddress().getPort()
                    + "/token\"\n"
                    + "}\n";
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("the mock could not mint a service account", e);
        }
    }

    /** Registers an app for a package name (the Play Console's app under the service account). */
    public void registerApp(String packageName) {
        apps.put(packageName, new AppState());
    }

    /** Moves a track release's status (the refresh scenarios: completed, failed, halted, ...). */
    public void setTrackReleaseStatus(String packageName, String track, long versionCode, String status) {
        AppState app = apps.get(packageName);
        if (app == null) {
            return;
        }
        TrackState trackState = app.tracks.get(track);
        if (trackState == null) {
            return;
        }
        for (ReleaseState release : trackState.releases) {
            if (release.versionCodes.contains(versionCode)) {
                release.status = status;
            }
        }
    }

    /** The track's tester emails as the mock holds them (assertions' evidence). */
    public List<String> testerEmailsOf(String packageName, String track) {
        AppState app = apps.get(packageName);
        if (app == null) {
            return List.of();
        }
        TrackState trackState = app.tracks.get(track);
        return trackState == null ? List.of() : List.copyOf(trackState.testers);
    }

    /** The track's releases as the mock holds them (the listing assertions' evidence). */
    public List<Map<String, Object>> releasesOf(String packageName, String track) {
        AppState app = apps.get(packageName);
        if (app == null) {
            return List.of();
        }
        TrackState trackState = app.tracks.get(track);
        if (trackState == null) {
            return List.of();
        }
        List<Map<String, Object>> releases = new ArrayList<>();
        for (ReleaseState release : trackState.releases) {
            releases.add(releaseBody(release));
        }
        return releases;
    }

    public void rejectNextAssertion() {
        rejectNextAssertion = true;
    }

    /** The token endpoint's own 500 (the TRANSIENT/ AUTHENTICATION taxonomy boundary). */
    public void failNextTokenServerSide() {
        failNextTokenServerSide = true;
    }

    public void rejectNextBearer() {
        rejectNextBearer = true;
    }

    public void throttleNextCall() {
        throttleNextCall = true;
    }

    /** Throttles the next N calls (the exhaustion path: every sandbox attempt eats one). */
    public void throttleNextCalls(int count) {
        throttleBudget.set(count);
    }

    public void failNextCallServerSide() {
        failNextCallServerSide = true;
    }

    /** The refused-write knob: the next track write answers Google's 400 with an actionable message. */
    public void rejectNextTrackWrite() {
        rejectNextTrackWrite = true;
    }

    /** Every recorded wire call: method, path, and the body a test asserts on. */
    public List<RecordedCall> calls() {
        return List.copyOf(calls);
    }

    /**
     * Clears the wire log WITHOUT touching the provider state: a test
     * whose fixtures dial legitimately (the group link validates the
     * track through a real edit) scopes its "the dispatch never
     * dialed" assertions to the calls that follow.
     */
    public void clearCalls() {
        calls.clear();
    }

    public List<RecordedCall> callsOf(String method, String pathContains) {
        return calls.stream()
                .filter(call -> call.method().equals(method) && call.path().contains(pathContains))
                .toList();
    }

    /** Every uploaded bundle (the upload's wire evidence: package, bytes, the assigned version code). */
    public List<Uploaded> uploads() {
        return List.copyOf(uploads);
    }

    // -------------------------------------------------------------------
    // The wire
    // -------------------------------------------------------------------

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/upload/")) {
                path = path.substring("/upload".length());
            }
            String method = exchange.getRequestMethod();
            byte[] rawBody = exchange.getRequestBody().readAllBytes();
            calls.add(new RecordedCall(
                    method,
                    path,
                    rawBody.length > 512
                            ? "(media " + rawBody.length + " bytes)"
                            : new String(rawBody, StandardCharsets.UTF_8)));

            if (path.equals("/token")) {
                if (failNextTokenServerSide) {
                    failNextTokenServerSide = false;
                    answer(exchange, 500, googleError(500, "Backend error"));
                    return;
                }
                handleToken(exchange, new String(rawBody, StandardCharsets.UTF_8));
                return;
            }
            if (rejectNextBearer) {
                rejectNextBearer = false;
                answer(exchange, 401, googleError(401, "Request had invalid authentication credentials"));
                return;
            }
            if (!bearerValid(exchange.getRequestHeaders().getFirst("Authorization"))) {
                answer(exchange, 401, googleError(401, "Request had invalid authentication credentials"));
                return;
            }
            if (throttleNextCall || throttleBudget.getAndUpdate(n -> n > 0 ? n - 1 : n) > 0) {
                throttleNextCall = false;
                exchange.getResponseHeaders().set("Retry-After", "1");
                answer(exchange, 429, googleError(429, "Rate limit exceeded"));
                return;
            }
            if (failNextCallServerSide) {
                failNextCallServerSide = false;
                answer(exchange, 500, googleError(500, "Internal error"));
                return;
            }

            // The API routes: /androidpublisher/v3/applications/{pkg}/...
            String[] segments = path.split("/");
            // ["", "androidpublisher", "v3", "applications", pkg, ...]
            if (segments.length < 5 || !"applications".equals(segments[3])) {
                answer(exchange, 404, googleError(404, "not found: " + path));
                return;
            }
            String packageName = segments[4];
            AppState app = apps.get(packageName);
            if (app == null) {
                answer(exchange, 404, googleError(404, "Application not found."));
                return;
            }
            String rest = String.join("/", java.util.Arrays.copyOfRange(segments, 5, segments.length));
            if ("edits".equals(rest) && "POST".equals(method)) {
                handleInsertEdit(exchange, packageName);
            } else if (rest.matches("edits/[^/]+") && "POST".equals(method)) {
                // :commit (the colon form URL-decodes into the segment)
                handleCommit(exchange, app);
            } else if (rest.matches("edits/[^/]+/bundles") && "POST".equals(method)) {
                handleUploadBundle(exchange, app, rawBody);
            } else if (rest.matches("edits/[^/]+/tracks") && "GET".equals(method)) {
                handleListTracks(exchange, app);
            } else if (rest.matches("edits/[^/]+/tracks/[^/]+") && "GET".equals(method)) {
                handleGetTrack(exchange, app, lastSegment(rest));
            } else if (rest.matches("edits/[^/]+/tracks/[^/]+") && "PUT".equals(method)) {
                handlePutTrack(exchange, app, lastSegment(rest), new String(rawBody, StandardCharsets.UTF_8));
            } else if (rest.matches("edits/[^/]+/testers/[^/]+") && "GET".equals(method)) {
                handleGetTesters(exchange, app, lastSegment(rest));
            } else if (rest.matches("edits/[^/]+/testers/[^/]+") && "PUT".equals(method)) {
                handlePutTesters(exchange, app, lastSegment(rest), new String(rawBody, StandardCharsets.UTF_8));
            } else if (rest.matches("tracks/[^/]+") && "GET".equals(method)) {
                handleGetTrack(exchange, app, lastSegment(rest));
            } else {
                answer(exchange, 404, googleError(404, "not found: " + path));
            }
        } finally {
            exchange.close();
        }
    }

    /** The OAuth2 JWT-bearer exchange: verify Google's model, mint the bearer. */
    private void handleToken(HttpExchange exchange, String form) throws IOException {
        Map<String, String> pairs = new LinkedHashMap<>();
        for (String pair : form.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0) {
                pairs.put(
                        java.net.URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
            }
        }
        String assertion = pairs.get("assertion");
        if (!"urn:ietf:params:oauth:grant-type:jwt-bearer".equals(pairs.get("grant_type"))
                || assertion == null
                || rejectNextAssertion
                || !assertionValid(assertion)) {
            rejectNextAssertion = false;
            answer(
                    exchange,
                    400,
                    Map.of("error", "invalid_grant", "error_description", "Invalid JWT: failed validation"));
            return;
        }
        String token = "mock-bearer-" + idCounter.incrementAndGet();
        liveTokens.add(token);
        answer(exchange, 200, Map.of("access_token", token, "expires_in", 3600, "token_type", "Bearer"));
    }

    /** Google's assertion model verified: RS256 signature, iss, aud, scope, the one-hour window. */
    private boolean assertionValid(String assertion) {
        try {
            String[] parts = assertion.split("\\.");
            String header = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            JsonNode headerNode = MAPPER.readTree(header);
            JsonNode payloadNode = MAPPER.readTree(payload);
            if (!"RS256".equals(headerNode.path("alg").asString())) {
                return false;
            }
            if (!clientEmail.equals(payloadNode.path("iss").asString())
                    || !tokenUri().equals(payloadNode.path("aud").asString())
                    || !"https://www.googleapis.com/auth/androidpublisher"
                            .equals(payloadNode.path("scope").asString())) {
                return false;
            }
            long now = System.currentTimeMillis() / 1000;
            long iat = payloadNode.path("iat").asLong();
            long exp = payloadNode.path("exp").asLong();
            if (now < iat || now > exp || exp - iat > 3600) {
                return false;
            }
            Signature verifier = Signature.getInstance("SHA256withRSA");
            byte[] signingInput = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);
            byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
            for (java.security.PublicKey trusted : trustedKeys) {
                verifier.initVerify(trusted);
                verifier.update(signingInput);
                if (verifier.verify(signature)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private void handleInsertEdit(HttpExchange exchange, String packageName) throws IOException {
        String editId = "edit-" + idCounter.incrementAndGet();
        editsOfPackage.put(packageName, editId);
        answer(
                exchange,
                200,
                Map.of("id", editId, "expiryTimeSeconds", String.valueOf(System.currentTimeMillis() / 1000 + 600)));
    }

    private void handleCommit(HttpExchange exchange, AppState app) throws IOException {
        // The two-phase close: the internal track serves immediately (no
        // review — the story's own note); the closed tracks hold their
        // releases IN REVIEW until the knob moves them (the refresh
        // journey a test drives).
        for (TrackState track : app.tracks.values()) {
            for (ReleaseState release : track.releases) {
                if (!"internal".equals(track.name) && "completed".equals(release.status)) {
                    release.status = "inProgress";
                }
            }
        }
        answer(
                exchange,
                200,
                Map.of(
                        "id",
                        "edit-" + idCounter.incrementAndGet(),
                        "expiryTimeSeconds",
                        String.valueOf(System.currentTimeMillis() / 1000 + 600)));
    }

    private void handleUploadBundle(HttpExchange exchange, AppState app, byte[] body) throws IOException {
        if (body.length == 0) {
            answer(exchange, 400, googleError(400, "The media upload was empty"));
            return;
        }
        long versionCode = app.versionCodes.incrementAndGet();
        app.knownVersionCodes.add(versionCode);
        uploads.add(new Uploaded(versionCode, body.length, sha1Of(body)));
        answer(exchange, 200, Map.of("versionCode", versionCode, "sha1", sha1Of(body)));
    }

    private void handleListTracks(HttpExchange exchange, AppState app) throws IOException {
        List<Map<String, Object>> tracks = new ArrayList<>();
        for (TrackState track : app.tracks.values()) {
            tracks.add(trackBody(track));
        }
        answer(exchange, 200, Map.of("tracks", tracks));
    }

    private void handleGetTrack(HttpExchange exchange, AppState app, String trackName) throws IOException {
        TrackState track = app.tracks.get(trackName);
        if (track == null) {
            answer(exchange, 404, googleError(404, "Track not found."));
            return;
        }
        answer(exchange, 200, trackBody(track));
    }

    private void handlePutTrack(HttpExchange exchange, AppState app, String trackName, String body) throws IOException {
        TrackState track = app.tracks.get(trackName);
        if (track == null) {
            answer(exchange, 404, googleError(404, "Track not found."));
            return;
        }
        if (rejectNextTrackWrite) {
            rejectNextTrackWrite = false;
            answer(
                    exchange,
                    400,
                    googleError(400, "Release note text cannot exceed 500 characters for language en-US"));
            return;
        }
        JsonNode request = MAPPER.readTree(body);
        track.releases.clear();
        for (JsonNode release : request.path("releases")) {
            List<Long> versionCodes = new ArrayList<>();
            for (JsonNode code : release.path("versionCodes")) {
                long versionCode = Long.parseLong(code.asString());
                if (!app.knownVersionCodes.contains(versionCode)) {
                    answer(exchange, 400, googleError(400, "Version code " + versionCode + " has no uploaded bundle"));
                    return;
                }
                versionCodes.add(versionCode);
            }
            ReleaseState state = new ReleaseState();
            state.name = release.path("name").asString(null);
            state.status = release.path("status").asString("draft");
            state.versionCodes.addAll(versionCodes);
            for (JsonNode note : release.path("releaseNotes")) {
                if (note.path("text").asString("").length() > 500) {
                    answer(
                            exchange,
                            400,
                            googleError(
                                    400,
                                    "Release note text cannot exceed 500 characters for language "
                                            + note.path("language").asString()));
                    return;
                }
                state.releaseNotes.add(Map.of(
                        "language", note.path("language").asString(),
                        "text", note.path("text").asString()));
            }
            track.releases.add(state);
        }
        answer(exchange, 200, trackBody(track));
    }

    private void handleGetTesters(HttpExchange exchange, AppState app, String trackName) throws IOException {
        TrackState track = app.tracks.get(trackName);
        if (track == null) {
            answer(exchange, 404, googleError(404, "Track not found."));
            return;
        }
        answer(exchange, 200, Map.of("emails", List.copyOf(track.testers)));
    }

    private void handlePutTesters(HttpExchange exchange, AppState app, String trackName, String body)
            throws IOException {
        TrackState track = app.tracks.get(trackName);
        if (track == null) {
            answer(exchange, 404, googleError(404, "Track not found."));
            return;
        }
        track.testers.clear();
        for (JsonNode email : MAPPER.readTree(body).path("emails")) {
            track.testers.add(email.asString());
        }
        answer(exchange, 200, Map.of("emails", List.copyOf(track.testers)));
    }

    private boolean bearerValid(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return false;
        }
        return liveTokens.contains(authorization.substring("Bearer ".length()));
    }

    private String tokenUri() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/token";
    }

    private Map<String, Object> trackBody(TrackState track) {
        List<Map<String, Object>> releases = new ArrayList<>();
        for (ReleaseState release : track.releases) {
            releases.add(releaseBody(release));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("track", track.name);
        body.put("releases", releases);
        return body;
    }

    private static Map<String, Object> releaseBody(ReleaseState release) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (release.name != null) {
            body.put("name", release.name);
        }
        body.put(
                "versionCodes",
                release.versionCodes.stream().map(String::valueOf).toList());
        body.put("releaseNotes", List.copyOf(release.releaseNotes));
        body.put("status", release.status);
        return body;
    }

    private static Map<String, Object> googleError(int code, String message) {
        return Map.of("error", Map.of("code", code, "message", message, "status", "FAILED_PRECONDITION"));
    }

    private static String lastSegment(String rest) {
        return rest.substring(rest.lastIndexOf('/') + 1);
    }

    private static final tools.jackson.databind.json.JsonMapper MAPPER = new tools.jackson.databind.json.JsonMapper();

    private static String sha1Of(byte[] body) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-1");
            return Base64.getEncoder().encodeToString(digest.digest(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String jsonString(String value) {
        return "\""
                + value.replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\r", "\\r")
                        .replace("\n", "\\n")
                + "\"";
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

    /** One uploaded bundle (the upload's wire evidence). */
    public record Uploaded(long versionCode, long sizeBytes, String sha1) {}

    /** One app's mock state: its tracks, its version-code counter, the codes bundles minted. */
    private static final class AppState {
        final Map<String, TrackState> tracks = new LinkedHashMap<>();
        final AtomicInteger versionCodes = new AtomicInteger(0);
        final Set<Long> knownVersionCodes = ConcurrentHashMap.newKeySet();

        AppState() {
            for (String track : List.of("internal", "alpha", "beta", "production")) {
                tracks.put(track, new TrackState(track));
            }
        }
    }

    /** One track's mock state: its releases and its tester email list. */
    private static final class TrackState {
        final String name;
        final List<ReleaseState> releases = new CopyOnWriteArrayList<>();
        final List<String> testers = new CopyOnWriteArrayList<>();

        TrackState(String name) {
            this.name = name;
        }
    }

    /** One track release's mutable mock state. */
    private static final class ReleaseState {
        String name;
        String status;
        final List<Long> versionCodes = new CopyOnWriteArrayList<>();
        final List<Map<String, String>> releaseNotes = new CopyOnWriteArrayList<>();
    }
}
