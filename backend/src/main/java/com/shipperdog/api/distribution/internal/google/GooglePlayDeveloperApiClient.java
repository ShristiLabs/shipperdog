package com.shipperdog.api.distribution.internal.google;

import com.shipperdog.api.config.MetricNames;
import com.shipperdog.api.distribution.AdapterException;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/**
 * The Google Play Developer (Publishing) API client (S13-04, #64,
 * FR-021): the thin REST conversation the Play connector runs — the
 * two-phase edit model (insert an edit, mutate inside it, commit),
 * the AAB media upload to the edit, the track releases (list, read,
 * write), and the per-track tester email lists. Every request carries
 * the OAuth2 bearer ({@link GooglePlayTokenSource}); every failure
 * exits through the adapter taxonomy — 401/403 AUTHENTICATION
 * (retrying a rejected credential is a lockout generator), 429
 * RATE_LIMITED (counted at the provider grain, beside the sandbox's
 * retried backoff), 5xx and transport faults TRANSIENT, the remaining
 * 4xx PERMANENT with Google's own error message carried verbatim.
 *
 * <p>Wire shapes are Google's REST conventions — flat resource JSON,
 * tracks carrying {@code releases} arrays whose {@code versionCodes}
 * are JSON strings, errors answering Google's {@code {"error": ...}}
 * body. The class is transport-only: no platform tables, no policy —
 * the adapter and the group service decide what a call's answer
 * MEANS.
 */
@Component
public class GooglePlayDeveloperApiClient {

    private static final Logger log = LoggerFactory.getLogger(GooglePlayDeveloperApiClient.class);

    /**
     * The provider label this client's failures and telemetry carry:
     * Google throttles the project's quota, not the calling channel —
     * the counter is provider-grain, the Apple precedent.
     */
    private static final String PROVIDER = "google-play";

    private final RestClient http;
    private final GooglePlayTokenSource tokens;
    private final MeterRegistry meters;
    private final String baseUrl;
    private final String mediaBaseUrl;

    public GooglePlayDeveloperApiClient(
            com.shipperdog.api.distribution.internal.config.DistributionProperties properties,
            GooglePlayTokenSource tokens,
            MeterRegistry meters) {
        var play = properties.googlePlay();
        java.net.http.HttpClient transport = java.net.http.HttpClient.newBuilder()
                .connectTimeout(
                        java.time.Duration.ofMillis(play.connectTimeout().toMillis()))
                .build();
        org.springframework.http.client.JdkClientHttpRequestFactory factory =
                new org.springframework.http.client.JdkClientHttpRequestFactory(transport);
        factory.setReadTimeout(play.readTimeout());
        this.http = RestClient.builder().requestFactory(factory).build();
        this.tokens = tokens;
        this.meters = meters;
        this.baseUrl = trimSlash(play.baseUrl());
        // The media upload prefix: Google serves binary uploads under
        // /upload/androidpublisher/v3/... on the same host as the API.
        URI base = URI.create(this.baseUrl);
        this.mediaBaseUrl = base.getScheme() + "://" + base.getAuthority() + "/upload" + base.getPath();
    }

