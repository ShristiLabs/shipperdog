package com.shipperdog.api.notification.internal.service;

import com.shipperdog.api.audit.AuditEventFeed;
import com.shipperdog.api.audit.AuditEventFeed.AuditFeedRow;
import com.shipperdog.api.environment.SecretReferenceResolver;
import com.shipperdog.api.notification.internal.bus.AuditStreamProperties;
import com.shipperdog.api.notification.internal.entity.AuditStreamBatchEntity;
import com.shipperdog.api.notification.internal.entity.AuditStreamExportEntity;
import com.shipperdog.api.notification.internal.repository.AuditStreamBatchRepository;
import com.shipperdog.api.notification.internal.repository.AuditStreamExportRepository;
import com.shipperdog.api.notification.internal.storage.AuditStreamBucketStore;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * The audit stream's bucket batcher (issue #71, S14-05, AC2/AC3):
 * the periodic pass that turns each enabled BUCKET export's
 * watermark into the next versioned batch -- one bounded page of the
 * organization's immutable audit rows (the {@link AuditEventFeed}
 * port, oldest first, keyset-paginated on {@code (occurredAt, id)}),
 * rendered as JSONL in the frozen export envelope, written beside
 * its HMAC-signed manifest, and committed together with the
 * watermark advance and the batch-history row. Verifiable
 * completeness is the manifest's own contract: every batch carries
 * its row count and window, and the cumulative counts against the
 * audit table's count for the same window are the proof.
 *
 * <p>Consumer downtime is the watermark's problem and it is durable
 * (AC3): nothing is consumed until the batch commits, so a bucket
 * that is unreachable simply leaves the watermark standing -- the
 * backlog drains over successive passes (each bounded to {@code
 * batchMaxRows}), never one unbounded read, never a lost row. The
 * write is idempotent by construction: the window is deterministic
 * in the standing watermark, so a crash between the object write and
 * the commit re-runs the identical window and re-writes identical
 * bytes (the store overwrite IS the recovery).
 *
 * <p>A pass failure (secret reference not resolving, bucket
 * unreachable) is logged with the export id and leaves the watermark
 * untouched -- the operational signal is the lagging watermark beside
 * a healthy trail, and the next cadence retries. Neither successful
 * nor failed passes write audit lines: the batch-history row and the
 * signed manifest are the record, and a per-batch event would itself
 * bridge onto the stream and echo forever under the drain (the
 * notification module's per-delivery doctrine).
 */
@Component
public class AuditStreamBucketExporter {

    private static final Logger log = LoggerFactory.getLogger(AuditStreamBucketExporter.class);

    /** The manifest's own schema member -- the manifest is a versioned contract beside the envelope (docs/siem.md). */
    static final String MANIFEST_SCHEMA_VERSION = "1.0";

    private static final ObjectMapper CANONICAL = new ObjectMapper();

    private final AuditStreamExportRepository exports;
    private final AuditStreamBatchRepository batches;
    private final AuditEventFeed feed;
    private final AuditStreamBucketStore bucket;
    private final SecretReferenceResolver secrets;
    private final AuditStreamProperties properties;
    private final Clock clock;

    AuditStreamBucketExporter(
            AuditStreamExportRepository exports,
            AuditStreamBatchRepository batches,
            AuditEventFeed feed,
            AuditStreamBucketStore bucket,
            SecretReferenceResolver secrets,
            AuditStreamProperties properties,
            Clock clock) {
        this.exports = exports;
        this.batches = batches;
        this.feed = feed;
        this.bucket = bucket;
        this.secrets = secrets;
        this.properties = properties;
        this.clock = clock;
    }

    /** The scheduled pass (the cadence and switch are environment-configurable). */
    @Scheduled(fixedDelayString = "${shipperdog.notifications.audit-stream.batch-fixed-delay:PT60S}")
    void exportScheduled() {
        if (properties.batchEnabled()) {
            exportOnce();
        }
    }

    /**
     * One pass over every enabled bucket export. Public seam for
     * tests and manual drains (the rollup-trigger precedent). A
     * per-export failure logs and leaves that export's watermark
     * standing; the pass's transaction stays clean for the others
     * (the storage writes precede every database write of their own
     * iteration, so a storage failure dirties nothing).
     */
    @Transactional
    public void exportOnce() {
        for (AuditStreamExportEntity export :
                exports.findByKindAndEnabledTrueOrderByIdAsc(com.shipperdog.api.notification.AuditStreamKind.BUCKET)) {
            try {
                exportOne(export);
            } catch (RuntimeException e) {
                log.error(
                        "audit stream batch failed export={} organization={}",
                        export.getId(),
                        export.getOrganizationId(),
                        e);
            }
        }
    }

    /** One export's batch: page, render, write, sign, commit -- or leave the watermark standing. */
    private void exportOne(AuditStreamExportEntity export) {
        Instant now = Instant.now(clock);
        List<AuditFeedRow> rows = feed.pageAfter(
                export.getOrganizationId(),
                export.getWatermark(),
                export.getWatermarkId(),
                properties.boundedBatchMaxRows());
        if (rows.isEmpty()) {
            return;
        }
        String secret = secrets.resolve(export.getSigningSecretRef()).orElse(null);
        if (secret == null) {
            throw new IllegalStateException("the signing secret reference did not resolve");
        }
        AuditFeedRow last = rows.get(rows.size() - 1);
        long seq = export.getBatchSeq() + 1;

        byte[] batchBytes = jsonl(rows);
        String batchSha256 = sha256Hex(batchBytes);
        String batchUri =
                objectUri(export, "audit-" + export.getOrganizationId() + "-" + String.format("%06d", seq) + ".jsonl");
        String manifestUri = objectUri(
                export, "manifest-" + export.getOrganizationId() + "-" + String.format("%06d", seq) + ".json");

        bucket.write(batchUri, batchBytes, "application/x-ndjson");
        bucket.write(manifestUri, manifest(export, seq, rows, batchSha256, batchUri, now, secret), "application/json");

        batches.save(new AuditStreamBatchEntity(
                export.getId(),
                export.getOrganizationId(),
                seq,
                export.getWatermark(),
                last.occurredAt(),
                rows.size(),
                batchSha256,
                batchUri,
                manifestUri,
                now));
        export.advanceWatermark(seq, last.occurredAt(), last.id(), now);
        exports.save(export);
        // No audit line per batch, deliberately: the batch-history
        // row and the signed manifest ARE the record (the
        // notification module's own doctrine -- per-delivery events
        // are not audited), and a per-batch event would itself
        // bridge onto the stream and echo forever under the drain.
    }

    // Rendering -------------------------------------------------------------------

    /** The batch body: one envelope per line, trailing newline, the frozen schema both legs share. */
    private static byte[] jsonl(List<AuditFeedRow> rows) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (AuditFeedRow row : rows) {
            byte[] envelope = SiemEventFormats.shipperdogJson(row.id(), SiemEventFormats.feedFacts(row));
            body.writeBytes(envelope);
            body.write('\n');
        }
        return body.toByteArray();
    }

    /**
     * The manifest: the batch's completeness facts in a frozen key
     * order, HMAC-signed. The signature is SHA-256 HMAC over the
     * canonical serialization of the manifest WITHOUT the {@code
     * signature} member -- every other member (including the
     * algorithm name and the batch digest) is signed material
     * (docs/siem.md carries the verification recipe).
     */
    private byte[] manifest(
            AuditStreamExportEntity export,
            long seq,
            List<AuditFeedRow> rows,
            String batchSha256,
            String batchUri,
            Instant now,
            String secret) {
        Map<String, Object> signedMaterial = new LinkedHashMap<>();
        signedMaterial.put("schema_version", MANIFEST_SCHEMA_VERSION);
        signedMaterial.put("export_id", export.getId().toString());
        signedMaterial.put("organization_id", export.getOrganizationId().toString());
        signedMaterial.put("batch_seq", seq);
        signedMaterial.put(
                "watermark_from",
                export.getWatermark() == null ? null : export.getWatermark().toString());
        signedMaterial.put(
                "watermark_to", rows.get(rows.size() - 1).occurredAt().toString());
        signedMaterial.put("event_count", rows.size());
        signedMaterial.put("batch_sha256", batchSha256);
        signedMaterial.put("batch_object", batchUri);
        signedMaterial.put("created_at", now.toString());
        signedMaterial.put("signature_algorithm", "HMAC-SHA256");
        Map<String, Object> manifest = new LinkedHashMap<>(signedMaterial);
        manifest.put("signature", hmacSha256Hex(secret, CANONICAL.writeValueAsBytes(signedMaterial)));
        return CANONICAL.writeValueAsBytes(manifest);
    }

    /** The export's bucket URI joined to a key segment ({@code gs://bucket/prefix/key}). */
    private static String objectUri(AuditStreamExportEntity export, String key) {
        String base = export.getBucketUri();
        return base.endsWith("/") ? base + key : base + "/" + key;
    }

    // Digests -----------------------------------------------------------------------

    static String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static String hmacSha256Hex(String secret, byte[] content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(content));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    /** The batcher's wiring: the properties record (scheduling itself is on the application). */
    @Configuration
    @EnableConfigurationProperties(AuditStreamProperties.class)
    static class BatchConfig {}
}
