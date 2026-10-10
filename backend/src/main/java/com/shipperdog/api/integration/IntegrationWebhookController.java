package com.shipperdog.api.integration;

import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.audit.AuditLogger;
import com.shipperdog.api.integration.internal.service.GitHubWebhookService;
import com.shipperdog.api.integration.internal.webhook.InboundWebhookProperties;
import com.shipperdog.api.integration.internal.webhook.WebhookSignatureVerifier;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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
 * The inbound webhook receiver (issue #53, S12-03, FR-022, PRD 19):
 * {@code POST /webhooks/github} -- deliberately OUTSIDE the
 * {@code /api/v1} bearer surface, because the delivery's own HMAC
 * signature IS the authentication (the credential is the shared
 * secret only the sender and this deployment hold; the verifier is
 * the authority, the same character as the download proxy's HMAC
 * token). The generic framework underneath -- constant-time
 * signature verification over a rotating secret list, the
 * timestamp-window replay seam, the delivery-id ledger -- is the
 * surface S12-04's receivers and E13's connectors reuse.
 *
 * <p>The uniform rejection posture: EVERY authenticity failure
 * (absent header, malformed prefix, wrong key, no secret configured)
 * answers the same 401 -- an unauthenticated caller learns nothing
 * about which leg refused or whether the receiver is even on. A
 * verified sender's unreadable body answers 400 (the sender needs
 * the diagnostic); everything else is a 200 receipt, including
 * unknown events (acknowledged and ignored -- GitHub's contract is
 * 2xx-or-retry, and an event this receiver does not consume must not
 * churn retries).
 *
 * <p>Configure the GitHub webhook with content type
 * {@code application/json}; the events consumed are documented in
 * docs/ci.md (today: {@code workflow_run}).
 */
@RestController
public class IntegrationWebhookController {

    private static final Logger log = LoggerFactory.getLogger(IntegrationWebhookController.class);

    private final WebhookSignatureVerifier verifier;
    private final InboundWebhookProperties properties;
    private final GitHubWebhookService github;
    private final AuditLogger audit;
    private final ObjectMapper mapper;

    IntegrationWebhookController(
            WebhookSignatureVerifier verifier,
            InboundWebhookProperties properties,
            GitHubWebhookService github,
            AuditLogger audit,
            ObjectMapper mapper) {
        this.verifier = verifier;
        this.properties = properties;
        this.github = github;
        this.audit = audit;
        this.mapper = mapper;
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
            throw new IntegrationException(
                    IntegrationException.Code.PAYLOAD_TOO_LARGE,
                    "webhook body of " + body.length + " bytes exceeds the configured ceiling");
        }
        if (!verifier.signatureMatches(body, signature, properties.githubSecrets())) {
            audit.record(
                    AuditEventType.INBOUND_WEBHOOK_REJECTED,
                    null,
                    false,
                    Map.of(
                            "provider",
                            GitHubWebhookService.PROVIDER,
                            "event",
                            safe(event),
                            "deliveryId",
                            safe(delivery)));
            // One log line at WARN for the operator, one audit event,
            // one uniform answer -- never which leg refused.
            log.warn("rejected a github webhook delivery: signature verification failed");
            throw new IntegrationException(IntegrationException.Code.SIGNATURE_REJECTED, "webhook signature rejected");
        }
        if (delivery == null || delivery.isBlank()) {
            throw new IntegrationException(
                    IntegrationException.Code.PAYLOAD_MALFORMED, "verified delivery without a delivery id header");
        }
        JsonNode payload;
        try {
            payload = mapper.readTree(body);
        } catch (RuntimeException e) {
            throw new IntegrationException(IntegrationException.Code.PAYLOAD_MALFORMED, "unreadable JSON body");
        }
        GitHubWebhookService.Receipt receipt = github.process(delivery, event, payload);
        return ResponseEntity.ok(new WebhookReceiptView(receipt.status(), receipt.deliveryId()));
    }

    private static String safe(String header) {
        return header == null || header.isBlank() ? "-" : header.trim();
    }

    /** What the receiver did with a verified delivery. */
    public record WebhookReceiptView(
            @Schema(
                    description =
                            "ACCEPTED (consumed and acted on), IGNORED (consumed, not acted on), or DUPLICATE (already processed)",
                    example = "ACCEPTED")
            String status,

            @Schema(
                    description = "The delivery id the receipt answers (the sender's X-GitHub-Delivery)",
                    example = "72d3162e-cc78-11e3-81ab-4c9367dc0958")
            String deliveryId) {}
}
