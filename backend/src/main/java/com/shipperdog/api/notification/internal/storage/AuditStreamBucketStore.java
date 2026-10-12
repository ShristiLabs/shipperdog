package com.shipperdog.api.notification.internal.storage;

/**
 * The bucket leg's object-write port (issue #71, S14-05, AC2): one
 * bounded write of a batch (or manifest) object to the export's
 * destination. The {@code gs://} implementation is {@code
 * GcsAuditStreamBucketStore}; the port is the seam the S3 connector
 * of the AWS follow-up implements (the export surface stores the
 * scheme, the store answers whether it serves it).
 */
public interface AuditStreamBucketStore {

    /**
     * Whether this store serves the URI's scheme (the create/edit
     * surface's first destination question).
     */
    boolean serves(String bucketUri);

    /**
     * Writes the bytes to the object the URI names. Idempotent by the
     * caller's construction: the batcher's window is deterministic,
     * so a crashed pass's re-write sends identical bytes to the same
     * object.
     *
     * @param objectUri the full object URI ({@code gs://bucket/key})
     * @param content the object bytes
     * @param contentType the object's content type
     */
    void write(String objectUri, byte[] content, String contentType);
}
