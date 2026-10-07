// Programmatic APK fixture for the portal e2e golden path (issue #38,
// S8-04): a real ZIP whose AndroidManifest.xml is real binary AXML and
// whose APK Signing Block carries the v2 scheme pair — the shape the
// backend validation pipeline requires to settle an upload READY
// (FORMAT, METADATA, SIGNING stages all read structural facts, never
// the file name's claim). This mirrors the backend suite's
// AndroidFixtures writer (an independent implementation of the same
// format, so no binary is ever committed); it exists because the e2e
// runs in plain Node against the compose stack, where the golden path
// must download a REAL artifact through the browser.
//
// Format reference: the AXML chunk grammar and the APK Signing Block
// v2 framing, both as the backend parser (and aapt) read them.

import { crc32 } from "node:zlib";

const ANDROID_NS = "http://schemas.android.com/apk/res/android";

/**
 * Mints a signed APK whose manifest carries the given identity.
 * @returns {Uint8Array} the APK bytes
 */
export function signedApk({ packageName, versionName, versionCode }) {
  const manifest = manifestAxml({ packageName, versionName, versionCode });
  const zip = zipStored([
    { name: "AndroidManifest.xml", data: manifest },
    { name: "classes.dex", data: noise(64) },
    { name: "META-INF/MANIFEST.MF", data: ascii("Manifest-Version: 1.0\r\n") },
  ]);
  return withSigningBlock(zip, true, false);
}

// ---------------------------------------------------------------------------
// AXML assembly (mirrors the backend fixture writer chunk for chunk)
// ---------------------------------------------------------------------------

function manifestAxml({ packageName, versionName, versionCode }) {
  const manifest = element("manifest", [
    attrString("package", packageName, false),
    attrInt("versionCode", versionCode),
    attrString("versionName", versionName, true),
  ]);
  const usesSdk = element("uses-sdk", [attrInt("minSdkVersion", 26), attrInt("targetSdkVersion", 35)]);
  return axml([manifest, usesSdk]);
}

function element(name, attributes) {
  return { name, attributes };
}

function attrString(name, value, androidNamespace) {
  return { name, value, intValue: null, androidNamespace };
}

function attrInt(name, value) {
  return { name, value: null, intValue: value, androidNamespace: true };
}

function axml(elements) {
  const pool = new Map();
  const intern = (value) => {
    if (!pool.has(value)) {
      pool.set(value, pool.size);
    }
    return pool.get(value);
  };
  const androidNs = intern(ANDROID_NS);
  const prefix = intern("android");
  const chunks = [];
  chunks.push(namespaceChunk(true, prefix, androidNs));
  for (const item of elements) {
    chunks.push(startElementChunk(item, androidNs, intern));
    chunks.push(endElementChunk(item, intern));
  }
  chunks.push(namespaceChunk(false, prefix, androidNs));
  const stringPool = stringPoolChunk(pool);
  let bodySize = stringPool.length;
  for (const chunk of chunks) {
    bodySize += chunk.length;
  }
  const document = new ByteWriter();
  document.u16(0x0003); // RES_XML_TYPE
  document.u16(8);
  document.u32(8 + bodySize);
  document.bytes(stringPool);
  for (const chunk of chunks) {
    document.bytes(chunk);
  }
  return document.done();
}

function stringPoolChunk(pool) {
  const encoded = [...pool.keys()].map(utf16PoolString);
  let dataBytes = 0;
  for (const entry of encoded) {
    dataBytes += entry.length;
  }
  const offsetsAt = 28;
  const dataAt = offsetsAt + 4 * encoded.length;
  const chunk = new ByteWriter();
  chunk.u16(0x0001); // RES_STRING_POOL_TYPE
  chunk.u16(offsetsAt);
  chunk.u32(dataAt + dataBytes);
  chunk.u32(encoded.length);
  chunk.u32(0); // style count
  chunk.u32(0); // UTF8_FLAG off: classic UTF-16 pool
  chunk.u32(dataAt); // strings start, relative to chunk start
  chunk.u32(0); // styles start
  let offset = 0;
  for (const entry of encoded) {
    chunk.u32(offset);
    offset += entry.length;
  }
  for (const entry of encoded) {
    chunk.bytes(entry);
  }
  return chunk.done();
}

