package com.shipperdog.api.integration.internal.service;

import com.shipperdog.api.artifact.ArtifactProvenanceLink;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.audit.AuditLogger;
import com.shipperdog.api.integration.internal.entity.InboundWebhookDeliveryEntity;
import com.shipperdog.api.integration.internal.repository.InboundWebhookDeliveryRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * The Jenkins receiver's processing (issue #54, S12-04, FR-022).
 * Jenkins has no standard webhook signature or event shape, so the
 * receiver DEFINES a thin documented contract the pipeline snippet
 * posts (docs/ci-jenkins.md): a {@code build-completed} delivery
 * carrying the build's commit, its URL, and its timestamp -- signed
 * with the same HMAC-SHA256 shape GitHub uses ({@code
 * X-Shipperdog-Signature: sha256=hex(HMAC(secret, body))}, the
 * snippet's openssl one-liner), verified by the shared framework
 * before this service is reached.
 *
 * <p><b>What a verified delivery does</b>: a {@code SUCCESS} result
 * whose {@code gitCommit} matches artifacts' frozen S5-04 {@code
 * git_commit} claims enriches those artifacts with the build's URL
 * ({@code ci_run_url}) and pipeline name -- BACKFILL-ONLY, the same
 * deliberate, audited exception to write-once provenance the GitHub
 * receiver owns. Every other result (FAILURE, UNSTABLE, ABORTED) is
 * acknowledged and ignored: a failed build backfills nothing.
 *
 * <p><b>Replay</b>: the delivery's {@code buildTimestamp} (epoch
 * seconds, from BUILD_ID's clock) is checked against the framework's
 * window by the controller before this service runs; the ledger's
 * unique (provider, delivery_id) key answers a replayed {@code
 * deliveryId} DUPLICATE without reprocessing. The snippet generates
 * one deliveryId per posted build event.
 *
 * <p><b>Audit posture</b>: identical to the GitHub receiver's --
 * accepted, ignored, and duplicate deliveries audit their facts; the
 * secret never appears anywhere; the raw payload never rides the
 * audit trail.
 */
@Service
public class JenkinsWebhookService {

    /** The framework's provider key this receiver owns. */
    public static final String PROVIDER = "jenkins";

    /** The contract's single event name (the receiver defines the vocabulary). */
    public static final String EVENT = "build-completed";

    private static final Logger log = LoggerFactory.getLogger(JenkinsWebhookService.class);

    private static final int DELIVERY_MAX = 255;

    private final InboundWebhookDeliveryRepository deliveries;
    private final ArtifactProvenanceLink provenance;
    private final AuditLogger audit;
    private final Clock clock;

    public JenkinsWebhookService(
            InboundWebhookDeliveryRepository deliveries,
            ArtifactProvenanceLink provenance,
            AuditLogger audit,
            Clock clock) {
        this.deliveries = deliveries;
        this.provenance = provenance;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Processes one verified, freshness-checked delivery, answering
     * the receipt the controller returns. The ledger claim comes
     * FIRST -- a duplicate never reprocesses.
     */
    @Transactional
    public GitHubWebhookService.Receipt process(JsonNode payload) {
        String delivery = truncate(text(payload, "deliveryId"), DELIVERY_MAX);
        UUID rowId = UUID.randomUUID();
        if (deliveries.claim(
                        rowId,
                        PROVIDER,
                        delivery,
                        EVENT,
                        InboundWebhookDeliveryEntity.Outcome.IGNORED.name(),
                        Instant.now(clock))
                == 0) {
            audit.record(
                    AuditEventType.INBOUND_WEBHOOK_DUPLICATE,
                    null,
                    true,
                    Map.of("provider", PROVIDER, "event", EVENT, "deliveryId", delivery));
            return new GitHubWebhookService.Receipt("DUPLICATE", delivery);
        }
        boolean acted = "SUCCESS".equals(text(payload, "result")) && enrichBuild(payload);
        if (acted) {
            deliveries.markAccepted(rowId);
            audit.record(
                    AuditEventType.INBOUND_WEBHOOK_ACCEPTED,
                    null,
                    true,
                    Map.of("provider", PROVIDER, "event", EVENT, "deliveryId", delivery));
            return new GitHubWebhookService.Receipt("ACCEPTED", delivery);
        }
        audit.record(
                AuditEventType.INBOUND_WEBHOOK_IGNORED,
                null,
                true,
                Map.of("provider", PROVIDER, "event", EVENT, "deliveryId", delivery));
        return new GitHubWebhookService.Receipt("IGNORED", delivery);
    }

    /**
     * The build leg: the SUCCESS build's commit and URL become the
     * backfill inputs. Every shape surprise degrades to a no-op (the
     * delivery audits IGNORED) -- never a 400 to a verified sender.
     */
    private boolean enrichBuild(JsonNode payload) {
        String commit = ProvenanceShapes.commitOf(text(payload, "gitCommit"));
        String runUrl = ProvenanceShapes.urlOf(text(payload, "buildUrl"));
        if (commit == null || runUrl == null) {
            return false;
        }
        String pipelineName = ProvenanceShapes.printable(firstNonBlank(text(payload, "pipeline"), "jenkins"));
        List<UUID> enriched = provenance.backfillProvenanceByCommit(commit, runUrl, pipelineName);
        if (enriched.isEmpty()) {
            return false;
        }
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("provider", PROVIDER);
        detail.put("event", EVENT);
        detail.put("gitCommit", commit);
        detail.put("ciRunUrl", runUrl);
        detail.put("artifacts", joinTruncated(enriched));
        audit.record(AuditEventType.ARTIFACT_PROVENANCE_ENRICHED, null, true, detail);
        log.info("a jenkins build enriched {} artifact(s) for commit {}", enriched.size(), commit);
        return true;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    /** The document reader's tolerant text leg: null for absent or null members. */
    private static String text(JsonNode tree, String field) {
        JsonNode value = tree.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    /** The audit-safe rendering of the enriched id list (bounded, comma-joined). */
    private static String joinTruncated(List<UUID> ids) {
        List<String> parts = new java.util.ArrayList<>();
        int size = 0;
        for (UUID id : ids) {
            if (size + id.toString().length() + 1 > 512) {
                parts.add("+" + (ids.size() - parts.size()) + " more");
                break;
            }
            parts.add(id.toString());
            size += id.toString().length() + 1;
        }
        return String.join(",", parts);
    }
}
