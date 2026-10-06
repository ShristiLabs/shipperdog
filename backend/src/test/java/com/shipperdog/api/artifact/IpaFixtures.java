package com.shipperdog.api.artifact;

import com.dd.plist.BinaryPropertyListWriter;
import com.dd.plist.NSDictionary;
import com.dd.plist.NSString;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Programmatic IPA fixtures (issue #21, S5-02, AC4's fixture matrix):
 * real ZIP archives with real binary plists — dd-plist writes the
 * plists ({@code BinaryPropertyListWriter}), {@code ZipOutputStream}
 * builds the archives — so no binary is ever committed to git and
 * every matrix leg (release, unsigned, missing plist, non-zip, padded)
 * is a method call. The provisioning-profile leg is a fake CMS
 * container: DER noise around the XML plist payload, exactly the
 * shape the marker scan recovers (the parser never validates PKCS7 —
 * that is S5-05).
 */
public final class IpaFixtures {

    /** The bundle identifier every fixture plist carries. */
    public static final String BUNDLE_ID = "com.acme.banking";

    public static final String VERSION_NAME = "7.12.0";

    public static final String BUILD = "421";

    public static final String MINIMUM_OS = "15.0";

    public static final String DISPLAY_NAME = "Acme Banking";

    public static final String PROFILE_NAME = "Acme Pilot Distribution";

    public static final String PROFILE_UUID = "5A3F9C2E-1111-4B7D-9C0A-DEADBEEF0001";

    public static final String TEAM_ID = "AB12CD34EF";

    private static final SecureRandom RANDOM = new SecureRandom();

    private IpaFixtures() {}

    /** The release-build leg: full plist plus an embedded provisioning profile. */
    public static byte[] releaseIpa() throws IOException {
        return ipa(infoPlist(), provisioningProfile());
    }

    /** The unsigned leg: identical identity fields, no profile. */
    public static byte[] unsignedIpa() throws IOException {
        return ipa(infoPlist(), null);
    }

    /** A release-shaped IPA padded with an incompressible payload entry. */
    public static byte[] paddedIpa(int paddingBytes) throws IOException {
        byte[] padding = new byte[paddingBytes];
        RANDOM.nextBytes(padding);
        return ipa(infoPlist(), provisioningProfile(), padding);
    }

    /** The missing-plist leg: a valid ZIP whose .app bundle has no Info.plist. */
    public static byte[] missingPlistIpa() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("Payload/Bank.app/_CodeSignature/CodeResources"));
            zip.write("<plist><dict/></plist>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    /** The non-zip leg: bytes that are not an archive at all, renamed .ipa. */
    public static byte[] notAZip(int size) {
        byte[] bytes = new byte[size];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    /** An IPA whose Info.plist is XML rather than binary (the weird-plists leg). */
    public static byte[] xmlPlistIpa() throws IOException {
        String plist = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" "
                + "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
                + "<plist version=\"1.0\"><dict>"
                + "<key>CFBundleIdentifier</key><string>"
                + BUNDLE_ID
                + "</string>"
                + "<key>CFBundleShortVersionString</key><string>"
                + VERSION_NAME
                + "</string>"
                + "<key>CFBundleVersion</key><string>"
                + BUILD
                + "</string>"
                + "<key>MinimumOSVersion</key><string>"
                + MINIMUM_OS
                + "</string>"
                + "<key>CFBundleDisplayName</key><string>"
                + DISPLAY_NAME
                + "</string>"
                + "</dict></plist>";
        return ipa(plist.getBytes(StandardCharsets.UTF_8), null);
    }

    /** An IPA whose plist carries no CFBundleIdentifier (the other weird leg). */
    public static byte[] identifierlessIpa() throws IOException {
        NSDictionary plist = new NSDictionary();
        plist.put("CFBundleShortVersionString", new NSString(VERSION_NAME));
        plist.put("CFBundleVersion", new NSString(BUILD));
        return ipa(BinaryPropertyListWriter.writeToArray(plist), null);
    }

    /** An IPA whose CFBundleVersion is not a plain integer. */
    public static byte[] nonNumericBuildIpa() throws IOException {
        NSDictionary plist = infoPlistValues("1.2.3-beta.1");
        return ipa(BinaryPropertyListWriter.writeToArray(plist), null);
    }

    /** The fixture plist as a real binary plist: every AC1 field populated. */
    public static byte[] infoPlist() throws IOException {
        return BinaryPropertyListWriter.writeToArray(infoPlistValues(BUILD));
    }

    /** The provisioning profile's XML plist payload. */
    public static String profilePayload() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" "
                + "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
                + "<plist version=\"1.0\"><dict>"
                + "<key>Name</key><string>"
                + PROFILE_NAME
                + "</string>"
                + "<key>UUID</key><string>"
                + PROFILE_UUID
                + "</string>"
                + "<key>TeamIdentifiers</key><array><string>"
                + TEAM_ID
                + "</string></array>"
                + "<key>ExpirationDate</key><date>2031-01-01T00:00:00Z</date>"
                + "<key>Entitlements</key><dict>"
                + "<key>application-identifier</key><string>"
                + TEAM_ID
                + "."
                + BUNDLE_ID
                + "</string>"
                + "<key>aps-environment</key><string>production</string>"
                + "<key>get-task-allow</key><"
                + "false/>"
                + "</dict>"
                + "<key>ProvisionedDevices</key><array><string>device-1</string></array>"
                + "</dict></plist>";
    }

    /** A fake CMS container: DER-ish noise around the XML payload. */
    public static byte[] provisioningProfile() {
        byte[] payload = profilePayload().getBytes(StandardCharsets.UTF_8);
        byte[] container = new byte[payload.length + 96];
        RANDOM.nextBytes(container);
        System.arraycopy(payload, 0, container, 48, payload.length);
        return container;
    }

    /** A profile-shaped blob with no XML payload inside (damaged-profile leg). */
    public static byte[] garbageProfile() {
        byte[] container = new byte[512];
        RANDOM.nextBytes(container);
        return container;
    }

    /** A plist whose CFBundleIdentifier exceeds the V16 column width. */
    public static byte[] oversizedIdentifierIpa() throws IOException {
        NSDictionary plist = infoPlistValues(BUILD);
        plist.put("CFBundleIdentifier", new NSString("a".repeat(300)));
        return ipa(BinaryPropertyListWriter.writeToArray(plist), null);
    }

    /** A plist whose display name is non-ASCII (the unicode boundary). */
    public static byte[] unicodeDisplayNameIpa() throws IOException {
        NSDictionary plist = infoPlistValues(BUILD);
        plist.put("CFBundleDisplayName", new NSString("银行银行银行"));
        return ipa(BinaryPropertyListWriter.writeToArray(plist), null);
    }

    /** A plist whose bundle identifier is a NUMBER, not a string. */
    public static byte[] numericIdentifierIpa() throws IOException {
        NSDictionary plist = infoPlistValues(BUILD);
        plist.put("CFBundleIdentifier", new com.dd.plist.NSNumber(12345));
        return ipa(BinaryPropertyListWriter.writeToArray(plist), null);
    }

    /** An IPA whose only entry is a ZERO-byte Info.plist. */
    public static byte[] emptyPlistIpa() throws IOException {
        return ipa(new byte[0], null);
    }

    /** An IPA whose .app bundle sits one directory deeper than Apple's shape. */
    public static byte[] nestedAppPathIpa() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("Payload/Deep/Bank.app/Info.plist"));
            zip.write(infoPlist());
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    /** The full identity plist with the given raw build string. */
    public static NSDictionary infoPlistValues(String build) {
        NSDictionary plist = new NSDictionary();
        plist.put("CFBundleIdentifier", new NSString(BUNDLE_ID));
        plist.put("CFBundleShortVersionString", new NSString(VERSION_NAME));
        plist.put("CFBundleVersion", new NSString(build));
        plist.put("MinimumOSVersion", new NSString(MINIMUM_OS));
        plist.put("CFBundleDisplayName", new NSString(DISPLAY_NAME));
        plist.put("CFBundleName", new NSString("Bank"));
        plist.put("DTPlatformVersion", new NSString("26.0"));
        return plist;
    }

    /** The fixture's entitlements as the parser flattens them. */
    public static Map<String, Object> expectedEntitlements() {
        Map<String, Object> entitlements = new LinkedHashMap<>();
        entitlements.put("application-identifier", TEAM_ID + "." + BUNDLE_ID);
        entitlements.put("aps-environment", "production");
        entitlements.put("get-task-allow", false);
        return entitlements;
    }

    private static byte[] ipa(byte[] plist, byte[] profile) throws IOException {
        return ipa(plist, profile, null);
    }

    private static byte[] ipa(byte[] plist, byte[] profile, byte[] padding) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("Payload/Bank.app/Info.plist"));
            zip.write(plist);
            zip.closeEntry();
            if (profile != null) {
                zip.putNextEntry(new ZipEntry("Payload/Bank.app/embedded.mobileprovision"));
                zip.write(profile);
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("Payload/Bank.app/Bank"));
            zip.write(new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
            zip.closeEntry();
            if (padding != null) {
                zip.putNextEntry(new ZipEntry("Payload/Bank.app/Frameworks/Padding.dylib"));
                zip.write(padding);
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    /** Flexible assembly for matrix legs the named fixtures do not cover. */
    public static final class IpaBuilder {

        private byte[] plist;

        private byte[] profile;

        public IpaBuilder infoPlist(byte[] plistBytes) {
            this.plist = plistBytes;
            return this;
        }

        public IpaBuilder profile(byte[] profileBytes) {
            this.profile = profileBytes;
            return this;
        }

        public byte[] build() throws IOException {
            return ipa(plist, profile);
        }
    }
}