    /** Inserts one edit (the two-phase model's open): the container every mutation rides inside. */
    public AppEdit insertEdit(GooglePlayCredentials credential, String packageName) {
        JsonNode edit = exchange(
                credential,
                () -> http.post()
                        .uri(URI.create(baseUrl + "/" + enc(packageName) + "/edits"))
                        .headers(headers -> headers.setBearerAuth(tokens.tokenOf(credential)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of())
                        .retrieve()
                        .body(JsonNode.class));
        return new AppEdit(
                edit.path("id").asString(null), edit.path("expiryTimeSeconds").asString(null));
    }

    /** Commits one edit (the two-phase model's close): the mutations go live. */
    public AppEdit commitEdit(GooglePlayCredentials credential, String packageName, String editId) {
        JsonNode edit = exchange(
                credential,
                () -> http.post()
                        .uri(URI.create(baseUrl + "/" + enc(packageName) + "/edits/" + enc(editId) + ":commit"))
                        .headers(headers -> headers.setBearerAuth(tokens.tokenOf(credential)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of())
                        .retrieve()
                        .body(JsonNode.class));
        return new AppEdit(
                edit.path("id").asString(null), edit.path("expiryTimeSeconds").asString(null));
    }

    /**
     * Uploads one AAB into the edit (the media endpoint, AC2): the
     * binary streams from the command's content supplier — never
     * buffered whole — and the answer carries the provider-assigned
     * version code the track release will reference.
     */
    public UploadedBundle uploadBundle(
            GooglePlayCredentials credential, String packageName, String editId, InputStream content, long size) {
        InputStreamResource streaming = new InputStreamResource(content) {
            @Override
            public long contentLength() {
                return size;
            }
        };
        JsonNode bundle = exchange(
                credential,
                () -> http.post()
                        .uri(URI.create(mediaBaseUrl + "/" + enc(packageName) + "/edits/" + enc(editId)
                                + "/bundles?uploadType=media"))
                        .headers(headers -> headers.setBearerAuth(tokens.tokenOf(credential)))
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .body(streaming)
                        .retrieve()
                        .body(JsonNode.class));
        return new UploadedBundle(
                bundle.path("versionCode").asLong(), bundle.path("sha1").asString(null));
    }

    /** The edit's tracks (the track model the mapping console reads). */
    public List<Track> tracksOfEdit(GooglePlayCredentials credential, String packageName, String editId) {
        JsonNode list = exchange(
                credential,
                () -> http.get()
                        .uri(URI.create(baseUrl + "/" + enc(packageName) + "/edits/" + enc(editId) + "/tracks"))
                        .headers(headers -> headers.setBearerAuth(tokens.tokenOf(credential)))
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .body(JsonNode.class));
        List<Track> tracks = new ArrayList<>();
        for (JsonNode track : list.path("tracks")) {
            tracks.add(trackOf(track));
        }
        return tracks;
    }

    /** One edit track by name (the read half of the track write). */
    public Track trackOfEdit(GooglePlayCredentials credential, String packageName, String editId, String track) {
        JsonNode answer = exchange(
                credential,
                () -> http.get()
                        .uri(URI.create(
                                baseUrl + "/" + enc(packageName) + "/edits/" + enc(editId) + "/tracks/" + enc(track)))
                        .headers(headers -> headers.setBearerAuth(tokens.tokenOf(credential)))
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .body(JsonNode.class));
        return trackOf(answer);
    }

    /** Writes one edit track (the track release assignment, AC2): the releases array replaces the track's. */
    public Track putTrack(GooglePlayCredentials credential, String packageName, String editId, Track track) {
        Map<String, Object> body = bodyOf(track);
        JsonNode answer = exchange(
                credential,
                () -> http.put()
                        .uri(URI.create(baseUrl + "/" + enc(packageName) + "/edits/" + enc(editId) + "/tracks/"
                                + enc(track.track())))
                        .headers(headers -> headers.setBearerAuth(tokens.tokenOf(credential)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(JsonNode.class));
        return trackOf(answer);
    }

    /** The track's tester email list (the sync surface's read half, AC4). */
    public List<String> testerEmailsOfTrack(
            GooglePlayCredentials credential, String packageName, String editId, String track) {
        JsonNode answer = exchange(
                credential,
                () -> http.get()
                        .uri(URI.create(
                                baseUrl + "/" + enc(packageName) + "/edits/" + enc(editId) + "/testers/" + enc(track)))
                        .headers(headers -> headers.setBearerAuth(tokens.tokenOf(credential)))
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .body(JsonNode.class));
        List<String> emails = new ArrayList<>();
        for (JsonNode email : answer.path("emails")) {
            emails.add(email.asString());
        }
        return emails;
    }

    /**
     * Writes the track's tester email list (the sync surface's write
     * half, AC4): the list REPLACES the track's — add and remove both
     * reflect, the Play contract the story's sync doctrine names.
     */
    public void putTesterEmailsOfTrack(
            GooglePlayCredentials credential, String packageName, String editId, String track, List<String> emails) {
        exchange(
                credential,
                () -> http.put()
                        .uri(URI.create(
                                baseUrl + "/" + enc(packageName) + "/edits/" + enc(editId) + "/testers/" + enc(track)))
                        .headers(headers -> headers.setBearerAuth(tokens.tokenOf(credential)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("emails", emails))
                        .retrieve()
                        .body(JsonNode.class));
    }

    /**
     * The app's LIVE track by name (the committed side, post-edit):
     * the read the record refresh runs — the track's releases with
     * Google's own status words.
     */
    public Track liveTrackOf(GooglePlayCredentials credential, String packageName, String track) {
        JsonNode answer = exchange(
                credential,
                () -> http.get()
                        .uri(URI.create(baseUrl + "/" + enc(packageName) + "/tracks/" + enc(track)))
                        .headers(headers -> headers.setBearerAuth(tokens.tokenOf(credential)))
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .body(JsonNode.class));
        return trackOf(answer);
    }

    private JsonNode exchange(GooglePlayCredentials credential, Call call) {
        try {
            JsonNode answer = call.execute();
            return answer == null ? tools.jackson.databind.node.JsonNodeFactory.instance.objectNode() : answer;
        } catch (RestClientResponseException e) {
            throw taxonomyOf(e);
        } catch (AdapterException e) {
            throw e;
        } catch (RuntimeException e) {
            // Transport fault (connect refused, read timeout): the honest
            // TRANSIENT, with the fault's identity logged for the operator.
            log.warn(
                    "the Google Play Developer API call failed in transport: {} ({})",
                    e.getMessage(),
                    e.getCause() == null
                            ? e.getClass().getSimpleName()
                            : e.getCause().toString());
            throw new AdapterException(
                    AdapterException.Category.TRANSIENT,
                    PROVIDER,
                    "the Google Play Developer API call failed: " + e.getClass().getSimpleName());
        }
    }

    /**
     * The taxonomy mapping of a Play HTTP failure (the closed set the
     * sandbox retries on). Google answers its own error body; the
     * message rides PERMANENT verbatim — the actionable reason a
     * refused track write carries.
     */
    private AdapterException taxonomyOf(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        if (status == 401 || status == 403) {
            return new AdapterException(
                    AdapterException.Category.AUTHENTICATION,
                    PROVIDER,
                    "Google rejected the service account (HTTP " + status + "): " + errorDetailOf(e));
        }
        if (status == 429) {
            meters.counter(MetricNames.ADAPTER_RATE_LIMIT, "provider", PROVIDER).increment();
            return new AdapterException(
                    AdapterException.Category.RATE_LIMITED, PROVIDER, "Google throttled the call (HTTP 429)");
        }
        if (status >= 500) {
            return new AdapterException(
                    AdapterException.Category.TRANSIENT,
                    PROVIDER,
                    "the Play Developer API failed (HTTP " + status + ")");
        }
        return new AdapterException(
                AdapterException.Category.PERMANENT,
                PROVIDER,
                "Google refused the request (HTTP " + status + "): " + errorDetailOf(e));
    }

    /** Google's own error message from its {@code error.message} body; the status text when the body carries none. */
    private static String errorDetailOf(RestClientResponseException e) {
        try {
            JsonNode error = MAPPER.readTree(e.getResponseBodyAsString()).path("error");
            String message = error.path("message").asString(null);
            if (message != null && !message.isBlank()) {
                return message;
            }
        } catch (RuntimeException ignored) {
            // A non-JSON error body (a proxy's HTML, an empty answer):
            // the status line below is the honest fallback.
        }
        return e.getStatusText();
    }

    private static Track trackOf(JsonNode node) {
        List<TrackRelease> releases = new ArrayList<>();
        for (JsonNode release : node.path("releases")) {
            List<Long> versionCodes = new ArrayList<>();
            for (JsonNode code : release.path("versionCodes")) {
                versionCodes.add(code.asLong());
            }
            List<ReleaseNote> notes = new ArrayList<>();
            for (JsonNode note : release.path("releaseNotes")) {
                notes.add(new ReleaseNote(
                        note.path("language").asString(null), note.path("text").asString(null)));
            }
            releases.add(new TrackRelease(
                    release.path("name").asString(null),
                    List.copyOf(versionCodes),
                    List.copyOf(notes),
                    release.path("status").asString(null)));
        }
        return new Track(node.path("track").asString(null), List.copyOf(releases));
    }

    private static Map<String, Object> bodyOf(Track track) {
        List<Map<String, Object>> releases = new ArrayList<>();
        for (TrackRelease release : track.releases()) {
            Map<String, Object> body = new LinkedHashMap<>();
            if (release.name() != null) {
                body.put("name", release.name());
            }
            body.put(
                    "versionCodes",
                    release.versionCodes().stream().map(String::valueOf).toList());
            if (!release.releaseNotes().isEmpty()) {
                body.put(
                        "releaseNotes",
                        release.releaseNotes().stream()
                                .map(note -> Map.of("language", note.language(), "text", note.text()))
                                .toList());
            }
            body.put("status", release.status());
            releases.add(body);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("track", track.track());
        body.put("releases", releases);
        return body;
    }

    private static String enc(String segment) {
        return java.net.URLEncoder.encode(segment, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String trimSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    @FunctionalInterface
    private interface Call {
        JsonNode execute();
    }

    /** One edit: Google's container token (the two-phase upload's handle). */
    record AppEdit(String id, String expiryTimeSeconds) {}

    /** One uploaded bundle: the provider-assigned version code the track release references. */
    record UploadedBundle(long versionCode, String sha1) {}

    /** One track with its releases (the model the mapping console and the refresh read). */
    record Track(String track, List<TrackRelease> releases) {}

    /**
     * One track release: the release's name, its version codes, its
     * per-language release notes, and Google's own status word
     * ({@code completed}, {@code inProgress}, {@code draft},
     * {@code halted}) the record carries verbatim.
     */
    record TrackRelease(String name, List<Long> versionCodes, List<ReleaseNote> releaseNotes, String status) {}

    /** One language's release note. */
    record ReleaseNote(String language, String text) {}

    private static final tools.jackson.databind.json.JsonMapper MAPPER = new tools.jackson.databind.json.JsonMapper();
}
