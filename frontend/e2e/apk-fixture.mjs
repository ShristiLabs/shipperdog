// Programmatic Android APK fixture for the console e2e suite (issue
// #59): a JS port of the backend's own AndroidFixtures test builder
// (backend/src/test/.../artifact/AndroidFixtures.java) — real ZIP
// archives whose manifests are real Android binary XML assembled chunk
// by chunk, plus the APK Signing Block byte surgery — so no binary is
// ever committed and the e2e can mint a genuinely READY artifact
// through the real upload+validation pipeline (the S9-05 release
// fixture needs one: release creation requires READY artifacts, and
// the validation stages demand a structurally real container whose
// manifest agrees with the application's package and the version's
// name). Writer and the server's parser share no code — that is the
// point of the fixture.
import { randomBytes } from "node:crypto";
import { crc32 } from "node:zlib";

const ANDROID_NS = "http://schemas.android.com/apk/res/android";
const ANDROID_PREFIX = "android";
const V2_PAIR_ID = 0x7109871a;
const SIGNING_MAGIC = Buffer.from("APK Sig Block 42", "ascii");

class ByteWriter {
  constructor() {
    this.chunks = [];
  }
  u16(value) {
    const b = Buffer.alloc(2);
    b.writeUInt16LE(value & 0xffff, 0);
    this.chunks.push(b);
    return this;
  }
  u32(value) {
    const b = Buffer.alloc(4);
    b.writeUInt32LE(Number(BigInt(value) & 0xffffffffn), 0);
    this.chunks.push(b);
    return this;
  }
  u64(value) {
    const b = Buffer.alloc(8);
    b.writeBigUInt64LE(BigInt(value) & 0xffffffffffffffffn, 0);
    this.chunks.push(b);
    return this;
  }
  bytes(data) {
    this.chunks.push(Buffer.from(data));
    return this;
  }
  buffer() {
    return Buffer.concat(this.chunks);
  }
}

function u32At(buffer, at, value) {
  buffer.writeUInt32LE(Number(BigInt(value) & 0xffffffffn), at);
}

/** The full manifest: package, version, label, and both SDK levels —
 * the port of AndroidFixtures.manifestAxml (UTF-16 pool, the classic
 * aapt encoding). */
export function manifestAxml({ pkg, versionName, versionCode, minSdk, targetSdk, label }) {
  const pool = new Map();
  const intern = (value) => {
    if (!pool.has(value)) pool.set(value, pool.size);
    return pool.get(value);
  };
  const androidNs = intern(ANDROID_NS);
  const prefix = intern(ANDROID_PREFIX);

  const attributes = (specs) =>
    specs
      .filter((spec) => spec !== null)
      .map((spec) => ({ name: spec[0], value: spec[1] ?? null, intValue: spec[2] ?? null, ns: spec[3] }));
  const manifestAttrs = attributes([
    pkg === null ? null : ["package", pkg, null, false],
    versionCode === null ? null : ["versionCode", null, versionCode, true],
    ["versionName", versionName, null, true],
    ["label", label, null, true],
  ]);
  const sdkAttrs = attributes([
    minSdk === null ? null : ["minSdkVersion", null, minSdk, true],
    targetSdk === null ? null : ["targetSdkVersion", null, targetSdk, true],
  ]);
  const elements = [
    { name: "manifest", attributes: manifestAttrs },
    { name: "uses-sdk", attributes: sdkAttrs },
  ];

  const chunks = [];
  const namespaceChunk = (start) => {
    const w = new ByteWriter();
    w.u16(start ? 0x0100 : 0x0101).u16(16).u32(24).u32(1).u32(0xffffffff).u32(prefix).u32(androidNs);
    return w.buffer();
  };
  const startElementChunk = (element) => {
    const name = intern(element.name);
    for (const attribute of element.attributes) {
      intern(attribute.name);
      if (attribute.value !== null) intern(attribute.value);
    }
    const count = element.attributes.length;
    const w = new ByteWriter();
    w.u16(0x0102)
      .u16(16)
      .u32(36 + 20 * count)
      .u32(1)
      .u32(0xffffffff)
      .u32(0xffffffff)
      .u32(name)
      .u16(20)
      .u16(20)
      .u16(count)
      .u16(0)
      .u16(0)
      .u16(0);
    for (const attribute of element.attributes) {
      w.u32(attribute.ns ? androidNs : 0xffffffff).u32(intern(attribute.name));
      if (attribute.value !== null) {
        w.u32(intern(attribute.value)).u16(8).bytes([0]).bytes([0x03]).u32(intern(attribute.value));
      } else {
        w.u32(0xffffffff).u16(8).bytes([0]).bytes([0x10]).u32(attribute.intValue);
      }
    }
    return w.buffer();
  };
  const endElementChunk = (element) => {
    const w = new ByteWriter();
    w.u16(0x0103).u16(16).u32(24).u32(1).u32(0xffffffff).u32(0xffffffff).u32(intern(element.name));
    return w.buffer();
  };

  chunks.push(namespaceChunk(true));
  for (const element of elements) {
    chunks.push(startElementChunk(element));
    chunks.push(endElementChunk(element));
  }
  chunks.push(namespaceChunk(false));

  // The string pool, assembled once every chunk has interned its strings.
  const values = [...pool.keys()];
  const encoded = values.map((value) => {
    const w = new ByteWriter();
    w.u16(value.length);
    for (let i = 0; i < value.length; i++) w.u16(value.charCodeAt(i));
    w.u16(0);
    return w.buffer();
  });
  const dataBytes = encoded.reduce((sum, b) => sum + b.length, 0);
  const offsetsAt = 28;
  const dataAt = offsetsAt + 4 * encoded.length;
  const poolWriter = new ByteWriter();
  poolWriter
    .u16(0x0001)
    .u16(offsetsAt)
    .u32(dataAt + dataBytes)
    .u32(encoded.length)
    .u32(0)
    .u32(0)
    .u32(dataAt)
    .u32(0);
  let offset = 0;
  for (const b of encoded) {
    poolWriter.u32(offset);
    offset += b.length;
  }
  for (const b of encoded) poolWriter.bytes(b);

  const poolBytes = poolWriter.buffer();
  const chunksBytes = chunks.reduce((sum, c) => sum + c.length, 0);
  const document = new ByteWriter();
  document.u16(0x0003).u16(8).u32(8 + poolBytes.length + chunksBytes);
  document.bytes(poolBytes);
  for (const chunk of chunks) document.bytes(chunk);
  return document.buffer();
}

