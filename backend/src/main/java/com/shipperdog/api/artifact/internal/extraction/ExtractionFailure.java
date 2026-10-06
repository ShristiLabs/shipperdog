package com.shipperdog.api.artifact.internal.extraction;

/**
 * The malformed-binary failure of artifact metadata extraction (issue
 * #21, AC3): every value names exactly what is wrong with the BINARY,
 * so the recorded error is actionable without a support round-trip.
 * Infrastructure failures (the store cannot be read) are deliberately
 * NOT this type — they surface as {@code ArtifactException}
 * STORAGE_UNAVAILABLE and the extraction service treats them as
 * transient, leaving the artifact VALIDATING.
 */
public class ExtractionFailure extends RuntimeException {

    /** The machine-readable codes persisted in {@code extraction_error}. */
    public enum Code {
        NOT_A_ZIP("the file is not a ZIP archive; a valid IPA is a ZIP with a Payload/*.app bundle, "
                + "an APK or AAB is a ZIP with an Android manifest"),
        ZIP64_UNSUPPORTED("the archive uses ZIP64 extensions, which M1 extraction does not support"),
        MALFORMED_ZIP("the ZIP structure is damaged; the archive cannot be walked"),
        MISSING_INFO_PLIST("no Payload/*.app/Info.plist entry exists in the archive"),
        MALFORMED_PLIST("the Info.plist exists but cannot be parsed, or lacks its required keys"),
        MISSING_MANIFEST("no AndroidManifest.xml entry exists: an APK carries one at the archive root, "
                + "an AAB under base/manifest/ (or manifest/)"),
        MALFORMED_MANIFEST("the AndroidManifest.xml exists but is not parseable Android binary XML, "
                + "or lacks its required fields"),
        MALFORMED_BUNDLE_CONFIG("the AAB's BundleConfig.pb exists but cannot be parsed for the version code"),
        UNKNOWN_FORMAT("the archive is a ZIP but neither an APK nor an AAB: no known Android manifest "
                + "layout exists inside; a renamed ZIP is not an Android artifact"),
        ENTRY_TOO_LARGE("an entry the parser must read exceeds the bounded-entry size cap"),
        ENTRY_ENCRYPTED("an entry the parser must read is encrypted"),
        UNSUPPORTED_COMPRESSION(
                "an entry the parser must read uses a compression method other than stored or deflated");

        final String defaultMessage;

        Code(String defaultMessage) {
            this.defaultMessage = defaultMessage;
        }

        /** The operator-readable explanation persisted beside the code. */
        public String defaultMessage() {
            return defaultMessage;
        }
    }

    private final Code code;

    public ExtractionFailure(Code code, String message) {
        super(message);
        this.code = code;
    }

    public ExtractionFailure(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** The persisted machine-readable code. */
    public Code code() {
        return code;
    }
}
