package com.shipperdog.api.integration;

import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.audit.AuditLogger;
import com.shipperdog.api.config.MetricNames;
import com.shipperdog.api.integration.internal.service.AppStoreWebhookService;
import com.shipperdog.api.integration.internal.service.GitHubWebhookService;
import com.shipperdog.api.integration.internal.service.GitLabWebhookService;
import com.shipperdog.api.integration.internal.service.GooglePlayWebhookService;
import com.shipperdog.api.integration.internal.service.JenkinsWebhookService;
import com.shipperdog.api.integration.internal.webhook.AppleJwsVerifier;
import com.shipperdog.api.integration.internal.webhook.GoogleJwtVerifier;
import com.shipperdog.api.integration.internal.webhook.InboundWebhookProperties;
import com.shipperdog.api.integration.internal.webhook.WebhookSignatureVerifier;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The inbound webhook receivers (issue #53, S12-03 and issue #54,
 * S12-04, FR-022, PRD 19): five {@code POST /webhooks/*} routes --
 * deliberately OUTSIDE the {@code /api/v1} bearer surface, because
 * each delivery's own provider authentication IS the request's
 * authentication (an HMAC shared secret, a provider token, a
 * provider-signed JWS, or a Google-issued OIDC token; the verifier
 * is the authority, the same character as the download proxy's HMAC
 * token). The generic framework underneath -- constant-time
 * verification over rotating credential lists, the timestamp-window
 * replay seam, the delivery-id ledger, the body ceiling -- is the
 * surface E13's connectors reuse next.
 *
 * <p><b>The two uniform-rejection families</b>. The shared-secret
 * family (GitHub, Jenkins, GitLab) answers every authenticity failure
 * the same 401 -- an unauthenticated caller learns nothing about
 * which leg refused or whether the receiver is even on. The store
 * family (App Store, Google Play) answers every authenticity failure
 * the same 400 ({@code WEBHOOK_DELIVERY_REJECTED}, per S12-04's
 * acceptance criteria) -- same uniformity, the status its providers'
 * contracts expect. In both families a verified sender's unreadable
 * body answers 400 PAYLOAD_MALFORMED (the sender needs the
 * diagnostic), everything else is a 200 receipt -- including unknown
 * events and replays (the providers' contract is 2xx-or-retry, and
 * redelivering is their idempotent-transport design).
 *
 * <p><b>Metrics and audit cover every answered delivery</b> (S12-04's
 * hardening): one {@code shipperdog.webhooks.deliveries} increment
 * per terminal answer (provider + outcome) and one audit event per
 * accepted/ignored/duplicate/rejected delivery -- never the
 * credential, never the raw payload; audit detail values carry the
 * same caps as the ledger columns (the S12-03 review's alignment
 * finding), so a hostile header cannot balloon an audit row.
 */
@RestController
public class IntegrationWebhookController {

    private static final Logger log = LoggerFactory.getLogger(IntegrationWebhookController.class);

    /** The audit-detail caps, aligned with the V41 ledger columns (the S12-03 review finding). */
    private static final int AUDIT_EVENT_MAX = 64;

    private static final int AUDIT_DELIVERY_MAX = 255;

    private final WebhookSignatureVerifier verifier;
    private final InboundWebhookProperties properties;
    private final GitHubWebhookService github;
    private final JenkinsWebhookService jenkins;
    private final GitLabWebhookService gitlab;
    private final AppStoreWebhookService appstore;
    private final GooglePlayWebhookService googlePlay;
    private final AppleJwsVerifier appleJws;
    private final GoogleJwtVerifier googleJwt;
    private final AuditLogger audit;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;

    IntegrationWebhookController(
            WebhookSignatureVerifier verifier,
            InboundWebhookProperties properties,
            GitHubWebhookService github,
            JenkinsWebhookService jenkins,
            GitLabWebhookService gitlab,
            AppStoreWebhookService appstore,
            GooglePlayWebhookService googlePlay,
            AppleJwsVerifier appleJws,
            GoogleJwtVerifier googleJwt,
            AuditLogger audit,
            ObjectMapper mapper,
            MeterRegistry meters) {
        this.verifier = verifier;
        this.properties = properties;
        this.github = github;
        this.jenkins = jenkins;
        this.gitlab = gitlab;
        this.appstore = appstore;
        this.googlePlay = googlePlay;
        this.appleJws = appleJws;
        this.googleJwt = googleJwt;
        this.audit = audit;
        this.mapper = mapper;
        this.meters = meters;
    }

    /**
     * Receives one GitHub webhook delivery. Order of the gates: the
     * body ceiling (413), the signature (the uniform 401), the JSON
     * parse (400 for a verified sender), then processing (the 200
     * receipt; duplicates answered from the ledger).
     */
    @PostMapping(value = "/webhooks/github", consumes = MediaType.APPLICATION_JSON_VALUE)
    // Declared permitAll like the download proxy (S8-04): the
    // delivery's HMAC-SHA256 signature IS the authentication --
    // verified above before ANY processing -- so the method-security
    // posture is anonymous-by-design, recorded rather than implicit.
    @PreAuthorize("permitAll()")
    @Operation(
            operationId = "receiveGithubWebhook",
            summary = "Receive a signed GitHub webhook delivery",
            description = "The delivery's HMAC-SHA256 signature (X-Hub-Signature-256, sha256=hex over the exact body "
                    + "bytes) is the authentication. Every authenticity failure answers the same 401; "
                    + "a verified sender's unreadable body answers 400; every processed delivery answers "
                    + "200 with its receipt (ACCEPTED, IGNORED, or DUPLICATE -- GitHub's contract is "
                    + "2xx-or-retry, and events this receiver does not consume are acknowledged, not failed).")
    @ApiResponse(responseCode = "200", description = "The delivery's receipt")
    @ApiResponse(responseCode = "401", description = "Delivery not accepted (uniform for every signature failure)")
    @ApiResponse(responseCode = "400", description = "A verified delivery whose body is not readable JSON")
    @ApiResponse(responseCode = "413", description = "A delivery beyond the configured body ceiling")
    ResponseEntity<WebhookReceiptView> receive(
            @RequestBody byte[] body,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String delivery,
            @RequestHeader(value = "X-GitHub-Event", required = false) String event) {
        if (body.length > properties.maxBodyBytes()) {
            counted(GitHubWebhookService.PROVIDER, "too_large");
            throw new IntegrationException(
                    IntegrationException.Code.PAYLOAD_TOO_LARGE,
                    "webhook body of " + body.length + " bytes exceeds the configured ceiling");
        }
        if (!verifier.signatureMatches(body, signature, properties.githubSecrets())) {
            rejected(GitHubWebhookService.PROVIDER, event, delivery);
            throw new IntegrationException(IntegrationException.Code.SIGNATURE_REJECTED, "webhook signature rejected");
        }
        if (delivery == null || delivery.isBlank()) {
            counted(GitHubWebhookService.PROVIDER, "malformed");
            throw new IntegrationException(
                    IntegrationException.Code.PAYLOAD_MALFORMED, "verified delivery without a delivery id header");
        }
        JsonNode payload;
        try {
            payload = mapper.readTree(body);
        } catch (RuntimeException e) {
            counted(GitHubWebhookService.PROVIDER, "malformed");
            throw new IntegrationException(IntegrationException.Code.PAYLOAD_MALFORMED, "unreadable JSON body");
        }
        GitHubWebhookService.Receipt receipt = github.process(delivery, event, payload);
        return receipt(GitHubWebhookService.PROVIDER, receipt);
    }

    /**
     * Receives one Jenkins build-completed delivery (S12-04). Jenkins
     * has no standard webhook signature, so the receiver defines the
     * contract (docs/ci-jenkins.md): the pipeline posts the build's
     * facts and signs the exact body bytes
     * {@code X-Shipperdog-Signature: sha256=hex(HMAC-SHA256(secret,
     * body))} -- the same shape GitHub uses, with this receiver's own
     * rotating secret list. Gates: body ceiling (413), signature (the
     * uniform 401), JSON parse and deliveryId (400 for a verified
     * sender), buildTimestamp freshness (the uniform 401 -- a stale
     * build is a replay), then processing.
     */
    @PostMapping(value = "/webhooks/jenkins", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("permitAll()")
    @Operation(
            operationId = "receiveJenkinsWebhook",
            summary = "Receive a signed Jenkins build-completed delivery",
            description = "The delivery's HMAC-SHA256 signature (X-Shipperdog-Signature, sha256=hex over the exact "
                    + "body bytes) is the authentication -- the Jenkinsfile snippet's openssl one-liner computes it. "
                    + "Every authenticity failure answers the same 401; a verified sender's unreadable body or "
                    + "missing deliveryId answers 400; a stale buildTimestamp answers 401 (a replay); every "
                    + "processed delivery answers 200 with its receipt (a SUCCESS build whose gitCommit matches "
                    + "artifacts backfills their provenance; everything else is acknowledged, not failed).")
    @ApiResponse(responseCode = "200", description = "The delivery's receipt")
    @ApiResponse(
            responseCode = "401",
            description = "Delivery not accepted (uniform for every signature/staleness failure)")
    @ApiResponse(
            responseCode = "400",
            description = "A verified delivery whose body is not readable JSON or has no deliveryId")
    @ApiResponse(responseCode = "413", description = "A delivery beyond the configured body ceiling")
    ResponseEntity<WebhookReceiptView> receiveJenkins(
            @RequestBody byte[] body,
            @RequestHeader(value = "X-Shipperdog-Signature", required = false) String signature) {
        if (body.length > properties.maxBodyBytes()) {
            counted(JenkinsWebhookService.PROVIDER, "too_large");
            throw new IntegrationException(
                    IntegrationException.Code.PAYLOAD_TOO_LARGE,
                    "webhook body of " + body.length + " bytes exceeds the configured ceiling");
        }
        if (!verifier.signatureMatches(body, signature, properties.jenkinsSecrets())) {
            rejected(JenkinsWebhookService.PROVIDER, JenkinsWebhookService.EVENT, null);
            throw new IntegrationException(IntegrationException.Code.SIGNATURE_REJECTED, "webhook signature rejected");
        }
        JsonNode payload;
        try {
            payload = mapper.readTree(body);
        } catch (RuntimeException e) {
            counted(JenkinsWebhookService.PROVIDER, "malformed");
            throw new IntegrationException(IntegrationException.Code.PAYLOAD_MALFORMED, "unreadable JSON body");
        }
        String delivery = text(payload, "deliveryId");
        if (delivery == null || delivery.isBlank()) {
            counted(JenkinsWebhookService.PROVIDER, "malformed");
            throw new IntegrationException(
                    IntegrationException.Code.PAYLOAD_MALFORMED, "verified delivery without a deliveryId field");
        }
        if (!fresh(
                doc -> epoch(text(doc, "buildTimestamp"))
                        .map(this::withinWindow)
                        .orElse(null),
                payload)) {
            rejected(JenkinsWebhookService.PROVIDER, JenkinsWebhookService.EVENT, delivery);
            throw new IntegrationException(
                    IntegrationException.Code.SIGNATURE_REJECTED, "webhook delivery not accepted");
        }
        GitHubWebhookService.Receipt receipt = jenkins.process(payload);
        return receipt(JenkinsWebhookService.PROVIDER, receipt);
    }

    /**
     * Receives one GitLab webhook delivery (S12-04). GitLab's protocol
     * sends the configured secret verbatim in {@code X-Gitlab-Token}
     * (no body signature), so this family compares constant-time over
     * the rotating token list and the transport MUST be TLS. Gates:
     * body ceiling (413), token (the uniform 401), JSON parse and the
     * delivery-id header (400 for a verified sender), the pipeline
     * timestamps' freshness (the uniform 401), then processing.
     */
    @PostMapping(value = "/webhooks/gitlab", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("permitAll()")
    @Operation(
            operationId = "receiveGitlabWebhook",
            summary = "Receive a token-authenticated GitLab webhook delivery",
            description = "The X-Gitlab-Token header (compared constant-time against the rotating secret list) is "
                    + "the authentication; the transport must be TLS because GitLab signs nothing. Every "
                    + "authenticity failure answers the same 401; a verified sender's unreadable body or missing "
                    + "X-Gitlab-Webhook-UUID answers 400; stale pipeline timestamps answer 401 (a replay); every "
                    + "processed delivery answers 200 with its receipt (a successful pipeline whose sha matches "
                    + "artifacts backfills their provenance; every other event is acknowledged, not failed).")
    @ApiResponse(responseCode = "200", description = "The delivery's receipt")
    @ApiResponse(
            responseCode = "401",
            description = "Delivery not accepted (uniform for every token/staleness failure)")
    @ApiResponse(
            responseCode = "400",
            description = "A verified delivery whose body is not readable JSON or has no delivery id")
    @ApiResponse(responseCode = "413", description = "A delivery beyond the configured body ceiling")
    ResponseEntity<WebhookReceiptView> receiveGitlab(
            @RequestBody byte[] body,
            @RequestHeader(value = "X-Gitlab-Token", required = false) String token,
            @RequestHeader(value = "X-Gitlab-Webhook-UUID", required = false) String delivery,
            @RequestHeader(value = "X-Gitlab-Event", required = false) String event) {
        if (body.length > properties.maxBodyBytes()) {
            counted(GitLabWebhookService.PROVIDER, "too_large");
            throw new IntegrationException(
                    IntegrationException.Code.PAYLOAD_TOO_LARGE,
                    "webhook body of " + body.length + " bytes exceeds the configured ceiling");
        }
        if (!verifier.tokenMatches(token, properties.gitlabSecrets())) {
            rejected(GitLabWebhookService.PROVIDER, event, null);
            throw new IntegrationException(IntegrationException.Code.SIGNATURE_REJECTED, "webhook token rejected");
        }
        if (delivery == null || delivery.isBlank()) {
            counted(GitLabWebhookService.PROVIDER, "malformed");
            throw new IntegrationException(
                    IntegrationException.Code.PAYLOAD_MALFORMED, "verified delivery without a delivery id header");
        }
        JsonNode payload;
        try {
            payload = mapper.readTree(body);
        } catch (RuntimeException e) {
            counted(GitLabWebhookService.PROVIDER, "malformed");
            throw new IntegrationException(IntegrationException.Code.PAYLOAD_MALFORMED, "unreadable JSON body");
        }
        if (!fresh(
                doc -> {
                    Instant stamped = gitlab.freshnessOf(doc);
                    return stamped == null ? null : withinWindow(stamped);
                },
                payload)) {
            rejected(GitLabWebhookService.PROVIDER, event, delivery);
            throw new IntegrationException(
                    IntegrationException.Code.SIGNATURE_REJECTED, "webhook delivery not accepted");
        }
        GitHubWebhookService.Receipt receipt = gitlab.process(delivery, event, payload);
        return receipt(GitLabWebhookService.PROVIDER, receipt);
    }

    /**
     * Receives one App Store Server Notification (S12-04): a JWS
     * {@code signedPayload} verified against Apple's certificate
     * chain and ES256 signature, then mapped thin to a normalized
     * internal event. Gates: body ceiling (413), the JSON body and
     * its signedPayload + the JWS itself (the uniform 400 of the
     * store family -- every authenticity leg), signedDate freshness
     * (the same uniform 400), then processing. A notification for a
     * bundle not on the allowlist is acknowledged and ignored (Apple
     * retries non-2xx; a foreign app must not churn retries).
     */
    @PostMapping(value = "/webhooks/appstore", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("permitAll()")
    @Operation(
            operationId = "receiveAppStoreWebhook",
            summary = "Receive a signed App Store Server Notification",
            description = "Apple's JWS signedPayload (ES256, x5c certificate chain to the pinned Apple Root CA) is "
                    + "the authentication. Every authenticity failure -- unreadable body, missing or invalid "
                    + "signedPayload, failed chain, failed signature, stale signedDate -- answers the same 400 "
                    + "(WEBHOOK_DELIVERY_REJECTED, per the store receivers' contract); every processed delivery "
                    + "answers 200 with its receipt (ACCEPTED -- mapped to a normalized internal event, IGNORED -- "
                    + "a foreign bundle or shape drift, or DUPLICATE -- Apple's redelivery of a notificationUUID "
                    + "already processed).")
    @ApiResponse(responseCode = "200", description = "The delivery's receipt")
    @ApiResponse(
            responseCode = "400",
            description = "Delivery not accepted (uniform for every authenticity/staleness failure)")
    @ApiResponse(responseCode = "413", description = "A delivery beyond the configured body ceiling")
    ResponseEntity<WebhookReceiptView> receiveAppStore(@RequestBody byte[] body) {
        if (body.length > properties.maxBodyBytes()) {
            counted(AppStoreWebhookService.PROVIDER, "too_large");
            throw new IntegrationException(
                    IntegrationException.Code.PAYLOAD_TOO_LARGE,
                    "webhook body of " + body.length + " bytes exceeds the configured ceiling");
        }
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (RuntimeException e) {
            root = null;
        }
        String signedPayload = root == null ? null : text(root, "signedPayload");
        JsonNode payload =
                signedPayload == null || signedPayload.isBlank() ? null : appleJws.verifyAndDecode(signedPayload);
        if (payload == null) {
            rejected(AppStoreWebhookService.PROVIDER, null, null);
            throw new IntegrationException(IntegrationException.Code.DELIVERY_REJECTED, "webhook delivery rejected");
        }
        if (!fresh(
                notification -> epochMillis(text(notification, "signedDate"))
                        .map(this::withinWindow)
                        .orElse(null),
                payload)) {
            rejected(
                    AppStoreWebhookService.PROVIDER,
                    text(payload, "notificationType"),
                    text(payload, "notificationUUID"));
            throw new IntegrationException(IntegrationException.Code.DELIVERY_REJECTED, "webhook delivery rejected");
        }
        GitHubWebhookService.Receipt receipt = appstore.process(payload);
        return receipt(AppStoreWebhookService.PROVIDER, receipt);
    }

    /**
     * Receives one Google Play Real-Time Developer Notification
     * (S12-04): a Pub/Sub push delivery whose {@code Authorization:
     * Bearer} OIDC token is verified against Google's public JWKS
     * (signature, issuer, audience, freshness, and the optional
     * service-account allowlist), its base64 protobuf notification
     * read thin, and the whole mapped to a normalized internal event.
     * Gates: body ceiling (413), the bearer token (the uniform 400),
     * the envelope parse (400 for a verified sender), publishTime
     * freshness (the same uniform 400), then processing.
     */
    @PostMapping(value = "/webhooks/google-play", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("permitAll()")
    @Operation(
            operationId = "receiveGooglePlayWebhook",
            summary = "Receive an OIDC-authenticated Google Play RTDN push",
            description = "The Authorization: Bearer OIDC token (Google-signed, RS256 against Google's public JWKS, "
                    + "audience pinned to this endpoint) is the authentication. Every authenticity failure -- "
                    + "missing or invalid token, wrong audience, expired, stale, untrusted sender -- answers the "
                    + "same 400 (WEBHOOK_DELIVERY_REJECTED); a verified sender's unreadable envelope answers 400 "
                    + "PAYLOAD_MALFORMED; stale publishTime answers the uniform 400; every processed delivery "
                    + "answers 200 with its receipt (ACCEPTED -- mapped to a normalized internal event, IGNORED -- "
                    + "shape drift, or DUPLICATE -- Pub/Sub's redelivery of a messageId already processed).")
    @ApiResponse(responseCode = "200", description = "The delivery's receipt")
    @ApiResponse(
            responseCode = "400",
            description = "Delivery not accepted (authenticity/staleness uniform, or an unreadable verified envelope)")
    @ApiResponse(responseCode = "413", description = "A delivery beyond the configured body ceiling")
    ResponseEntity<WebhookReceiptView> receiveGooglePlay(
            @RequestBody byte[] body, @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (body.length > properties.maxBodyBytes()) {
            counted(GooglePlayWebhookService.PROVIDER, "too_large");
            throw new IntegrationException(
                    IntegrationException.Code.PAYLOAD_TOO_LARGE,
                    "webhook body of " + body.length + " bytes exceeds the configured ceiling");
        }
        if (googleJwt.verifyAndDecode(authorization) == null) {
            rejected(GooglePlayWebhookService.PROVIDER, null, null);
            throw new IntegrationException(IntegrationException.Code.DELIVERY_REJECTED, "webhook delivery rejected");
        }
        JsonNode envelope;
        try {
            envelope = mapper.readTree(body);
        } catch (RuntimeException e) {
            counted(GooglePlayWebhookService.PROVIDER, "malformed");
            throw new IntegrationException(IntegrationException.Code.PAYLOAD_MALFORMED, "unreadable JSON body");
        }
        if (!fresh(
                push -> {
                    Instant stamped = googlePlay.freshnessOf(push);
                    return stamped == null ? null : withinWindow(stamped);
                },
                envelope)) {
            rejected(GooglePlayWebhookService.PROVIDER, null, text(envelope.path("message"), "messageId"));
            throw new IntegrationException(IntegrationException.Code.DELIVERY_REJECTED, "webhook delivery rejected");
        }
        GitHubWebhookService.Receipt receipt = googlePlay.process(envelope);
        return receipt(GooglePlayWebhookService.PROVIDER, receipt);
    }

    // -----------------------------------------------------------------------
    // The shared legs: the replay-window evaluation, the metric, the
    // rejection audit, and the receipt
    // -----------------------------------------------------------------------

    /** The freshness verdict: fresh (true), stale (false), unevaluable (null -- the caller decides). */
    private interface FreshnessCheck {
        Boolean evaluate(JsonNode payload);
    }

    /**
     * Runs a freshness check: STALE is the replay rejection its
     * caller throws for; UNEVALUABLE is not -- the delivery processes
     * with fresh=false and the service declines to act (an IGNORED
     * receipt), so a provider shape that stopped carrying a timestamp
     * degrades to a no-op instead of an outage of rejections.
     */
    private static boolean fresh(FreshnessCheck check, JsonNode payload) {
        Boolean verdict = check.evaluate(payload);
        return !Boolean.FALSE.equals(verdict);
    }

    private boolean withinWindow(Instant stamped) {
        return verifier.withinWindow(stamped, properties.maxTimestampSkew());
    }

    private static java.util.Optional<Instant> epoch(String raw) {
        if (raw == null || raw.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(Instant.ofEpochSecond(Long.parseLong(raw.trim())));
        } catch (NumberFormatException e) {
            return java.util.Optional.empty();
        }
    }

    private static java.util.Optional<Instant> epochMillis(String raw) {
        if (raw == null || raw.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(Instant.ofEpochMilli(Long.parseLong(raw.trim())));
        } catch (NumberFormatException e) {
            return java.util.Optional.empty();
        }
    }

    /** The rejection audit + metric every uniform rejection leg shares. */
    private void rejected(String provider, String event, String delivery) {
        audit.record(
                AuditEventType.INBOUND_WEBHOOK_REJECTED,
                null,
                false,
                Map.of(
                        "provider",
                        provider,
                        "event",
                        auditSafe(event, AUDIT_EVENT_MAX),
                        "deliveryId",
                        auditSafe(delivery, AUDIT_DELIVERY_MAX)));
        counted(provider, "rejected");
        // One log line at WARN for the operator, one audit event, one
        // uniform answer -- never which leg refused.
        log.warn("rejected an inbound webhook delivery: provider {}", provider);
    }

    /** The receipt leg: the 200 + its metric increment. */
    private ResponseEntity<WebhookReceiptView> receipt(String provider, GitHubWebhookService.Receipt receipt) {
        counted(provider, receipt.status().toLowerCase(java.util.Locale.ROOT));
        return ResponseEntity.ok(new WebhookReceiptView(receipt.status(), receipt.deliveryId()));
    }

    private void counted(String provider, String outcome) {
        meters.counter(MetricNames.WEBHOOK_DELIVERIES, "provider", provider, "outcome", outcome)
                .increment();
    }

    /**
     * The audit-safe rendering of a provider header/value: never
     * blank-carrying, capped at the ledger column's own width (the
     * S12-03 review's cap-alignment finding -- a hostile header must
     * not balloon an audit row).
     */
    private static String auditSafe(String value, int cap) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        String trimmed = value.trim();
        return trimmed.length() <= cap ? trimmed : trimmed.substring(0, cap);
    }

    /** The document reader's tolerant text leg: null for absent or null members. */
    private static String text(JsonNode tree, String field) {
        JsonNode value = tree.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /** What the receiver did with a verified delivery. */
    public record WebhookReceiptView(
            @Schema(
                    description =
                            "ACCEPTED (consumed and acted on), IGNORED (consumed, not acted on), or DUPLICATE (already processed)",
                    example = "ACCEPTED")
            String status,

            @Schema(
                    description = "The delivery id the receipt answers (the sender's own delivery identifier)",
                    example = "72d3162e-cc78-11e3-81ab-4c9367dc0958")
            String deliveryId) {}
}
