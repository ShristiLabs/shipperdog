package com.shipperdog.api.feedback.internal.storage;

import com.google.cloud.WriteChannel;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.Storage.BlobWriteOption;
import com.google.cloud.storage.StorageException;
import com.shipperdog.api.feedback.FeedbackAttachmentStore;
import com.shipperdog.api.feedback.FeedbackException;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The GCS implementation of the {@link FeedbackAttachmentStore} port
 * (issue #46, S11-01): the artifact module's two-target store shape
 * applied to attachments — the compose GCS emulator
 * (fsouko/fake-gcs-server) when a host is configured, production GCS
 * through application default credentials when it is blank. The
 * module's OWN client bean (modules never import each other's
 * internals, ArchUnit-enforced), the same discipline: writes are
 * write-once ({@code doesNotExist} precondition on top of keys that
 * embed a fresh attachment id), streamed in bounded chunks with the
 * SHA-256 computed in the same pass, and reads stream through the
 * response writer — nothing buffers a whole attachment.
 */
@Component
public class GcsFeedbackAttachmentStore implements FeedbackAttachmentStore {

    private static final Logger log = LoggerFactory.getLogger(GcsFeedbackAttachmentStore.class);

    /** Chunk size of streamed writes; a multiple of the 256 KiB GCS floor (attachments are small, one chunk is the common case). */
    private static final int WRITE_CHUNK_BYTES = 1024 * 1024;

    /** Chunk size of streamed reads: bounded, never whole-object. */
    private static final int READ_CHUNK_BYTES = 1024 * 1024;

    private final Storage storage;

    private final FeedbackStorageProperties properties;

    private final AtomicBoolean bucketEnsured = new AtomicBoolean(false);

    public GcsFeedbackAttachmentStore(Storage feedbackAttachmentStorage, FeedbackStorageProperties properties) {
        this.storage = feedbackAttachmentStorage;
        this.properties = properties;
    }

    @Override
    public String put(String storageKey, InputStream content, String contentType) {
        ensureBucket();
        BlobInfo target = BlobInfo.newBuilder(properties.bucket(), storageKey)
                .setContentType(contentType)
                .build();
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw unavailable("SHA-256 is unavailable", e);
        }
        try (DigestInputStream digested = new DigestInputStream(content, sha256);
                WriteChannel writer = storage.writer(target, BlobWriteOption.doesNotExist())) {
            writer.setChunkSize(WRITE_CHUNK_BYTES);
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = digested.read(buffer)) >= 0) {
                if (read > 0) {
                    writer.write(ByteBuffer.wrap(buffer, 0, read));
                }
            }
        } catch (StorageException | IOException e) {
            throw unavailable("cannot store attachment at " + storageKey, e);
        }
        return HexFormat.of().formatHex(sha256.digest());
    }

    @Override
    public InputStream open(String storageKey) {
        BlobId id = BlobId.of(properties.bucket(), storageKey);
        try {
            // The existence get() distinguishes a missing object (a
            // metadata row whose bytes never landed — operator
            // territory) from a mid-stream failure; objects are
            // immutable so nothing can change between the two calls.
            if (storage.get(id) == null) {
                throw unavailable("no attachment object at " + storageKey, null);
            }
            com.google.cloud.ReadChannel reader = storage.reader(id);
            reader.setChunkSize(READ_CHUNK_BYTES);
            return Channels.newInputStream(reader);
        } catch (StorageException e) {
            throw unavailable("cannot open attachment at " + storageKey, e);
        }
    }

    /** The bucket's existence is checked once per process, tolerantly (the artifact store's shape). */
    @PostConstruct
    void ensureBucketAtStartup() {
        if (properties.createBucketIfAbsent()) {
            tryEnsureBucket();
        }
    }

    private void ensureBucket() {
        if (properties.createBucketIfAbsent()) {
            tryEnsureBucket();
        }
    }

    private synchronized void tryEnsureBucket() {
        if (bucketEnsured.get()) {
            return;
        }
        try {
            if (storage.get(properties.bucket()) == null) {
                storage.create(BucketInfo.of(properties.bucket()));
            }
            bucketEnsured.set(true);
        } catch (StorageException e) {
            // The API stays up when storage is not reachable yet; the
            // next submission retries the check.
            log.warn("feedback bucket '{}' is not ready: {}", properties.bucket(), e.getMessage());
        }
    }

    private static FeedbackException unavailable(String message, Exception cause) {
        log.warn("feedback attachment storage: {} ({})", message, cause == null ? "no cause" : cause.toString());
        return new FeedbackException(FeedbackException.Code.STORAGE_UNAVAILABLE, message);
    }
}