/** A minimal stored-entry ZIP: no compression, so the server's walk
 * sees exactly the bytes written. */
export function zip(entries) {
  const local = [];
  const central = [];
  let offset = 0;
  for (const [name, data] of entries) {
    const nameBytes = Buffer.from(name, "utf8");
    const checksum = crc32(data) >>> 0;
    const localHeader = Buffer.alloc(30);
    localHeader.writeUInt32LE(0x04034b50, 0);
    localHeader.writeUInt16LE(20, 4);
    localHeader.writeUInt16LE(0, 6);
    localHeader.writeUInt16LE(0, 8); // method 0: stored
    localHeader.writeUInt16LE(0, 10);
    localHeader.writeUInt16LE(0, 12);
    localHeader.writeUInt32LE(checksum, 14);
    localHeader.writeUInt32LE(data.length, 18);
    localHeader.writeUInt32LE(data.length, 22);
    localHeader.writeUInt16LE(nameBytes.length, 26);
    localHeader.writeUInt16LE(0, 28);
    local.push(localHeader, nameBytes, data);

    const centralHeader = Buffer.alloc(46);
    centralHeader.writeUInt32LE(0x02014b50, 0);
    centralHeader.writeUInt16LE(20, 4);
    centralHeader.writeUInt16LE(20, 6);
    centralHeader.writeUInt16LE(0, 8);
    centralHeader.writeUInt16LE(0, 10);
    centralHeader.writeUInt16LE(0, 12);
    centralHeader.writeUInt16LE(0, 14);
    centralHeader.writeUInt32LE(checksum, 16);
    centralHeader.writeUInt32LE(data.length, 20);
    centralHeader.writeUInt32LE(data.length, 24);
    centralHeader.writeUInt16LE(nameBytes.length, 28);
    centralHeader.writeUInt16LE(0, 30);
    centralHeader.writeUInt16LE(0, 32);
    centralHeader.writeUInt16LE(0, 34);
    centralHeader.writeUInt16LE(0, 36);
    centralHeader.writeUInt32LE(0, 38);
    centralHeader.writeUInt32LE(offset, 42);
    central.push(centralHeader, nameBytes);

    offset += 30 + nameBytes.length + data.length;
  }
  const centralBytes = Buffer.concat(central);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(entries.length, 8);
  eocd.writeUInt16LE(entries.length, 10);
  eocd.writeUInt32LE(centralBytes.length, 12);
  eocd.writeUInt32LE(offset, 16);
  return Buffer.concat([...local, centralBytes, eocd]);
}

/** The release fixture: the manifest + dex + META-INF shape with the
 * APK Signing Scheme v2 block spliced in front of the central
 * directory (the port of AndroidFixtures.withSigningBlock). */
export function signedApk({ pkg, versionName, versionCode }) {
  const apk = zip([
    ["AndroidManifest.xml", manifestAxml({ pkg, versionName, versionCode, minSdk: 26, targetSdk: 35, label: "Console Fixture" })],
    ["classes.dex", randomBytes(64)],
    ["META-INF/MANIFEST.MF", Buffer.from("Manifest-Version: 1.0\r\n", "ascii")],
  ]);
  const pairs = new ByteWriter();
  const value = randomBytes(24);
  pairs.u64(4 + value.length).u32(V2_PAIR_ID).bytes(value);
  const pairBytes = pairs.buffer();
  const sizeValue = pairBytes.length + 8 + SIGNING_MAGIC.length;
  const block = new ByteWriter();
  block.u64(sizeValue).bytes(pairBytes).u64(sizeValue).bytes(SIGNING_MAGIC);
  const blockBytes = block.buffer();

  // The central directory's offset, read from the trailing EOCD.
  let eocdAt = -1;
  for (let i = apk.length - 22; i >= 0; i--) {
    if (apk[i] === 0x50 && apk[i + 1] === 0x4b && apk[i + 2] === 0x05 && apk[i + 3] === 0x06) {
      eocdAt = i;
      break;
    }
  }
  if (eocdAt < 0) throw new Error("fixture zip has no EOCD");
  const directoryAt = apk.readUInt32LE(eocdAt + 16);
  const spliced = Buffer.concat([
    apk.subarray(0, directoryAt),
    blockBytes,
    apk.subarray(directoryAt),
  ]);
  u32At(spliced, spliced.length - 22 + 16, directoryAt + blockBytes.length);
  return spliced;
}
