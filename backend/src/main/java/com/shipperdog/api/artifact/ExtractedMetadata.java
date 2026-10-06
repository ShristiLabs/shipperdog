package com.shipperdog.api.artifact;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * What a platform parser lifted out of a stored artifact binary (issue
 * #21, S5-02, FR-005, PRD 8.5): the identity fields every platform
 * carries — bundle/package identifier, marketing version, build, OS
 * floor — plus the open-ended per-platform payload (signing
 * information, entitlements, display name). This record is the
 * parser's whole answer; persistence and views decide how much of it
 * becomes a column (V16) and how much rides in the jsonb document.
 *
 * <p>Field-by-field semantics:
 *
 * <ul>
 *   <li>{@code bundleId} — {@code CFBundleIdentifier} on iOS, the
 *       package name on Android (S5-03). Required by the parsers: a
 *       plist or manifest without it is malformed, not merely sparse.
 *   <li>{@code versionName} — {@code CFBundleShortVersionString} on
 *       iOS, {@code versionName} on Android: the marketing version
 *       string, deliberately never a number.
 *   <li>{@code buildNumber} — the numeric build when the platform
 *       spells one as a plain integer ({@code CFBundleVersion} digits,
 *       Android {@code versionCode}); null when the raw value is not
 *       an integer (Apple allows {@code 1.2.3} and even letters) —
 *       the verbatim string always rides in {@code buildNumberRaw}.
 *       The upload API's CI-pinned build wins over extraction; the
 *       parser never knows which case it is in.
 *   <li>{@code minimumOs} — {@code MinimumOSVersion} on iOS,
 *       {@code minSdkVersion} on Android, as the platform spells it.
 *   <li>{@code targetOs} — Android-only (S5-03): {@code
 *       targetSdkVersion}, stored in the jsonb document for M3's Play
 *       target-API policy checks; null on iOS.
 *   <li>{@code format} — Android-only (S5-03): the STRUCTURALLY
 *       detected format, {@code "APK"} or {@code "AAB"} — never the
 *       file name's claim; rides in the jsonb document so FR-006's
 *       channel-format rules (AAB is Play-only) can act on it. Null
 *       on iOS (an IPA is an IPA).
 *   <li>{@code signing} — null when the binary carries no embedded
 *       provisioning profile (unsigned/ad-hoc builds): fields stored
 *       only, verification is S5-05 (the issue's own scope line).
 *   <li>{@code signatureBlocks} — Android-only (S5-03): APK v2/v3
 *       signing-block PAIR PRESENCE, detected structurally and stored
 *       without any verification (S5-05 owns judging signatures);
 *       null on iOS. AABs carry JAR signatures in {@code META-INF}
 *       that this marker deliberately does not chase.
 *   <li>{@code parser} — attribution string (e.g.
 *       {@code "ipa:dd-plist:1.29"}) so a stored document always says
 *       which code produced it.
 * </ul>
 *
 * @param buildNumber the numeric build, or null when the raw value is
 *     not a plain integer
 * @param buildNumberRaw the build string exactly as extracted, or null
 *     when the platform carries none
 */
public record ExtractedMetadata(
        String bundleId,
        String versionName,
        Long buildNumber,
        String buildNumberRaw,
        String minimumOs,
        String displayName,
        String targetOs,
        String format,
        Signing signing,
        SignatureBlocks signatureBlocks,
        Instant extractedAt,
        long durationMs,
        String parser) {

    /**
     * The signing tier S5-02 stores without verifying (issue #21 AC2):
     * everything readable from the embedded provisioning profile —
     * profile name and UUID, team identifiers, expiration, and the
     * entitlements dictionary. S5-05's signing validation is the
     * consumer that will judge these; here they are data.
     *
     * @param teamIdentifiers the profile's team ids in profile order
     * @param entitlements the flattened entitlements dictionary
     *     (strings, numbers, booleans, nested lists/maps — never raw
     *     parser types)
     */
    public record Signing(
            String profileName,
            String profileUuid,
            List<String> teamIdentifiers,
            Instant expiresAt,
            Map<String, Object> entitlements) {}

    /**
     * The Android signing markers S5-03 stores without verifying
     * (issue #22 AC1): whether the APK carries an APK Signature Scheme
     * v2 (block id {@code 0x7109871a}) or v3 (block id {@code
     * 0xf05368c0}) pair in its signing block. Presence is detected
     * structurally by walking the block's pairs with ranged reads;
     * the signatures themselves are never read or validated (S5-05
     * owns that, and v1 JAR signatures in {@code META-INF} are
     * deliberately out of scope).
     */
    public record SignatureBlocks(boolean v2SignatureBlock, boolean v3SignatureBlock) {}
}
