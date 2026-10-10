package com.shipperdog.api.notification.internal.service;

import com.shipperdog.api.notification.DomainEvent;
import com.shipperdog.api.notification.internal.bus.NotificationProperties;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * The webhook channel's HTTP leg (issue #51, S12-01, AC5): one signed
 * POST per delivery attempt. The body is the canonical event JSON
 * (the PRD 51 attribute set -- fixed key order, so the bytes a
 * receiver re-reads for verification are the bytes that were signed);
 * the signature is HMAC-SHA256 over {@code timestamp + "." + body}
 * with the subscription's secret, sent as {@code
 * X-Shipperdog-Signature: sha256=<hex>} beside the {@code
 * X-Shipperdog-Timestamp} and {@code X-Shipperdog-Event} headers --
 * the GitHub-webhook recipe receivers already know how to verify
 * (constant-time compare; docs/notifications.md carries the worked
 * example). Any 2xx is success; anything else -- non-2xx status,
 * connect failure, timeout -- is one failed attempt for the retry
 * ledger, never an exception into the dispatcher's flow.
 */
@Component
class WebhookDeliveryClient {

    /** The signature header's name. */
    static final String SIGNATURE_HEADER = "X-Shipperdog-Signature";

    /** The timestamp header's name (the signature's first input). */
    static final String TIMESTAMP_HEADER = "X-Shipperdog-Timestamp";

    /** The event-name header's name. */
    static final String EVENT_HEADER = "X-Shipperdog-Event";

    /** Renders the canonical body bytes (fixed key order; the receiver-facing contract). */
    private static final ObjectMapper CANONICAL = new ObjectMapper();

    private final RestClient http;

    private final Clock clock;

    WebhookDeliveryClient(NotificationProperties properties, Clock clock) {
        SimpleClientHttpRequestFactory factory = new NoRedirectRequestFactory();
        factory.setConnectTimeout((int) properties.webhookTimeout().toMillis());
        factory.setReadTimeout((int) properties.webhookTimeout().toMillis());
        this.http = RestClient.builder().requestFactory(factory).build();
        this.clock = clock;
    }

    /** One attempt's outcome: success, or the failure a human reads in the log. */
    record Attempt(boolean delivered, @Nullable String error) {

        static Attempt success() {
            return new Attempt(true, null);
        }

        static Attempt failure(String error) {
            return new Attempt(false, error);
        }
    }

    /** Posts the canonical event JSON to the URL, signed with the secret. Never throws. */
    Attempt deliver(String url, String secret, DomainEvent event) {
        return deliver(url, secret, event.name().name(), canonicalBody(event));
    }

    /**
     * Posts the given body bytes to the URL, signed with the secret:
     * the WEBHOOK channel passes the canonical event JSON, the SLACK
     * and TEAMS channels (S12-02) pass their provider-rendered message
     * -- same wire discipline (signature over the exact bytes, no
     * redirects, any 2xx is success) for every body shape.
     */
    Attempt deliver(String url, String secret, String eventName, byte[] body) {
        String timestamp = Long.toString(Instant.now(clock).getEpochSecond());
        String signature;
        try {
            signature = sign(secret, timestamp, body);
        } catch (RuntimeException e) {
            return Attempt.failure("signing failed: " + e.getClass().getSimpleName());
        }
        try {
            http.post()
                    .uri(URI.create(url))
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header(EVENT_HEADER, eventName)
                    .header(TIMESTAMP_HEADER, timestamp)
                    .header(SIGNATURE_HEADER, signature)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return Attempt.success();
        } catch (RuntimeException e) {
            return Attempt.failure(describe(e));
        }
    }

    /**
     * The canonical body: the PRD 51 attribute set in a fixed key
     * order -- snake_case member names, the receiver-facing contract
     * frozen with the event vocabulary (docs/notifications.md).
     */
    static byte[] canonicalBody(DomainEvent event) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event_id", event.eventId().toString());
        body.put("name", event.name().name());
        body.put("organization_id", event.organizationId().toString());
        body.put("tenant_id", event.tenantId() == null ? null : event.tenantId().toString());
        body.put(
                "application_id",
                event.applicationId() == null ? null : event.applicationId().toString());
        body.put(
                "version_id",
                event.versionId() == null ? null : event.versionId().toString());
        body.put(
                "artifact_id",
                event.artifactId() == null ? null : event.artifactId().toString());
        body.put(
                "release_id",
                event.releaseId() == null ? null : event.releaseId().toString());
        body.put("correlation_id", event.correlationId());
        body.put("occurred_at", event.occurredAt().toString());
        body.put("payload", event.payload());
        return CANONICAL.writeValueAsBytes(body);
    }

    /** HMAC-SHA256 over {@code timestamp + "." + body}, hex-encoded with the scheme prefix. */
    static String sign(String secret, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '.');
            byte[] digest = mac.doFinal(body);
            StringBuilder hex = new StringBuilder(2 * digest.length);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return "sha256=" + hex;
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private static String describe(RuntimeException e) {
        String message = e.getMessage();
        String rendered =
                message == null ? e.getClass().getSimpleName() : e.getClass().getSimpleName() + ": " + message;
        return rendered.length() > 256 ? rendered.substring(0, 256) : rendered;
    }

    /**
     * Redirects are never followed: a receiver's 3xx must surface as
     * that status (one failed attempt), never bounce the delivery to
     * a host the target policy never vetted (the SSRF posture's
     * delivery-side half; the JDK client would only auto-follow after
     * GET/HEAD anyway -- this pins it).
     */
    private static final class NoRedirectRequestFactory extends SimpleClientHttpRequestFactory {

        @Override
        protected void prepareConnection(java.net.HttpURLConnection connection, String httpMethod)
                throws java.io.IOException {
            super.prepareConnection(connection, httpMethod);
            connection.setInstanceFollowRedirects(false);
        }
    }
}