function utf16PoolString(value) {
  const writer = new ByteWriter();
  writer.u16(value.length);
  for (let i = 0; i < value.length; i += 1) {
    writer.u16(value.charCodeAt(i));
  }
  writer.u16(0); // terminator
  return writer.done();
}

function namespaceChunk(start, prefix, uri) {
  const chunk = new ByteWriter();
  chunk.u16(start ? 0x0100 : 0x0101);
  chunk.u16(16);
  chunk.u32(24);
  chunk.u32(1); // line number
  chunk.u32(0xffffffff); // no comment
  chunk.u32(prefix);
  chunk.u32(uri);
  return chunk.done();
}

function startElementChunk(item, androidNs, intern) {
  const name = intern(item.name);
  for (const attribute of item.attributes) {
    intern(attribute.name);
    if (attribute.value !== null) {
      intern(attribute.value);
    }
  }
  const count = item.attributes.length;
  const chunk = new ByteWriter();
  chunk.u16(0x0102); // RES_XML_START_ELEMENT_TYPE
  chunk.u16(16);
  chunk.u32(36 + 20 * count);
  chunk.u32(1); // line number
  chunk.u32(0xffffffff); // no comment
  chunk.u32(0xffffffff); // element carries no namespace
  chunk.u32(name);
  chunk.u16(20); // attributeStart
  chunk.u16(20); // attributeSize
  chunk.u16(count);
  chunk.u16(0); // idIndex
  chunk.u16(0); // classIndex
  chunk.u16(0); // styleIndex
  for (const attribute of item.attributes) {
    chunk.u32(attribute.androidNamespace ? androidNs : 0xffffffff);
    chunk.u32(intern(attribute.name));
    if (attribute.value !== null) {
      const index = intern(attribute.value);
      chunk.u32(index);
      chunk.u16(8); // typed value size
      chunk.u8(0); // res0
      chunk.u8(0x03); // TYPE_STRING
      chunk.u32(index);
    } else {
      chunk.u32(0xffffffff); // no raw literal for ints
      chunk.u16(8);
      chunk.u8(0); // res0
      chunk.u8(0x10); // TYPE_INT_DEC
      chunk.u32(attribute.intValue);
    }
  }
  return chunk.done();
}

function endElementChunk(item, intern) {
  const chunk = new ByteWriter();
  chunk.u16(0x0103);
  chunk.u16(16);
  chunk.u32(24);
  chunk.u32(1);
  chunk.u32(0xffffffff);
  chunk.u32(0xffffffff);
  chunk.u32(intern(item.name));
  return chunk.done();
}

// ---------------------------------------------------------------------------
// ZIP assembly (stored entries; the parser reads both compressions)
// ---------------------------------------------------------------------------

function zipStored(entries) {
  const local = [];
  const central = [];
  let offset = 0;
  for (const entry of entries) {
    const nameBytes = ascii(entry.name);
    const crc = crc32(entry.data) >> 0;
    const localHeader = new ByteWriter();
    localHeader.u32(0x04034b50);
    localHeader.u16(20); // version needed
    localHeader.u16(0); // flags
    localHeader.u16(0); // STORED
    localHeader.u16(0); // time
    localHeader.u16(0x2100); // date (deterministic)
    localHeader.u32(crc);
    localHeader.u32(entry.data.length);
    localHeader.u32(entry.data.length);
    localHeader.u16(nameBytes.length);
    localHeader.u16(0); // extra length
    localHeader.bytes(nameBytes);
    const headerBytes = localHeader.done();
    local.push(headerBytes, entry.data);
    const centralEntry = new ByteWriter();
    centralEntry.u32(0x02014b50);
    centralEntry.u16(20); // version made by
    centralEntry.u16(20); // version needed
    centralEntry.u16(0);
    centralEntry.u16(0); // STORED
    centralEntry.u16(0);
    centralEntry.u16(0x2100);
    centralEntry.u32(crc);
    centralEntry.u32(entry.data.length);
    centralEntry.u32(entry.data.length);
    centralEntry.u16(nameBytes.length);
    centralEntry.u16(0);
    centralEntry.u16(0);
    centralEntry.u16(0);
    centralEntry.u16(0);
    centralEntry.u32(0);
    centralEntry.u32(offset);
    centralEntry.bytes(nameBytes);
    central.push(centralEntry.done());
    offset += headerBytes.length + entry.data.length;
  }
  const directoryAt = offset;
  let directorySize = 0;
  for (const entry of central) {
    directorySize += entry.length;
  }
  const eocd = new ByteWriter();
  eocd.u32(0x06054b50);
  eocd.u16(0);
  eocd.u16(0);
  eocd.u16(entries.length);
  eocd.u16(entries.length);
  eocd.u32(directorySize);
  eocd.u32(directoryAt);
  eocd.u16(0);
  return concat([...local, ...central, eocd.done()]);
}

