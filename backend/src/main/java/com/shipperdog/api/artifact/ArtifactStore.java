package com.shipperdog.api.artifact;

import java.io.InputStream;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The object-storage port of the artifact module (issue #20, S5-01,
 * FR-007, PRD 24, ADR-0003): binaries live in object storage under the
 * PRD 24 layout, PostgreSQL keeps metadata only — never bytes. The
 * port's surface is deliberately INV-003-shaped: it can create objects
 * and read them back for hashing, and it has NO overwrite and NO
 * delete, because an uploaded artifact cannot be replaced (INV-003)
 * and the durable registry is the only thing that makes an object an
 * artifact.
 *
 * <p>Object keys are server-derived only ({@code /{organizationId}/
 * {tenantId}/{applicationId}/{versionId}/{artifactId}/artifact}, the
 * pre-allocated artifact id in the path — never a client filename),
 * and addressing is never authorization (PRD 24's own note: the signed
 * download URLs of S8-04 carry the decision, not the path).
 *
 * <p>Callers stream: {@link #put} consumes the given stream without
 * buffering it whole (the request part is disk-buffered by the servlet
 * layer), and {@link #hash} streams the object back through a digest
 * — no method of this port ever exposes a whole-artifact byte array.
 * The parser's read side (issue #21, S5-02 — the gap ADR-0003 left
 * for that story) is deliberately RANGED, not whole-object: the IPA
 * parser needs the ZIP central directory (at the end of the object)
 * and a handful of small entries, so {@link #sizeOf} plus {@link
 * #read} fetch kilobytes out of a multi-hundred-MB binary instead of
 * streaming it — the same no-whole-binary discipline as the write
 * side, in seekable form. The download tier (issue #38, S8-04) adds
 * its own read surface — {@link #signV4DownloadUrl} and the streaming
 * {@link #open} — because serving bytes to testers is a different
 * concern from parsing them (ADR-0003's own separation, kept here).
 */
public interface ArtifactStore {

    /**
     * Stores the stream's bytes at the key, refusing if the object
     * already exists (write-once, INV-003's belt-and-braces: keys
     * embed a fresh artifact id, so a collision is a fault, not a
     * request).
     *
     * @param storageKey the server-derived object key
     * @param content the byte stream; consumed, never buffered whole
     * @param contentType the artifact's media type (e.g.
     *     {@code application/octet-stream})
     * @throws ArtifactException STORAGE_UNAVAILABLE when the store
     *     rejects the write or the object already exists
     */
    void put(String storageKey, InputStream content, String contentType);

    /**
     * Creates a resumable upload session for the key (issue #20 AC5,
     * the direct-to-storage path for large binaries) and returns the
     * session URL the client streams bytes to — short-lived by GCS
     * resumable-session semantics, bounded on ShipperDog's side by the
     * upload-session TTL that gates finalize.
     */
    String createResumableSession(String storageKey, String contentType);

    /**
     * Streams the stored object back through a SHA-256 digest (the
     * resumable path's finalize: the server never saw the client's
     * direct bytes, so the checksum is computed from the stored object
     * itself, PRD 18's checksum step). Empty when no object exists at
     * the key — the caller decides what an absent object means (a
     * session the client never used).
     *
     * @return the lowercase hex SHA-256 and the byte count
     * @throws ArtifactException STORAGE_UNAVAILABLE when the store
     *     fails
     */
    Optional<StoredChecksum> hash(String storageKey);

    /**
     * The stored object's byte count from metadata — no content is
     * read (issue #21, S5-02: the ZIP reader sizes its backward scan
     * for the end-of-central-directory record without fetching the
     * binary). Empty when no object exists at the key.
     */
    OptionalLong sizeOf(String storageKey);

    /**
     * Reads exactly {@code length} bytes of the stored object starting
     * at {@code offset} (issue #21, S5-02): the ranged read the IPA
     * parser's central-directory walk is built on. Partial reads are
     * the caller's contract violation (the central directory declared
     * the offsets); implementations answer what the store returns or
     * fail with STORAGE_UNAVAILABLE.
     *
     * @throws ArtifactException STORAGE_UNAVAILABLE when the store
     *     fails or the range leaves the object
     */
    byte[] read(String storageKey, long offset, int length);

    /**
     * Mints a short-lived signed download URL for the stored object
     * (issue #38, S8-04, FR-014): the production tier's issuance
     * primitive — a GCS V4-signed URL carrying the download decision,
     * never the storage path as authorization (PRD 24's own note). The
     * URL expires after {@code ttl}, answers {@code attachment} with
     * the artifact's file name, and grants READ on exactly this one
     * object — no overwrite, no delete, no listing (INV-003 is
     * structural on the read side too). Production mode only: the
     * compose emulator does not verify V4 signatures (ADR-0003's
     * documented emulator hostility, re-verified by this story — see
     * the ADR's download-tier amendment), so emulator deployments mint
     * the backend-proxy URL instead and never call this method.
     *
     * @throws ArtifactException STORAGE_UNAVAILABLE when the store
     *         cannot sign (missing signer credentials in production)
     */
    String signV4DownloadUrl(String storageKey, String fileName, String contentType, Duration ttl);

    /**
     * Streams the whole stored object (issue #38, S8-04): the download
     * tier's own read surface — the bytes a signed URL (or the
     * emulator-mode proxy that stands in for one) serves to a tester.
     * Unlike the ranged {@link #read} the parser tier uses, this is a
     * whole-object stream consumed by the response writer, never
     * buffered by the caller; ADR-0003's no-whole-binary discipline
     * holds because nothing reads it into memory. The download
     * decision already happened before any caller opens this — the
     * stream is the post-authorization egress, and the port keeps no
     * overwrite or delete.
     *
     * @throws ArtifactException STORAGE_UNAVAILABLE when the store
     *         fails or no object exists at the key
     */
    InputStream open(String storageKey);

    /** The checksum tuple {@link #hash} answers. */
    record StoredChecksum(String sha256Hex, long size) {}
}
