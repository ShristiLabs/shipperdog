package com.shipperdog.api.integration.internal.service;

import com.shipperdog.api.artifact.ArtifactProvenanceLink;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.audit.AuditLogger;
import com.shipperdog.api.integration.internal.entity.InboundWebhookDeliveryEntity;
import com.shipperdog.api.integration.internal.repository.InboundWebhookDeliveryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
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
 * The GitLab receiver's processing (issue #54, S12-04, FR-022).
 * GitLab's webhook protocol signs nothing: the configured secret
 * rides the {@code X-Gitlab-Token} header verbatim, compared
 * constant-time by the shared framework before this service runs
 * (the transport must therefore be TLS -- the deployment note).
 * GitLab DOES stamp every delivery with its own ids ({@code
 * X-Gitlab-Webhook-UUID}, unique per delivery) and its events carry
 * timestamps, so this receiver gets the framework's full replay
 * defense: the payload timestamp window (checked by the controller)
 * and the delivery-id ledger.
 *
 * <p><b>Events consumed</b> (the documented contract, docs/ci-gitlab.md):
 * {@code pipeline} events with {@code object_attributes.status ==
 * "success"} -- a finished pipeline whose {@code sha} matches
 * artifacts' frozen S5-04 {@code git_commit} claims enriches those
 * artifacts with the pipeline's URL and name, BACKFILL-ONLY (the
 * same deliberate, audited exception to write-once provenance the
 * GitHub and Jenkins receivers own). Every other event (push, merge
 * request, a failed or running pipeline) is acknowledged and ignored
 * -- GitLab's catalog is large; the webhook subscribes to what the
 * receiver consumes.
 *
 * <p><b>Audit posture</b>: identical to the GitHub receiver's -- the
 * token never appears anywhere; the raw payload never rides the
 * audit trail; the delivery id is the join key to GitLab's own log.
 */
@Service
public class GitLabWebhookService {

    /** The framework's provider key this receiver owns. */
    public static final String PROVIDER = "gitlab";

    private static final Logger log = LoggerFactory.getLogger(GitLabWebhookService.class);

    private static final int EVENT_MAX = 64;
    private static final int DELIVERY_MAX = 255;

    private final InboundWebhookDeliveryRepository deliveries;
    private final ArtifactProvenanceLink provenance;
    private final AuditLogger audit;
    private final Clock clock;

    public GitLabWebhookService(
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
    public GitHubWebhookService.Receipt process(String deliveryId, String eventType, JsonNode payload) {
        String event = truncate(normalize(eventType), EVENT_MAX);
        String delivery = truncate(normalize(deliveryId), DELIVERY_MAX);
        UUID rowId = UUID.randomUUID();
        if (deliveries.claim(
                        rowId,
                        PROVIDER,
                        delivery,
                        event,
                        InboundWebhookDeliveryEntity.Outcome.IGNORED.name(),
                        Instant.now(clock))
                == 0) {
            audit.record(
                    AuditEventType.INBOUND_WEBHOOK_DUPLICATE,
                    null,
                    true,
                    Map.of("provider", PROVIDER, "event", event, "deliveryId", delivery));
            return new GitHubWebhookService.Receipt("DUPLICATE", delivery);
        }
        boolean acted = "pipeline".equals(text(payload, "object_kind")) && enrichPipeline(payload);
        if (acted) {
            deliveries.markAccepted(rowId);
            audit.record(
                    AuditEventType.INBOUND_WEBHOOK_ACCEPTED,
                    null,
                    true,
                    Map.of("provider", PROVIDER, "event", event, "deliveryId", delivery));
            return new GitHubWebhookService.Receipt("ACCEPTED", delivery);
        }
        audit.record(
                AuditEventType.INBOUND_WEBHOOK_IGNORED,
                null,
                true,
                Map.of("provider", PROVIDER, "event", event, "deliveryId", delivery));
        return new GitHubWebhookService.Receipt("IGNORED", delivery);
    }

    /**
     * The freshness input for the controller's replay window: the
     * pipeline's own timestamps, the finished time when the event
     * carries one (a completed pipeline always does), the created
     * time as the fallback. Null when neither parses -- the controller
     * treats an unevaluable timestamp as not acted on (IGNORED),
     * never as fresh.
     */
    public Instant freshnessOf(JsonNode payload) {
        JsonNode attributes = payload.path("object_attributes");
        Instant finished = parse(text(attributes, "finished_at"));
        return finished != null ? finished : parse(text(attributes, "created_at"));
    }

    private static Instant parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * The pipeline leg: a successful pipeline's sha and URL become
     * the backfill inputs. Every shape surprise degrades to a no-op
     * (the delivery audits IGNORED) -- never a 400 to a verified
     * sender whose payload shape GitLab evolved.
     */
    private boolean enrichPipeline(JsonNode payload) {
        JsonNode attributes = payload.path("object_attributes");
        if (!"success".equals(text(attributes, "status"))) {
            return false;
        }
        String commit = ProvenanceShapes.commitOf(text(attributes, "sha"));
        String runUrl = ProvenanceShapes.urlOf(
                firstNonBlank(text(attributes, "pipeline_url"), text(payload.path("project"), "web_url")));
        if (commit == null || runUrl == null) {
            return false;
        }
        String pipelineName = ProvenanceShapes.printable(
                firstNonBlank(text(payload.path("project"), "path_with_namespace"), "gitlab-pipeline"));
        List<UUID> enriched = provenance.backfillProvenanceByCommit(commit, runUrl, pipelineName);
        if (enriched.isEmpty()) {
            return false;
        }
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("provider", PROVIDER);
        detail.put("event", "pipeline");
        detail.put("gitCommit", commit);
        detail.put("ciRunUrl", runUrl);
        detail.put("artifacts", joinTruncated(enriched));
        audit.record(AuditEventType.ARTIFACT_PROVENANCE_ENRICHED, null, true, detail);
        log.info("a gitlab pipeline enriched {} artifact(s) for commit {}", enriched.size(), commit);
        return true;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static String normalize(String raw) {
        return raw == null ? "" : raw.trim();
    }

    /** The document reader's tolerant text leg: null for absent or null members. */
    private static String text(JsonNode tree, String field) {
        JsonNode value = tree.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
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
