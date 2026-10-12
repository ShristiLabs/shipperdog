package com.shipperdog.api.notification;

/**
 * The SIEM export's transport leg (issue #71, S14-05, PRD 38): which
 * machinery carries the organization's audit trail off-platform.
 * Both legs ride the S12-01 discipline -- the HTTPS leg through the
 * relay and the signed-POST client with its retry ledger; the bucket
 * leg as the periodic batcher over the append-only audit store with
 * a keyset watermark and signed manifests (docs/siem.md).
 */
public enum AuditStreamKind {
    /** Real-time: one signed POST per audit event to the customer's collector URL, with retry/backoff and dead-letter. */
    HTTPS,
    /** Periodic: versioned JSONL batches plus signed manifests to a gs:// bucket, keyset-watermarked, bounded per pass. */
    BUCKET
}
