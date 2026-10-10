package com.shipperdog.api.integration.internal.service;

import com.shipperdog.api.artifact.ArtifactProvenanceLink;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.audit.AuditLogger;
import com.shipperdog.api.integration.internal.entity.InboundWebhookDeliveryEntity;
import com.shipperdog.api.integration.internal.repository.InboundWebhookDeliveryRepository;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
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
 * The GitHub receiver's processing (issue #53, S12-03, FR-022): the
 * event routing, the workflow_run enrichment, the delivery ledger's
 * idempotency claim, and the audit trail of every outcome. The
 * transport legs -- body ceiling, signature verification, JSON
 * parsing -- live in the controller; this service owns what a VERIFIED
 * delivery means.
 *
 * <p><b>Events consumed</b> (the documented contract): {@code
 * workflow_run} with {@code action == "completed"} -- a finished CI
 * run whose {@code head_sha} matches artifacts' frozen S5-04 {@code
 * git_commit} claims -- enriches those artifacts' provenance with the
 * run's URL ({@code ci_run_url}) and workflow name ({@code pipeline}),
 * BACKFILL-ONLY (absent fields filled, asserted values never
 * replaced). Every other event is acknowledged and IGNORED: GitHub's
 * webhook catalog is large and versioned, the receiver subscribes to
 * what it consumes, and an unknown or shape-shifted payload is a
 * logged no-op, never a failure the sender would pointlessly retry.
 *
 * <p><b>Idempotency</b>: the ledger claim and the processing share
 * one transaction -- a crash rolls both back (a redelivery processes
 * fresh), and the unique (provider, delivery_id) key answers a
 * concurrent or replayed copy DUPLICATE without reprocessing. The
 * enrichment is idempotent independently of the ledger (backfill-only),
 * so every layer of redelivery converges on the same state.
 *
 * <p><b>Audit posture</b>: accepted, ignored, and duplicate
 * deliveries audit their facts (provider, event, delivery id,
 * outcome); rejections audit from the controller with the same
 * uniformity as their 401. The signing secret never appears anywhere,
 * and the raw payload never rides the audit trail -- the delivery id
 * is the join key to GitHub's own delivery log.
 */
@Service
public class GitHubWebhookService {

    /** The framework's provider key this receiver owns. */
    public static final String PROVIDER = "github";

    private static final Logger log = LoggerFactory.getLogger(GitHubWebhookService.class);

    private static final int EVENT_MAX = 64;
    private static final int DELIVERY_MAX = 255;
    private static final int PIPELINE_MAX = 255;
    private static final int URL_MAX = 2048;

    private final InboundWebhookDeliveryRepository deliveries;
    private final ArtifactProvenanceLink provenance;
    private final AuditLogger audit;
    private final Clock clock;

    public GitHubWebhookService(
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
     * Processes one verified delivery, answering the receipt the
     * controller returns: ACCEPTED, IGNORED, or DUPLICATE. The ledger
     * claim comes FIRST -- a duplicate never reprocesses (the
     * enrichment's own idempotency is the second line of defense, not
     * the only one).
     */
    @Transactional
    public Receipt process(String deliveryId, String eventType, JsonNode payload) {
        String event = truncate(normalize(eventType), EVENT_MAX);
        String delivery = truncate(normalize(deliveryId), DELIVERY_MAX);
        UUID rowId = UUID.randomUUID();
        // The provisional outcome is IGNORED -- what a delivery the
        // receiver does not act on becomes; acted-on deliveries
        // promote to ACCEPTED below, in the same transaction.
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
            return new Receipt("DUPLICATE", delivery);
        }
        boolean acted = "workflow_run".equals(event) && enrichCompletedRun(payload);
        if (acted) {
            deliveries.markAccepted(rowId);
            audit.record(
                    AuditEventType.INBOUND_WEBHOOK_ACCEPTED,
                    null,
                    true,
                    Map.of("provider", PROVIDER, "event", event, "deliveryId", delivery));
            return new Receipt("ACCEPTED", delivery);
        }
        audit.record(
                AuditEventType.INBOUND_WEBHOOK_IGNORED,
                null,
                true,
                Map.of("provider", PROVIDER, "event", event, "deliveryId", delivery));
        return new Receipt("IGNORED", delivery);
    }

    /**
     * The workflow_run leg: a completed run's head commit and run URL
     * become the backfill inputs. Every shape surprise degrades to a
     * no-op (the delivery audits IGNORED) -- never a 400 to a verified
     * sender whose payload shape GitHub evolved.
     */
    private boolean enrichCompletedRun(JsonNode payload) {
        JsonNode run = payload.path("workflow_run");
        if (!"completed".equals(text(payload, "action")) || !run.isObject()) {
            return false;
        }
        String commit = commitOf(text(run, "head_sha"));
        String runUrl = urlOf(text(run, "html_url"));
        if (commit == null || runUrl == null) {
            return false;
        }
        String workflowName = printable(firstNonBlank(text(run, "name"), text(payload.path("workflow"), "name")));
        List<UUID> enriched = provenance.backfillProvenanceByCommit(commit, runUrl, workflowName);
        if (enriched.isEmpty()) {
            return false;
        }
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("provider", PROVIDER);
        detail.put("event", "workflow_run");
        detail.put("gitCommit", commit);
        detail.put("ciRunUrl", runUrl);
        detail.put("artifacts", joinTruncated(enriched));
        audit.record(AuditEventType.ARTIFACT_PROVENANCE_ENRICHED, null, true, detail);
        log.info("workflow_run enriched {} artifact(s) for commit {}", enriched.size(), commit);
        return true;
    }

    // -----------------------------------------------------------------------
    // The S5-04 shape checks the backfill inputs must pass (the same
    // shapes the provenance contract validates at upload; a mismatch
    // degrades to a no-op rather than poisoning the frozen document)
    // -----------------------------------------------------------------------

    /** Hex SHA-1/SHA-256, case-insensitive, answered lowercase. */
    private static String commitOf(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return value.matches("[0-9a-f]{40}") || value.matches("[0-9a-f]{64}") ? value : null;
    }

    /** An absolute http(s) URL within the provenance ceiling. */
    private static String urlOf(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.length() > URL_MAX || value.length() == 0) {
            return null;
        }
        try {
            URI parsed = URI.create(value);
            String scheme = parsed.getScheme();
            if (scheme == null || parsed.getHost() == null) {
                return null;
            }
            if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) {
                return null;
            }
            return value;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A 1-255 printable label, or null (a null pipeline simply does not backfill). */
    private static String printable(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty() || value.length() > PIPELINE_MAX || value.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            return null;
        }
        return value;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }

    private static String normalize(String raw) {
        return raw == null ? "" : raw.trim();
    }

    /** The document reader's tolerant text leg (the Provenance.from shape): null for absent or null members. */
    private static String text(JsonNode tree, String field) {
        JsonNode value = tree.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** The audit-safe rendering of the enriched id list (bounded, comma-joined). */
    private static String joinTruncated(List<UUID> ids) {
        List<String> parts = new ArrayList<>();
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

    /** The receipt the controller answers with: what the receiver did with the delivery. */
    public record Receipt(String status, String deliveryId) {}
}