// ---------------------------------------------------------------------------
// APK Signing Block splice (v2 pair present; values are marker bytes —
// M1 stores presence only)
// ---------------------------------------------------------------------------

const V2_PAIR_ID = 0x7109871a;

function withSigningBlock(apk, v2, v3) {
  const pairs = new ByteWriter();
  const writePair = (id) => {
    const value = noise(24);
    pairs.u64(4 + value.length);
    pairs.u32(id);
    pairs.bytes(value);
  };
  if (v2) {
    writePair(V2_PAIR_ID);
  }
  if (v3) {
    writePair(0xf05368c0);
  }
  const pairBytes = pairs.done();
  const magic = ascii("APK Sig Block 42");
  const sizeValue = BigInt(pairBytes.length + 8 + magic.length);
  const block = new ByteWriter();
  block.u64(sizeValue);
  block.bytes(pairBytes);
  block.u64(sizeValue);
  block.bytes(magic);
  const blockBytes = block.done();

  const directoryAt = centralDirectoryOffset(apk);
  const spliced = new Uint8Array(apk.length + blockBytes.length);
  spliced.set(apk.subarray(0, directoryAt), 0);
  spliced.set(blockBytes, directoryAt);
  spliced.set(apk.subarray(directoryAt), directoryAt + blockBytes.length);
  // The local entries did not move: only the EOCD's directory pointer
  // shifts, exactly the surgery a real signer performs.
  const eocd = spliced.length - 22;
  const view = new DataView(spliced.buffer);
  view.setUint32(eocd + 16, directoryAt + blockBytes.length, true);
  return spliced;
}

function centralDirectoryOffset(apk) {
  const view = new DataView(apk.buffer, apk.byteOffset, apk.byteLength);
  const eocd = apk.length - 22;
  return view.getUint32(eocd + 16, true);
}

// ---------------------------------------------------------------------------
// Small utilities
// ---------------------------------------------------------------------------

class ByteWriter {
  constructor() {
    this.chunks = [];
  }

  u8(value) {
    this.chunks.push(new Uint8Array([value & 0xff]));
    return this;
  }

  u16(value) {
    this.chunks.push(new Uint8Array([value & 0xff, (value >>> 8) & 0xff]));
    return this;
  }

  u32(value) {
    this.chunks.push(
      new Uint8Array([
        value & 0xff,
        (value >>> 8) & 0xff,
        (value >>> 16) & 0xff,
        (value >>> 24) & 0xff,
      ]),
    );
    return this;
  }

  u64(value) {
    let rest = BigInt(value);
    for (let i = 0n; i < 8n; i += 1n) {
      this.u8(Number(rest & 0xffn));
      rest >>= 8n;
    }
    return this;
  }

  bytes(data) {
    this.chunks.push(data);
    return this;
  }

  done() {
    return concat(this.chunks);
  }
}

function concat(parts) {
  let total = 0;
  for (const part of parts) {
    total += part.length;
  }
  const out = new Uint8Array(total);
  let offset = 0;
  for (const part of parts) {
    out.set(part, offset);
    offset += part.length;
  }
  return out;
}

function ascii(value) {
  return new Uint8Array([...value].map((char) => char.charCodeAt(0) & 0xff));
}

function noise(length) {
  const out = new Uint8Array(length);
  for (let i = 0; i < length; i += 1) {
    out[i] = Math.floor(Math.random() * 256);
  }
  return out;
}

// Node >= 22.2 exposes crc32 (the portal pins >= 22.12); the signed
// (>>) keeps the value in u32 range for the ZIP fields that follow.
