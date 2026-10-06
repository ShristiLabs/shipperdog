package com.shipperdog.api.artifact;

/**
 * The platform parser port (issue #21, S5-02, FR-005): one
 * implementation per artifact platform — the issue's "keep the parser
 * behind an interface" rule, so S5-03's Android sibling is a new
 * implementation, never a rewrite of this seam.
 *
 * <p>Implementations are stateless beans of the artifact module. They
 * answer the {@link #platform()} they parse (the
 * {@code ArtifactEntity.Platform} name — {@code "IOS"} today,
 * {@code "ANDROID"} from S5-03) so the extraction service can route
 * each artifact to its parser without knowing either platform.
 *
 * <p>Failure contract: an unreadable, truncated, or malformed binary
 * throws the implementation's failure type with an actionable,
 * machine-readable code (the artifact module treats it as the
 * binary's own fault — the artifact moves to FAILED, issue #21 AC3);
 * a failure to READ the binary (storage) propagates as
 * {@link ArtifactException} STORAGE_UNAVAILABLE and is never the
 * binary's fault.
 */
public interface ArtifactMetadataParser {

    /** The platform name this parser handles (e.g. {@code "IOS"}). */
    String platform();

    /**
     * Extracts the metadata of one stored binary.
     *
     * @param binary the seekable view of the stored object; never
     *     buffered whole by a conforming implementation
     * @return the extracted fields; never null — a binary with no
     *     readable metadata is a failure, not an empty answer
     */
    ExtractedMetadata parse(ArtifactBinary binary);
}
