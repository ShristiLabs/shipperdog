package com.shipperdog.api.artifact.internal.extraction;

import com.dd.plist.NSArray;
import com.dd.plist.NSDictionary;
import com.dd.plist.NSNumber;
import com.dd.plist.NSObject;
import com.dd.plist.PropertyListParser;
import com.shipperdog.api.artifact.ArtifactBinary;
import com.shipperdog.api.artifact.ArtifactMetadataParser;
import com.shipperdog.api.artifact.ExtractedMetadata;
import com.shipperdog.api.artifact.ExtractedMetadata.Signing;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The iOS IPA parser (issue #21, S5-02, FR-005): walks the stored
 * object's ZIP central directory, reads {@code Payload/*.app/Info.plist}
 * (binary or XML — dd-plist detects the format), and lifts the
 * identity fields. When the bundle carries an {@code
 * embedded.mobileprovision}, its XML plist payload is marker-extracted
 * from the CMS container and parsed for the signing tier — fields
 * stored, never verified (S5-05 owns verification; the issue's own
 * scope line).
 *
 * <p>The M1 provisioning-profile handling is deliberately the simple
 * one the issue's technical notes prescribe: the CMS/PKCS7 container's
 * XML payload sits verbatim inside the DER blob, bounded by the first
 * {@code <?xml} and the last {@code </plist>} byte sequence, so a
 * marker scan recovers it without a PKCS7 parser; full certificate
 * chain validation is S5-05's job. A profile whose payload cannot be
 * recovered is a signing-information gap, not a failed extraction:
 * unsigned builds exist (the fixture matrix's "unsigned" leg), so the
 * plist tier alone decides success.
 *
 * <p>Reads are ranged and capped (ZipRanges): a 500 MB IPA costs a
 * few kilobytes. Nothing here allocates by artifact size.
 */
@Component
public class IpaMetadataParser implements ArtifactMetadataParser {

    /** The platform of every artifact this parser accepts. */
    private static final String PLATFORM = "IOS";

    /** {@code Payload/Anything.app/Info.plist} — the bundle's manifest. */
    private static final Pattern INFO_PLIST = Pattern.compile("^Payload/[^/]+\\.app/Info\\.plist$");

    /** The provisioning profile's fixed name inside the bundle. */
    private static final String PROFILE_NAME = "embedded.mobileprovision";

    /** The attribution recorded in every extraction document. */
    private static final String PARSER = "ipa:dd-plist";

    /** Marker that opens the CMS container's XML payload. */
    private static final byte[] XML_START = "<?xml".getBytes(StandardCharsets.US_ASCII);

    /** Marker that closes the CMS container's XML payload. */
    private static final byte[] XML_END = "</plist>".getBytes(StandardCharsets.US_ASCII);

    private final ArtifactExtractionProperties properties;

    public IpaMetadataParser(ArtifactExtractionProperties properties) {
        this.properties = properties;
    }

    @Override
    public String platform() {
        return PLATFORM;
    }

    @Override
    public ExtractedMetadata parse(ArtifactBinary binary) {
        long startedAt = System.nanoTime();
        ZipRanges zip = ZipRanges.open(binary.ranges(), binary.size(), properties.maxEntryBytes());
        ZipRanges.Entry infoPlist = firstEntry(zip, INFO_PLIST)
                .orElseThrow(() -> new ExtractionFailure(
                        ExtractionFailure.Code.MISSING_INFO_PLIST,
                        ExtractionFailure.Code.MISSING_INFO_PLIST.defaultMessage()));
        NSDictionary plist = parsePlist(read(zip, infoPlist));
        String bundleId = requiredString(plist, "CFBundleIdentifier");
        String versionName = optionalString(plist, "CFBundleShortVersionString");
        String buildNumberRaw = optionalString(plist, "CFBundleVersion");
        String minimumOs = optionalString(plist, "MinimumOSVersion");
        String displayName = optionalString(plist, "CFBundleDisplayName");
        if (displayName == null) {
            displayName = optionalString(plist, "CFBundleName");
        }
        Signing signing = signingOf(zip, infoPlist);
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
        return new ExtractedMetadata(
                bundleId,
                versionName,
                numericBuild(buildNumberRaw),
                buildNumberRaw,
                minimumOs,
                displayName,
                signing,
                Instant.now(),
                durationMs,
                PARSER);
    }

    // -------------------------------------------------------------------------
    // Signing tier: the embedded provisioning profile
    // -------------------------------------------------------------------------

    /**
     * Reads the bundle's provisioning profile when present. An absent
     * profile (unsigned/ad-hoc build) answers null; a present but
     * unreadable one is logged as a gap in the extraction document —
     * the identity fields decide success, and S5-05's verification
     * will judge signing properly.
     */
    private Signing signingOf(ZipRanges zip, ZipRanges.Entry infoPlist) {
        String bundleDirectory = infoPlist.name().substring(0, infoPlist.name().length() - "Info.plist".length());
        String profileEntry = bundleDirectory + PROFILE_NAME;
        java.util.Optional<ZipRanges.Entry> entry = zip.entries().stream()
                .filter(candidate -> candidate.name().equals(profileEntry))
                .findFirst();
        if (entry.isEmpty()) {
            return null;
        }
        byte[] container = read(zip, entry.get());
        byte[] payload = xmlPayloadOf(container);
        if (payload == null) {
            return null;
        }
        try {
            NSObject parsed = PropertyListParser.parse(payload);
            if (!(parsed instanceof NSDictionary profile)) {
                return null;
            }
            List<String> teamIds = new ArrayList<>();
            NSObject teams = profile.get("TeamIdentifiers");
            if (teams instanceof NSArray array) {
                for (NSObject team : array.getArray()) {
                    if (team != null) {
                        teamIds.add(team.toString());
                    }
                }
            }
            NSObject entitlementsValue = profile.get("Entitlements");
            Map<String, Object> entitlements =
                    entitlementsValue instanceof NSDictionary dict ? plainMapOf(dict) : Map.of();
            return new Signing(
                    optionalString(profile, "Name"),
                    optionalString(profile, "UUID"),
                    List.copyOf(teamIds),
                    dateOf(profile.get("ExpirationDate")),
                    entitlements);
        } catch (java.io.IOException
                | com.dd.plist.PropertyListFormatException
                | java.text.ParseException
                | org.xml.sax.SAXException
                | javax.xml.parsers.ParserConfigurationException
                | RuntimeException e) {
            // A damaged profile never fails the identity extraction.
            return null;
        }
    }

    /**
     * Marker-extracts the XML plist from the CMS container: the first
     * {@code <?xml} through the last {@code </plist>} in the blob.
     */
    private static byte[] xmlPayloadOf(byte[] container) {
        int start = indexOf(container, XML_START);
        int end = lastIndexOf(container, XML_END);
        if (start < 0 || end < start) {
            return null;
        }
        return java.util.Arrays.copyOfRange(container, start, end + XML_END.length);
    }

    /** First index of the marker in the blob, or -1. */
    private static int indexOf(byte[] blob, byte[] marker) {
        outer:
        for (int i = 0; i <= blob.length - marker.length; i++) {
            for (int j = 0; j < marker.length; j++) {
                if (blob[i + j] != marker[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** Last index of the marker in the blob, or -1. */
    private static int lastIndexOf(byte[] blob, byte[] marker) {
        outer:
        for (int i = blob.length - marker.length; i >= 0; i--) {
            for (int j = 0; j < marker.length; j++) {
                if (blob[i + j] != marker[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    // -------------------------------------------------------------------------
    // plist helpers
    // -------------------------------------------------------------------------

    private NSDictionary parsePlist(byte[] bytes) {
        NSObject parsed;
        try {
            parsed = PropertyListParser.parse(bytes);
        } catch (java.io.IOException
                | com.dd.plist.PropertyListFormatException
                | java.text.ParseException
                | org.xml.sax.SAXException
                | javax.xml.parsers.ParserConfigurationException
                | RuntimeException e) {
            throw new ExtractionFailure(
                    ExtractionFailure.Code.MALFORMED_PLIST,
                    "Info.plist cannot be parsed: " + e.getClass().getSimpleName(),
                    e);
        }
        if (!(parsed instanceof NSDictionary dictionary)) {
            throw new ExtractionFailure(
                    ExtractionFailure.Code.MALFORMED_PLIST, "Info.plist is not a property-list dictionary");
        }
        return dictionary;
    }

    private static String requiredString(NSDictionary plist, String key) {
        NSObject value = plist.get(key);
        if (!(value instanceof com.dd.plist.NSString text) || text.getContent().isBlank()) {
            throw new ExtractionFailure(ExtractionFailure.Code.MALFORMED_PLIST, "Info.plist lacks the required " + key);
        }
        return bounded(key, text.getContent().trim());
    }

    private static String optionalString(NSDictionary dictionary, String key) {
        NSObject value = dictionary.get(key);
        if (value instanceof com.dd.plist.NSString text) {
            String content = text.getContent().trim();
            return content.isEmpty() ? null : bounded(key, content);
        }
        return null;
    }

    /**
     * The V16 column widths are the contract: an identity field longer
     * than its column (a hostile or simply bizarre plist) is a
     * malformed PLIST for extraction purposes — rejecting here keeps
     * the failure actionable at parse time instead of an unrecordable
     * write that would leave the artifact silently VALIDATING.
     */
    private static String bounded(String key, String value) {
        int cap = switch (key) {
            case "MinimumOSVersion" -> 32;
            case "CFBundleIdentifier",
                    "CFBundleShortVersionString",
                    "CFBundleVersion",
                    "CFBundleDisplayName",
                    "CFBundleName" -> 255;
            default -> Integer.MAX_VALUE;
        };
        if (value.length() > cap) {
            throw new ExtractionFailure(
                    ExtractionFailure.Code.MALFORMED_PLIST,
                    key + " exceeds " + cap + " characters (" + value.length()
                            + "); the artifact registry cannot store it");
        }
        return value;
    }

    private static Instant dateOf(NSObject value) {
        if (value instanceof com.dd.plist.NSDate date) {
            return date.getDate().toInstant();
        }
        return null;
    }

    /**
     * CFBundleVersion is a string Apple does not constrain: plain
     * integers fill the numeric column, everything else ({@code 1.2.3},
     * {@code 2026.10.04-beta}) stays in the raw string and the column
     * stays null — V15's nullable build_number exists for exactly
     * this.
     */
    private static Long numericBuild(String buildNumberRaw) {
        if (buildNumberRaw == null || buildNumberRaw.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(buildNumberRaw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Flattens a parsed dictionary to plain Java types for the jsonb tier. */
    private static Map<String, Object> plainMapOf(NSDictionary dictionary) {
        Map<String, Object> plain = new LinkedHashMap<>();
        for (String key : dictionary.keySet()) {
            NSObject value = dictionary.get(key);
            if (value != null) {
                plain.put(key, plainValueOf(value));
            }
        }
        return plain;
    }

    private static Object plainValueOf(NSObject value) {
        if (value instanceof com.dd.plist.NSString text) {
            return text.getContent();
        }
        if (value instanceof NSNumber number) {
            if (number.isBoolean()) {
                return number.boolValue();
            }
            return number.longValue();
        }
        if (value instanceof com.dd.plist.NSDate date) {
            return date.getDate().toInstant().toString();
        }
        if (value instanceof NSArray array) {
            List<Object> list = new ArrayList<>();
            for (NSObject item : array.getArray()) {
                list.add(item == null ? null : plainValueOf(item));
            }
            return list;
        }
        if (value instanceof NSDictionary dictionary) {
            return plainMapOf(dictionary);
        }
        return value.toString();
    }

    private static java.util.Optional<ZipRanges.Entry> firstEntry(ZipRanges zip, Pattern name) {
        return zip.entries().stream()
                .filter(entry -> name.matcher(entry.name()).matches())
                .findFirst();
    }

    private static byte[] read(ZipRanges zip, ZipRanges.Entry entry) {
        return zip.readEntry(entry);
    }
}
