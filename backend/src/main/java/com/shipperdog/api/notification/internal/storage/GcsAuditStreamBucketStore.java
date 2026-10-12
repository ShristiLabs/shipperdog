package com.shipperdog.api.notification.internal.storage;

import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The {@code gs://} implementation of the bucket-write port (issue
 * 71, S14-05, AC2): the feedback store's discipline applied to
 * exported batches -- the module's OWN client bean (modules never
 * import each other's internals, ArchUnit-enforced), emulator mode
 * for the compose GCS emulator, production GCS through application
 * default credentials when no host is configured. Writes are plain
 * object writes: the batcher's deterministic windows make a crashed
 * pass's re-write the same bytes to the same object (idempotent by
 * construction), so no write-once precondition is needed and no
 * partial window can ever publish.
 *
 * <p>Buckets are ensured once per process per name (created when
 * absent, the conflict of a racing creator swallowed): the compose
 * emulator starts empty, and a production customer bucket that
 * already exists is the expected case the ensure tolerates.
 */
@Component
public class GcsAuditStreamBucketStore implements AuditStreamBucketStore {

    private final Storage storage;

    private final ConcurrentHashMap<String, Boolean> ensuredBuckets = new ConcurrentHashMap<>();

    public GcsAuditStreamBucketStore(Storage auditStreamBucketStorage) {
        this.storage = auditStreamBucketStorage;
    }

    @Override
    public boolean serves(String bucketUri) {
        return bucketUri != null && bucketUri.startsWith("gs://");
    }

    @Override
    public void write(String objectUri, byte[] content, String contentType) {
        Parsed parsed = Parsed.of(objectUri);
        ensureBucket(parsed.bucket());
        // No write-once precondition, deliberately: a crashed pass
        // between the object write and the watermark commit re-runs
        // the SAME deterministic window, so the re-write sends
        // identical bytes to the same object -- overwrite IS the
        // idempotent recovery, and a precondition would wedge the
        // export on its own retry.
        storage.create(
                BlobInfo.newBuilder(parsed.bucket(), parsed.object())
                        .setContentType(contentType)
                        .build(),
                content);
    }

    private void ensureBucket(String bucket) {
        ensuredBuckets.computeIfAbsent(bucket, name -> {
            try {
                storage.create(BucketInfo.of(name));
            } catch (StorageException e) {
                // 409 CONFLICT: the bucket exists (a racing creator
                // or the customer's own) -- the ensure's happy path.
                if (e.getCode() != 409) {
                    throw e;
                }
            }
            return Boolean.TRUE;
        });
    }

    /** One object URI split at its first slash after the scheme (bucket, then the whole remainder as the key). */
    record Parsed(String bucket, String object) {

        static Parsed of(String objectUri) {
            String withoutScheme = objectUri.substring("gs://".length());
            int slash = withoutScheme.indexOf('/');
            if (slash <= 0 || slash == withoutScheme.length() - 1) {
                throw new IllegalArgumentException("the object URI must name a bucket and an object key");
            }
            return new Parsed(withoutScheme.substring(0, slash), withoutScheme.substring(slash + 1));
        }
    }
}
