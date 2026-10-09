package com.shipperdog.api.feedback;

import java.io.InputStream;

/**
 * The object-storage port of the feedback module (issue #46, S11-01,
 * FR-027, PRD 8.13): attachment bytes live in object storage under the
 * tenant-scoped prefix {@code {organizationId}/{submitterTenantId}/
 * feedback/{feedbackId}/{attachmentId}}, PostgreSQL keeps metadata
 * only — never bytes (the ADR-0003 discipline the artifact module
 * established; attachments are deliberately small diagnostics, never
 * the hundred-megabyte binaries the artifact tier streams, but the
 * separation of bytes from the durable record is the same rule).
 *
 * <p>Object keys are server-derived only — the pre-allocated
 * attachment id in the path, never a client filename (the INV-003
 * discipline applied to attachments; the metadata row records the
 * tester's file name for display). The port exposes exactly the two
 * operations the surface needs: a write-once {@link #put} that
 * streams the part through a SHA-256 digest (never buffering the
 * whole file — the servlet layer already disk-buffered it), and the
 * post-authorization {@link #open} the download route streams to an
 * authorized reader. No overwrite, no delete, no listing: an
 * attachment cannot be replaced, and the durable metadata row is the
 * only thing that makes an object an attachment.
 */
public interface FeedbackAttachmentStore {

    /**
     * Stores the stream's bytes at the key, computing the SHA-256
     * while they pass (one pass, no buffering), refusing if the
     * object already exists (write-once: keys embed a fresh
     * attachment id, so a collision is a fault, not a request).
     *
     * @return the lowercase hex SHA-256 of the stored bytes
     * @throws FeedbackException STORAGE_UNAVAILABLE when the store
     *     rejects the write or the object already exists
     */
    String put(String storageKey, InputStream content, String contentType);

    /**
     * Streams the stored object back for the download route (the
     * post-authorization egress): consumed by the response writer,
     * never buffered by the caller. The permission check already
     * happened before any caller opens this.
     *
     * @throws FeedbackException STORAGE_UNAVAILABLE when the store
     *     cannot serve the object
     */
    InputStream open(String storageKey);
}
