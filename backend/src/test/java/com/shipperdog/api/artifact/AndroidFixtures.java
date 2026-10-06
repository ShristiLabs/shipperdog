package com.shipperdog.api.artifact;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Programmatic Android fixtures (issue #22, S5-03, AC5's fixture
 * matrix): real ZIP archives whose manifests are real Android binary
 * XML assembled chunk by chunk in code — string pool, namespace,
 * elements, typed attributes — so no binary is ever committed and
 * every matrix leg (APK, AAB, signing block, renamed ZIP, damage) is
 * a method call, mirroring {@link IpaFixtures}. The AXML writer here
 * is an INDEPENDENT implementation of the format from the parser
 * under test: writer and parser share no code, which is what makes
 * the round-trip legs meaningful.
 *
 * <p>The signing-block legs assemble the block by hand: the JDK's
 * {@code ZipOutputStream} cannot emit one, so {@link
 * #withSigningBlock(byte[], boolean, boolean)} splices a block in
 * front of the central directory and re-patches every directory-
 * declared local-header offset plus the EOCD's directory pointer —
 * exactly the byte surgery a real APK signer performs.
 */
public final class AndroidFixtures {

    /** The package name every fixture manifest carries. */
    public static final String PACKAGE = "com.acme.banking";

    public static final String VERSION_NAME = "7.12.0";

    public static final String VERSION_CODE = "421";

    public static final String MIN_SDK = "26";

    public static final String TARGET_SDK = "35";

    public static final String LABEL = "Acme Banking";

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    private static final String ANDROID_PREFIX = "android";

    private static final SecureRandom RANDOM = new SecureRandom();

    private AndroidFixtures() {}

    // -----------------------------------------------------------------------
    // APK legs
    // -----------------------------------------------------------------------

    /** The release leg: full manifest, dex noise, no signing block. */
    public static byte[] releaseApk() throws IOException {
        return apk(manifestAxml());
    }

    /** The signed leg: release shape plus the requested scheme pairs. */
    public static byte[] signedApk(boolean v2, boolean v3) throws IOException {
        return withSigningBlock(releaseApk(), v2, v3);
    }

    /** A release-shaped APK padded with an incompressible dex payload entry. */
    public static byte[] paddedApk(int paddingBytes) throws IOException {
        byte[] padding = noise(paddingBytes);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("AndroidManifest.xml"));
            zip.write(manifestAxml());
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("classes.dex"));
            zip.write(padding);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    /** An APK whose manifest is the given AXML bytes. */
    public static byte[] apk(byte[] manifest) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("AndroidManifest.xml"));
            zip.write(manifest);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("classes.dex"));
            zip.write(noise(64));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zip.write("Manifest-Version: 1.0\r\n".getBytes(StandardCharsets.US_ASCII));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    // -----------------------------------------------------------------------
    // AAB legs
    // -----------------------------------------------------------------------

    /**
     * The canonical AAB leg: {@code base/manifest/AndroidManifest.xml}
     * WITHOUT version attributes (bundletool strips them) and the
     * version code in {@code BundleConfig.pb} protobuf field 1.
     */
    public static byte[] aabVersionCodeInBundleConfig() throws IOException {
        return aab(manifestAxmlWithoutVersionCode(), bundleConfigVersionCode());
    }

    /** The leg where the base manifest itself still carries the version. */
    public static byte[] aabVersionCodeInManifest() throws IOException {
        return aab(manifestAxml(), bundleConfigVersionCode());
    }

    /** The alternate-layout leg: root {@code manifest/} + BundleConfig.pb. */
    public static byte[] aabRootManifest() throws IOException {
        return aabRoot(manifestAxml(), bundleConfigVersionCode());
    }

    /**
     * The sparse leg: neither the manifest nor the bundle config
     * carries a version code (field 1 absent) — the build stays null.
     */
    public static byte[] aabWithoutVersionCode() throws IOException {
        return aab(manifestAxmlWithoutVersionCode(), new byte[] {0x12, 0x03, 'a', 'b', 'c'});
    }

    /** The empty-config leg: zero bytes are a valid (field-less) proto. */
    public static byte[] aabWithEmptyBundleConfig() throws IOException {
        return aab(manifestAxmlWithoutVersionCode(), new byte[0]);
    }

    /** The corrupt-config leg: an unterminated varint can never be walked. */
    public static byte[] aabWithCorruptBundleConfig() throws IOException {
        byte[] config = new byte[16];
        java.util.Arrays.fill(config, (byte) 0xFF);
        return aab(manifestAxmlWithoutVersionCode(), config);
    }

    /**
     * BundleConfig bytes whose version code (field 1) sits AFTER a
     * length-delimited field — the wire walk must skip, not stop.
     */
    public static byte[] bundleConfigVersionCodeAfterOtherFields() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(0x12); // field 2, wire 2: three bytes
        bytes.write(0x03);
        bytes.write('a');
        bytes.write('b');
        bytes.write('c');
        writeVarint(bytes, 0x08); // field 1, wire 0
        writeVarint(bytes, Long.parseLong(VERSION_CODE));
        return bytes.toByteArray();
    }

    /** BundleConfig bytes with field 1 first, the common shape. */
    public static byte[] bundleConfigVersionCode() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        writeVarint(bytes, 0x08);
        writeVarint(bytes, Long.parseLong(VERSION_CODE));
        return bytes.toByteArray();
    }

    private static byte[] aab(byte[] baseManifest, byte[] bundleConfig) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(BUNDLE_CONFIG_ENTRY));
            zip.write(bundleConfig);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("base/manifest/AndroidManifest.xml"));
            zip.write(baseManifest);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("base/dex/classes.dex"));
            zip.write(noise(64));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static byte[] aabRoot(byte[] manifest, byte[] bundleConfig) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(BUNDLE_CONFIG_ENTRY));
            zip.write(bundleConfig);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("manifest/AndroidManifest.xml"));
            zip.write(manifest);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    // -----------------------------------------------------------------------
    // Manifest AXML assembly
    // -----------------------------------------------------------------------

    /** The full manifest: package, version, label, and both SDK levels. */
    public static byte[] manifestAxml() throws IOException {
        // UTF-16 is the CLASSIC aapt pool encoding every real APK
        // carries; the default legs exercise it, and the utf8PoolApk
        // leg covers the UTF-8 variant.
        return manifestAxml(false);
    }

    /** The full manifest with the string pool in the chosen encoding. */
    public static byte[] manifestAxml(boolean utf8Pool) throws IOException {
        return manifestAxml(PACKAGE, VERSION_NAME, VERSION_CODE, MIN_SDK, TARGET_SDK, LABEL, utf8Pool, false);
    }

    /** The bundletool-stripped manifest: no version attributes at all. */
    public static byte[] manifestAxmlWithoutVersionCode() throws IOException {
        return manifestAxml(PACKAGE, VERSION_NAME, null, MIN_SDK, TARGET_SDK, LABEL, false, false);
    }

    /**
     * The manifest with every knob: null drops an attribute; stringSdks
     * renders the SDK levels as STRING values instead of INT_DEC (the
     * aapt2 shape some build tools emit).
     */
    public static byte[] manifestAxml(
            String pkg,
            String versionName,
            String versionCode,
            String minSdk,
            String targetSdk,
            String label,
            boolean utf8Pool,
            boolean stringSdks)
            throws IOException {
        ElementSpec manifest = new ElementSpec("manifest");
        if (pkg != null) {
            manifest.string("package", pkg, false);
        }
        if (versionCode != null) {
            manifest.integer("versionCode", Long.parseLong(versionCode));
        }
        if (versionName != null) {
            manifest.string("versionName", versionName, true);
        }
        if (label != null) {
            manifest.string("label", label, true);
        }
        ElementSpec usesSdk = new ElementSpec("uses-sdk");
        if (minSdk != null) {
            if (stringSdks) {
                usesSdk.string("minSdkVersion", minSdk, true);
            } else {
                usesSdk.integer("minSdkVersion", Long.parseLong(minSdk));
            }
        }
        if (targetSdk != null) {
            if (stringSdks) {
                usesSdk.string("targetSdkVersion", targetSdk, true);
            } else {
                usesSdk.integer("targetSdkVersion", Long.parseLong(targetSdk));
            }
        }
        return axml(utf8Pool, manifest, usesSdk);
    }

    /** One element the fixture writer can emit. */
    static final class ElementSpec {

        private final String name;

        private final List<AttributeSpec> attributes = new ArrayList<>();

        ElementSpec(String name) {
            this.name = name;
        }

        void string(String attrName, String value, boolean androidNamespace) {
            attributes.add(new AttributeSpec(attrName, value, null, androidNamespace));
        }

        void integer(String attrName, long value) {
            attributes.add(new AttributeSpec(attrName, null, value, true));
        }
    }

    /** One attribute: a string value or an int-decimal value. */
    private record AttributeSpec(String name, String value, Long intValue, boolean androidNamespace) {}

    /** The bundle config's entry name. */
    static final String BUNDLE_CONFIG_ENTRY = "BundleConfig.pb";

    /** Assembles a complete AXML document: header, pool, namespace, elements. */
    static byte[] axml(boolean utf8Pool, ElementSpec... elements) throws IOException {
        // Intern every string the document will reference, in order.
        Map<String, Integer> pool = new LinkedHashMap<>();
        int androidNs = intern(pool, ANDROID_NS);
        int prefix = intern(pool, ANDROID_PREFIX);
        List<byte[]> chunks = new ArrayList<>();
        chunks.add(namespaceChunk(true, prefix, androidNs, pool));
        for (ElementSpec element : elements) {
            chunks.add(startElementChunk(element, androidNs, pool));
            chunks.add(endElementChunk(element, androidNs, pool));
        }
        chunks.add(namespaceChunk(false, prefix, androidNs, pool));
        byte[] stringPool = stringPoolChunk(pool, utf8Pool);
        int bodySize = stringPool.length;
        for (byte[] chunk : chunks) {
            bodySize += chunk.length;
        }
        ByteArrayOutputStream document = new ByteArrayOutputStream();
        writeU16(document, 0x0003); // RES_XML_TYPE
        writeU16(document, 8);
        writeU32(document, 8 + bodySize);
        document.write(stringPool);
        for (byte[] chunk : chunks) {
            document.write(chunk);
        }
        return document.toByteArray();
    }

    private static int intern(Map<String, Integer> pool, String value) {
        return pool.computeIfAbsent(value, ignored -> pool.size());
    }

    private static byte[] stringPoolChunk(Map<String, Integer> pool, boolean utf8) throws IOException {
        List<byte[]> encoded = new ArrayList<>();
        int dataBytes = 0;
        for (String value : pool.keySet()) {
            byte[] bytes = utf8 ? utf8PoolString(value) : utf16PoolString(value);
            encoded.add(bytes);
            dataBytes += bytes.length;
        }
        int offsetsAt = 28;
        int dataAt = offsetsAt + 4 * encoded.size();
        ByteArrayOutputStream chunk = new ByteArrayOutputStream();
        writeU16(chunk, 0x0001); // RES_STRING_POOL_TYPE
        writeU16(chunk, offsetsAt);
        writeU32(chunk, dataAt + dataBytes);
        writeU32(chunk, encoded.size());
        writeU32(chunk, 0); // style count
        writeU32(chunk, utf8 ? 1 << 8 : 0); // UTF8_FLAG
        writeU32(chunk, dataAt); // strings start, relative to chunk start
        writeU32(chunk, 0); // styles start
        int offset = 0;
        for (byte[] bytes : encoded) {
            writeU32(chunk, offset);
            offset += bytes.length;
        }
        for (byte[] bytes : encoded) {
            chunk.write(bytes);
        }
        return chunk.toByteArray();
    }

    private static byte[] utf16PoolString(String value) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        writeU16(bytes, value.length());
        for (int i = 0; i < value.length(); i++) {
            writeU16(bytes, value.charAt(i));
        }
        writeU16(bytes, 0); // terminator
        return bytes.toByteArray();
    }

    private static byte[] utf8PoolString(String value) {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        writeU16(bytes, value.length());
        bytes.write(utf8.length);
        bytes.write(utf8, 0, utf8.length);
        bytes.write(0); // terminator
        return bytes.toByteArray();
    }

    private static byte[] namespaceChunk(boolean start, int prefix, int uri, Map<String, Integer> pool)
            throws IOException {
        ByteArrayOutputStream chunk = new ByteArrayOutputStream();
        writeU16(chunk, start ? 0x0100 : 0x0101);
        writeU16(chunk, 16);
        writeU32(chunk, 24);
        writeU32(chunk, 1); // line number
        writeU32(chunk, 0xFFFFFFFF); // no comment
        writeU32(chunk, prefix);
        writeU32(chunk, uri);
        return chunk.toByteArray();
    }

    private static byte[] startElementChunk(ElementSpec element, int androidNs, Map<String, Integer> pool)
            throws IOException {
        int name = intern(pool, element.name);
        for (AttributeSpec attribute : element.attributes) {
            intern(pool, attribute.name());
            if (attribute.value() != null) {
                intern(pool, attribute.value());
            }
        }
        int count = element.attributes.size();
        ByteArrayOutputStream chunk = new ByteArrayOutputStream();
        writeU16(chunk, 0x0102); // RES_XML_START_ELEMENT_TYPE
        writeU16(chunk, 16);
        writeU32(chunk, 36 + 20L * count);
        writeU32(chunk, 1); // line number
        writeU32(chunk, 0xFFFFFFFF); // no comment
        writeU32(chunk, 0xFFFFFFFF); // element carries no namespace
        writeU32(chunk, name);
        writeU16(chunk, 20); // attributeStart, past the attr extension
        writeU16(chunk, 20); // attributeSize
        writeU16(chunk, count);
        writeU16(chunk, 0); // idIndex
        writeU16(chunk, 0); // classIndex
        writeU16(chunk, 0); // styleIndex
        for (AttributeSpec attribute : element.attributes) {
            int attrName = intern(pool, attribute.name());
            writeU32(chunk, attribute.androidNamespace() ? androidNs : 0xFFFFFFFF);
            writeU32(chunk, attrName);
            if (attribute.value() != null) {
                writeU32(chunk, intern(pool, attribute.value()));
                writeU16(chunk, 8); // typed value size
                chunk.write(0); // res0
                chunk.write(0x03); // TYPE_STRING
                writeU32(chunk, intern(pool, attribute.value()));
            } else {
                writeU32(chunk, 0xFFFFFFFF); // no raw literal for ints
                writeU16(chunk, 8);
                chunk.write(0); // res0
                chunk.write(0x10); // TYPE_INT_DEC
                writeU32(chunk, attribute.intValue());
            }
        }
        return chunk.toByteArray();
    }

    private static byte[] endElementChunk(ElementSpec element, int androidNs, Map<String, Integer> pool)
            throws IOException {
        ByteArrayOutputStream chunk = new ByteArrayOutputStream();
        writeU16(chunk, 0x0103);
        writeU16(chunk, 16);
        writeU32(chunk, 24);
        writeU32(chunk, 1);
        writeU32(chunk, 0xFFFFFFFF);
        writeU32(chunk, 0xFFFFFFFF);
        writeU32(chunk, intern(pool, element.name));
        return chunk.toByteArray();
    }

    // -----------------------------------------------------------------------
    // APK Signing Block injection
    // -----------------------------------------------------------------------

    /** The v2 scheme's pair id, as the block carries it. */
    public static final long V2_PAIR_ID = 0x7109871aL;

    /** The v3 scheme's pair id, as the block carries it. */
    public static final long V3_PAIR_ID = 0xf05368c0L;

    private static final byte[] SIGNING_MAGIC = "APK Sig Block 42".getBytes(StandardCharsets.US_ASCII);

    /**
     * Splices an APK Signing Block carrying the requested scheme pairs
     * in front of the archive's central directory, then re-patches the
     * directory's local-header offsets and the EOCD's directory
     * pointer — the same byte surgery a real signer performs. Pair
     * VALUES are opaque marker bytes: M1 stores presence only.
     */
    public static byte[] withSigningBlock(byte[] apk, boolean v2, boolean v3) throws IOException {
        ByteArrayOutputStream pairs = new ByteArrayOutputStream();
        if (v2) {
            writePair(pairs, V2_PAIR_ID);
        }
        if (v3) {
            writePair(pairs, V3_PAIR_ID);
        }
        // The local entries did not move — the block sits between the
        // last entry's data and the directory — so only the EOCD's
        // directory pointer shifts. That is exactly the surgery a real
        // signer performs.
        return spliceSigningBlock(apk, pairs.toByteArray());
    }

    private static void writePair(ByteArrayOutputStream pairs, long id) throws IOException {
        byte[] value = noise(24);
        writeU64(pairs, 4 + value.length);
        writeU32(pairs, (int) id);
        pairs.write(value, 0, value.length);
    }

    /**
     * The hostile-walk leg: a block of valueless junk pairs (length 4,
     * id only), far more than the parser's pair ceiling — the probe
     * must terminate and report nothing detected, never loop.
     */
    public static byte[] withHostileSigningPairs(byte[] apk, int pairs) throws IOException {
        ByteArrayOutputStream pairBytes = new ByteArrayOutputStream();
        for (int i = 0; i < pairs; i++) {
            writeU64(pairBytes, 4);
            writeU32(pairBytes, 0x11223344);
        }
        return spliceSigningBlock(apk, pairBytes.toByteArray());
    }

    /** Patches a signed APK's footer size to an impossible 23 bytes. */
    public static byte[] withUndersizedSigningFooter(byte[] apk) {
        int directoryAt = centralDirectoryOffset(apk);
        for (int i = 0; i < 8; i++) {
            apk[directoryAt - 24 + i] = (byte) ((23L >> (8 * i)) & 0xFF);
        }
        return apk;
    }

    /** The splice shared by every signing-block leg. */
    private static byte[] spliceSigningBlock(byte[] apk, byte[] pairBytes) throws IOException {
        long sizeValue = pairBytes.length + 8 + SIGNING_MAGIC.length;
        ByteArrayOutputStream block = new ByteArrayOutputStream();
        writeU64(block, sizeValue);
        block.write(pairBytes, 0, pairBytes.length);
        writeU64(block, sizeValue);
        block.write(SIGNING_MAGIC, 0, SIGNING_MAGIC.length);
        byte[] blockBytes = block.toByteArray();
        int directoryAt = centralDirectoryOffset(apk);
        byte[] spliced = new byte[apk.length + blockBytes.length];
        System.arraycopy(apk, 0, spliced, 0, directoryAt);
        System.arraycopy(blockBytes, 0, spliced, directoryAt, blockBytes.length);
        System.arraycopy(apk, directoryAt, spliced, directoryAt + blockBytes.length, apk.length - directoryAt);
        int eocd = spliced.length - 22;
        writeU32At(spliced, eocd + 16, (directoryAt + blockBytes.length));
        return spliced;
    }

    // -----------------------------------------------------------------------
    // Damage and adversarial legs
    // -----------------------------------------------------------------------

    /** The non-zip leg: bytes that are not an archive at all, renamed .apk. */
    public static byte[] notAZip(int size) {
        return noise(size);
    }

    /** The renamed-zip leg: a valid ZIP with no Android manifest layout. */
    public static byte[] renamedZipApk() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("classes.dex"));
            zip.write(noise(32));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("readme.txt"));
            zip.write("not an android artifact".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    /** The damaged-manifest leg: random bytes in the manifest entry. */
    public static byte[] garbageManifestApk() throws IOException {
        return apk(noise(512));
    }

    /** The zero-byte manifest leg. */
    public static byte[] emptyManifestApk() throws IOException {
        return apk(new byte[0]);
    }

    /** The no-package leg: a structurally valid manifest without package. */
    public static byte[] identifierlessManifestApk() throws IOException {
        return apk(manifestAxml(null, VERSION_NAME, VERSION_CODE, MIN_SDK, TARGET_SDK, LABEL, false, false));
    }

    /** The hostile-width leg: a package attribute wider than bundle_id. */
    public static byte[] oversizedPackageApk() throws IOException {
        return apk(manifestAxml("a".repeat(300), VERSION_NAME, VERSION_CODE, MIN_SDK, TARGET_SDK, LABEL, false, false));
    }

    /** The aapt2-string leg: the SDK levels rendered as STRING values. */
    public static byte[] stringValuesApk() throws IOException {
        return apk(manifestAxml(PACKAGE, VERSION_NAME, VERSION_CODE, MIN_SDK, TARGET_SDK, LABEL, false, true));
    }

    /** The unicode label leg: the pool must carry non-ASCII cleanly. */
    public static byte[] unicodeLabelApk() throws IOException {
        return apk(manifestAxml(PACKAGE, VERSION_NAME, VERSION_CODE, MIN_SDK, TARGET_SDK, "银行银行银行", false, false));
    }

    /** The resource-reference label leg: @string labels read as absent. */
    public static byte[] resourceLabelApk() throws IOException {
        return apk(manifestAxml(
                PACKAGE, VERSION_NAME, VERSION_CODE, MIN_SDK, TARGET_SDK, "@string/app_name", false, false));
    }

    /** The UTF-8 pool leg: the same manifest with UTF-8 string data. */
    public static byte[] utf8PoolApk() throws IOException {
        return apk(manifestAxml(true));
    }

    /** The missing-SDK leg: no uses-sdk element at all. */
    public static byte[] noSdkApk() throws IOException {
        return apk(manifestAxml(PACKAGE, VERSION_NAME, VERSION_CODE, null, null, LABEL, false, false));
    }

    /** The encrypted-manifest leg: the directory declares the encryption flag. */
    public static byte[] encryptedManifestApk() throws IOException {
        byte[] apk = releaseApk();
        int directory = centralDirectoryOffset(apk);
        // First directory record is the manifest; flags sit at +8, bit 0.
        apk[directory + 8] |= 0x1;
        return apk;
    }

    /** The bomb leg: the directory declares a huge manifest extent. */
    public static byte[] bombManifestApk() throws IOException {
        byte[] apk = releaseApk();
        int directory = centralDirectoryOffset(apk);
        writeU32At(apk, directory + 20, 2_000_000); // compressed size
        writeU32At(apk, directory + 24, 2_000_000); // uncompressed size
        return apk;
    }

    /**
     * The truncated leg: the archive cut before the central directory,
     * which takes the EOCD with it — the graceful answer is NOT_A_ZIP,
     * never an unhandled read.
     */
    public static byte[] truncatedApk() throws IOException {
        byte[] apk = releaseApk();
        return java.util.Arrays.copyOf(apk, centralDirectoryOffset(apk) - 1);
    }

    // -----------------------------------------------------------------------
    // Byte helpers
    // -----------------------------------------------------------------------

    private static byte[] noise(int size) {
        byte[] bytes = new byte[size];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    /** The central directory's offset, read from the trailing EOCD. */
    static int centralDirectoryOffset(byte[] archive) {
        for (int i = archive.length - 22; i >= 0; i--) {
            if (archive[i] == 'P' && archive[i + 1] == 'K' && archive[i + 2] == 5 && archive[i + 3] == 6) {
                return (int) u32(archive, i + 16);
            }
        }
        throw new IllegalStateException("fixture has no EOCD");
    }

    private static void writeVarint(ByteArrayOutputStream bytes, long value) {
        while ((value & ~0x7FL) != 0) {
            bytes.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        bytes.write((int) value);
    }

    private static void writeU16(ByteArrayOutputStream bytes, int value) {
        bytes.write(value & 0xFF);
        bytes.write((value >> 8) & 0xFF);
    }

    private static void writeU32(ByteArrayOutputStream bytes, long value) {
        for (int i = 0; i < 4; i++) {
            bytes.write((int) ((value >> (8 * i)) & 0xFF));
        }
    }

    private static void writeU64(ByteArrayOutputStream bytes, long value) {
        for (int i = 0; i < 8; i++) {
            bytes.write((int) ((value >> (8 * i)) & 0xFF));
        }
    }

    private static void writeU32At(byte[] bytes, int at, long value) {
        for (int i = 0; i < 4; i++) {
            bytes[at + i] = (byte) ((value >> (8 * i)) & 0xFF);
        }
    }

    private static int u16(byte[] bytes, int at) {
        return (bytes[at] & 0xFF) | ((bytes[at + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] bytes, int at) {
        return (bytes[at] & 0xFFL)
                | ((bytes[at + 1] & 0xFFL) << 8)
                | ((bytes[at + 2] & 0xFFL) << 16)
                | ((bytes[at + 3] & 0xFFL) << 24);
    }
}
