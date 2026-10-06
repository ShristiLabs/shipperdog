package com.shipperdog.api.artifact;

/**
 * The seekable view of a stored artifact binary handed to platform
 * parsers (issue #21, S5-02): the object's byte count plus a ranged
 * reader. Parsers receive this — never a stream of the whole binary
 * and never a {@link com.shipperdog.api.artifact.ArtifactStore}
 * reference — so the no-whole-binary discipline (ADR-0003) is
 * structural on the read side too: a parser CAN pull the whole object
 * through {@link RangeReader#read(long, int)} calls, but the ZIP-based
 * formats ShipperDog parses never need to, and the bounded-entry caps
 * live behind the parser implementations.
 *
 * <p>The S5-03 Android sibling consumes the same shape: APK/AAB are
 * ZIPs as well, so the central-directory technique carries over
 * unchanged.
 */
public record ArtifactBinary(long size, RangeReader ranges) {

    /**
     * Reads exactly {@code length} bytes at {@code offset}. Failing
     * implementations throw {@link com.shipperdog.api.artifact.ArtifactException}
     * STORAGE_UNAVAILABLE — the artifact module's infra failure, which
     * the extraction service distinguishes from a malformed-binary
     * failure.
     */
    @FunctionalInterface
    public interface RangeReader {

        /** Reads exactly {@code length} bytes at {@code offset}. */
        byte[] read(long offset, int length);
    }
}
