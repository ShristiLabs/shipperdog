package com.shipperdog.api.artifact.internal.storage;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.ReadChannel;
import com.google.cloud.WriteChannel;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.Storage.BlobWriteOption;
import com.google.cloud.storage.StorageException;
import com.shipperdog.api.artifact.ArtifactException;
import com.shipperdog.api.artifact.ArtifactStore;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * The GCS implementation of the {@link ArtifactStore} port (issue #20,
 * S5-01, ADR-0003): one client, two targets — the compose GCS emulator
 * (fsouza/fake-gcs-server) when a host is configured, production GCS
 * through application default credentials when it is blank. Binaries
 * stream in bounded chunks ({@code Storage.WriteChannel}); nothing
 * buffers a whole artifact in memory (the #8 buffering-ceiling debt is
 * not repeated here).
 *
 * <p>INV-003 shapes the write path: every object is created with the
 * {@code doesNotExist} precondition (write-once at the store, on top of
 * keys that embed a fresh artifact id), and the port exposes no
 * overwrite and no delete — an uploaded artifact cannot be replaced.
 *
 * <p>The resumable-session initiation ({@link #createResumableSession})
 * is the one hand-rolled call: the GCS JSON API's documented
 * {@code POST /upload/storage/v1/b/{bucket}/o?uploadType=resumable}
 * whose {@code Location} header IS the session URL the client streams
 * bytes to (issue #20 AC5). The official client offers no public way to
 * create a session and hand its URL to someone else — its own writer
 * consumes sessions internally — so this one request goes out with
 * {@link java.net.http.HttpClient}, authenticated with an application
 * default token in production and unauthenticated against the emulator,
 * exactly the request the client library sends. The emulator and real
 * GCS answer it identically (verified against fsouza/fake-gcs-server
 * 1.56.1 in the integration suite).
 */
@Component
public class GcsArtifactStore implements ArtifactStore {

    private static final Logger log = LoggerFactory.getLogger(GcsArtifactStore.class);

    /** Chunk size of streamed writes; a multiple of the 256 KiB GCS floor. */
    private static final int WRITE_CHUNK_BYTES = 8 * 1024 * 1024;

    /** Chunk size of streamed download reads (issue #38, S8-04): bounded, never whole-object. */
    private static final int READ_CHUNK_BYTES = 1024 * 1024;

    private final Storage storage;
    private final ArtifactStorageProperties properties;
    private final HttpClient http;
    private final AtomicBoolean bucketEnsured = new AtomicBoolean(false);

    /** Cached application-default token source; null in emulator mode. */
    private final GoogleCredentials credentials;

    public GcsArtifactStore(@Qualifier("artifactStorage") Storage storage, ArtifactStorageProperties properties) {
        this.storage = storage;
        this.properties = properties;
        this.http =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.credentials = properties.emulatorMode() ? null : defaultCredentialsOrNull();
    }

    @Override
    public void put(String storageKey, InputStream content, String contentType) {
        ensureBucket();
        BlobInfo target = BlobInfo.newBuilder(properties.bucket(), storageKey)
                .setContentType(contentType)
                .build();
        try (WriteChannel writer = storage.writer(target, BlobWriteOption.doesNotExist())) {
            writer.setChunkSize(WRITE_CHUNK_BYTES);
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = content.read(buffer)) >= 0) {
                if (read > 0) {
                    writer.write(ByteBuffer.wrap(buffer, 0, read));
                }
            }
        } catch (StorageException | IOException e) {
            throw unavailable("cannot store object at " + storageKey, e);
        }
    }

    @Override
    public String createResumableSession(String storageKey, String contentType) {
        ensureBucket();
        String key = URLEncoder.encode(storageKey, StandardCharsets.UTF_8);
        String endpoint =
                effectiveHost() + "/upload/storage/v1/b/" + properties.bucket() + "/o?uploadType=resumable&name=" + key;
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint))
                // Content-Length is derived by the client itself (a
                // zero-body POST); setting it by hand is refused.
                .header("X-Goog-Upload-Protocol", "resumable")
                .header("X-Goog-Upload-Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(30));
        if (credentials != null) {
            try {
                request.header(
                        "Authorization",
                        "Bearer " + credentials.refreshAccessToken().getTokenValue());
            } catch (IOException e) {
                throw unavailable("cannot authenticate the upload-session request", e);
            }
        }
        HttpResponse<Void> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.discarding());
        } catch (IOException e) {
            throw unavailable("cannot create the upload session for " + storageKey, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable("interrupted while creating the upload session for " + storageKey, e);
        }
        if (response.statusCode() != 200
                || response.headers().firstValue("Location").isEmpty()) {
            throw unavailable(
                    "upload session was not granted for " + storageKey + " (status " + response.statusCode() + ")",
                    null);
        }
        return response.headers().firstValue("Location").orElseThrow();
    }

    @Override
    public Optional<StoredChecksum> hash(String storageKey) {
        BlobId id = BlobId.of(properties.bucket(), storageKey);
        try {
            if (storage.get(id) == null) {
                return Optional.empty();
            }
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            try (ReadChannel reader = storage.reader(id);
                    InputStream raw = Channels.newInputStream(reader);
                    DigestInputStream digested = new DigestInputStream(raw, sha256)) {
                long size = digested.transferTo(OutputStream.nullOutputStream());
                return Optional.of(new StoredChecksum(HexFormat.of().formatHex(sha256.digest()), size));
            }
        } catch (StorageException | IOException | NoSuchAlgorithmException e) {
            throw unavailable("cannot read object at " + storageKey, e);
        }
    }

    @Override
    public OptionalLong sizeOf(String storageKey) {
        try {
            BlobInfo info = storage.get(BlobId.of(properties.bucket(), storageKey));
            return info == null ? OptionalLong.empty() : OptionalLong.of(info.getSize());
        } catch (StorageException e) {
            throw unavailable("cannot stat object at " + storageKey, e);
        }
    }

    @Override
    public byte[] read(String storageKey, long offset, int length) {
        BlobId id = BlobId.of(properties.bucket(), storageKey);
        try (ReadChannel reader = storage.reader(id)) {
            reader.seek(offset);
            reader.limit(offset + length);
            ByteBuffer buffer = ByteBuffer.allocate(length);
            while (buffer.hasRemaining()) {
                int read = reader.read(buffer);
                if (read < 0) {
                    throw unavailable(
                            "object at " + storageKey + " ended inside the requested range [" + offset + ", "
                                    + (offset + length) + ")",
                            null);
                }
            }
            return buffer.array();
        } catch (StorageException | IOException e) {
            throw unavailable("cannot read range from object at " + storageKey, e);
        }
    }

    @Override
    public String signV4DownloadUrl(String storageKey, String fileName, String contentType, Duration ttl) {
        // Emulator deployments never reach here: the download tier
        // routes emulator issuance to the backend proxy (the store's
        // NoCredentials identity cannot sign, and fake-gcs-server does
        // not verify V4 signatures — ADR-0003's download-tier
        // amendment records the verification). A call in emulator mode
        // is a wiring fault and fails loudly, never silently.
        if (properties.emulatorMode()) {
            throw unavailable("V4 signing is unavailable against the emulator host", null);
        }
        BlobInfo target = BlobInfo.newBuilder(properties.bucket(), storageKey)
                .setContentType(contentType)
                .build();
        try {
            return storage.signUrl(
                            target,
                            ttl.toMillis(),
                            TimeUnit.MILLISECONDS,
                            Storage.SignUrlOption.withV4Signature(),
                            Storage.SignUrlOption.withQueryParams(java.util.Map.of(
                                    "response-content-disposition",
                                    "attachment; filename=\"" + sanitizeFileName(fileName) + "\"")))
                    .toString();
        } catch (StorageException e) {
            throw unavailable("cannot sign the download URL for " + storageKey, e);
        }
    }

    @Override
    public InputStream open(String storageKey) {
        BlobId id = BlobId.of(properties.bucket(), storageKey);
        try {
            // Two RPCs by design (the #38 review's low finding, note
            // adopted with issue #39): the existence get() distinguishes
            // a missing object (404 to the caller) from a mid-stream
            // failure, and the objects are immutable so nothing can
            // change between the two calls. reader() alone cannot make
            // that distinction until bytes are read.
            if (storage.get(id) == null) {
                throw unavailable("no object at " + storageKey, null);
            }
            ReadChannel reader = storage.reader(id);
            reader.setChunkSize(READ_CHUNK_BYTES);
            return Channels.newInputStream(reader);
        } catch (StorageException e) {
            throw unavailable("cannot open object at " + storageKey, e);
        }
    }

    /**
     * The response-disposition filename is quoted into an HTTP header
     * value: control characters and quotes are stripped so a hostile
     * upload name cannot smuggle header syntax (the name was already
     * bounded at upload; this is the belt to that braces).
     */
    private static String sanitizeFileName(String fileName) {
        return fileName == null ? "artifact" : fileName.replaceAll("[\"\\\\\\r\\n\\t;]", "_");
    }

    /** The bucket's existence is checked once per process, tolerantly. */
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
            // next upload retries the check.
            log.warn("artifact bucket '{}' is not ready: {}", properties.bucket(), e.getMessage());
        }
    }

    /** The endpoint every GCS call targets: the emulator host, or GCS itself. */
    private String effectiveHost() {
        return properties.emulatorMode() ? trimTrailingSlash(properties.host()) : "https://storage.googleapis.com";
    }

    private static String trimTrailingSlash(String host) {
        return host.endsWith("/") ? host.substring(0, host.length() - 1) : host;
    }

    private static GoogleCredentials defaultCredentialsOrNull() {
        try {
            return GoogleCredentials.getApplicationDefault();
        } catch (IOException e) {
            log.warn("no application default credentials found; production GCS calls will fail until configured");
            return null;
        }
    }

    private static ArtifactException unavailable(String message, Exception cause) {
        if (cause == null) {
            return new ArtifactException(ArtifactException.Code.STORAGE_UNAVAILABLE, message);
        }
        return new ArtifactException(ArtifactException.Code.STORAGE_UNAVAILABLE, message + ": " + cause.getMessage());
    }
}
