package com.shipperdog.api.artifact.internal.validation;

/**
 * The pipeline stages of FR-006 in the PRD's own order (issue #24,
 * S5-05): File Validation, Format Validation, Metadata Extraction,
 * Checksum, Signing Validation, Application Match, Version Match —
 * then READY. The pipeline executes exactly this order; the first
 * FAILED stage stops the run and every later stage records SKIPPED.
 *
 * <p>Stage-by-stage M1 semantics (docs/artifacts.md carries the user
 * view):
 *
 * <ul>
 *   <li>{@link #FILE} — the stored object still exists and its byte
 *       count equals the registered size: the binary the registry
 *       describes is the binary object storage holds.
 *   <li>{@link #FORMAT} — the structural container verdict (IPA on
 *       iOS; APK or AAB on Android, the S5-03 structural detection,
 *       never the file name's claim) against the platform and the
 *       uploaded extension; the verdict lands in the first-class
 *       {@code format} column so distribution-time channel rules (AAB
 *       is Play-only, FR-006's note) can act on a recorded fact.
 *   <li>{@link #METADATA} — the S5-02/S5-03 extraction outcome must be
 *       SUCCESS; a FAILED extraction fails here carrying its own code
 *       (the pipeline is scheduled after extraction settles, so this
 *       stage is the join point of the two async tiers).
 *   <li>{@link #CHECKSUM} — the stored object streams back through a
 *       SHA-256 digest and must equal the registered checksum
 *       (upload-time on the multipart path, storage-side at finalize;
 *       this stage re-proves it against the bytes as stored).
 *   <li>{@link #SIGNING} — M1 presence and self-consistency only: an
 *       iOS build must carry an internally coherent, unexpired
 *       embedded provisioning profile; an APK must carry at least one
 *       APK signing-scheme block. Certificate-chain and CMS signature
 *       verification are deliberately deferred (the issue's own scope
 *       line: depth deepens in M2/M3).
 *   <li>{@link #APPLICATION_MATCH} — the extracted bundle id / package
 *       name equals the application registry's registered identifier
 *       (S3-01), else the artifact FAILED with an explicit mismatch.
 *   <li>{@link #VERSION_MATCH} — the extracted marketing version
 *       equals the version record's name, and the extracted build
 *       (when a plain integer) equals the pinned build when the
 *       uploader pinned one. M1 rejects mismatches outright — no
 *       auto-amend proposal (the issue's decision).
 * </ul>
 */
public enum ValidationStage {
    FILE,
    FORMAT,
    METADATA,
    CHECKSUM,
    SIGNING,
    APPLICATION_MATCH,
    VERSION_MATCH
}
