// Tests for regen-versions.mjs (WP2 versioning). The script is a CLI whose
// whole surface is its exit code plus the versions.json it leaves behind,
// so each case runs it as a subprocess against a throwaway directory —
// the same way .github/workflows/docs-site.yml calls it.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const script = join(dirname(fileURLToPath(import.meta.url)), 'regen-versions.mjs');

function run(...args) {
  const res = spawnSync(process.execPath, [script, ...args], { encoding: 'utf8' });
  return { status: res.status, stderr: res.stderr, stdout: res.stdout };
}

function freshDir() {
  return mkdtempSync(join(tmpdir(), 'regen-versions-'));
}

function readManifest(dir) {
  return JSON.parse(readFileSync(join(dir, 'versions.json'), 'utf8'));
}

test('exits 2 with usage message when arguments are missing', () => {
  const res = run();
  assert.equal(res.status, 2);
  assert.match(res.stderr, /usage:/);
});

test('exits 2 on an invalid version id', () => {
  for (const bad of ['latest', 'v1', 'vX.Y', 'docs-test', '-docs-test']) {
    assert.equal(run(freshDir(), bad).status, 2, `expected rejection for ${bad}`);
  }
});

test('seeds a snapshot-only manifest when nothing exists yet', () => {
  const dir = freshDir();
  const res = run(dir, 'snapshot');
  assert.equal(res.status, 0);
  assert.deepEqual(readManifest(dir), {
    latest: null,
    versions: [{ id: 'snapshot', label: 'SNAPSHOT (main)' }],
  });
});

test('seeds snapshot + release and sets latest when the first release deploys', () => {
  const dir = freshDir();
  assert.equal(run(dir, 'v0.1').status, 0);
  const manifest = readManifest(dir);
  assert.equal(manifest.latest, 'v0.1');
  assert.deepEqual(
    manifest.versions.map((v) => v.id),
    ['snapshot', 'v0.1'],
  );
});

test('preserves other versions verbatim and rewrites only the current one', () => {
  const dir = freshDir();
  writeFileSync(
    join(dir, 'versions.json'),
    JSON.stringify({
      latest: 'v0.1',
      versions: [
        { id: 'snapshot', label: 'stale label' },
        { id: 'v0.1', label: 'custom release label' },
      ],
    }),
  );
  assert.equal(run(dir, 'snapshot').status, 0);
  const manifest = readManifest(dir);
  assert.deepEqual(manifest.versions, [
    { id: 'snapshot', label: 'SNAPSHOT (main)' },
    { id: 'v0.1', label: 'custom release label' },
  ]);
  assert.equal(manifest.latest, 'v0.1');
});

test('sorts releases numerically newest-first and picks the numeric highest as latest', () => {
  const dir = freshDir();
  writeFileSync(
    join(dir, 'versions.json'),
    JSON.stringify({
      latest: 'v0.9',
      versions: [
        { id: 'snapshot', label: 'SNAPSHOT (main)' },
        { id: 'v0.2', label: 'v0.2' },
        { id: 'v0.9', label: 'v0.9' },
        { id: 'v0.10', label: 'v0.10' },
      ],
    }),
  );
  assert.equal(run(dir, 'snapshot').status, 0);
  const manifest = readManifest(dir);
  assert.deepEqual(
    manifest.versions.map((v) => v.id),
    ['snapshot', 'v0.10', 'v0.9', 'v0.2'],
  );
  assert.equal(manifest.latest, 'v0.10');
});

test('drops non-canonical release ids (dotless segments) instead of listing them', () => {
  const dir = freshDir();
  writeFileSync(
    join(dir, 'versions.json'),
    JSON.stringify({
      latest: null,
      versions: [
        { id: 'snapshot', label: 'SNAPSHOT (main)' },
        { id: 'v9', label: 'v9' },
        { id: 'v0.1', label: 'v0.1' },
      ],
    }),
  );
  assert.equal(run(dir, 'snapshot').status, 0);
  const manifest = readManifest(dir);
  assert.deepEqual(
    manifest.versions.map((v) => v.id),
    ['snapshot', 'v0.1'],
  );
  assert.equal(manifest.latest, 'v0.1');
});

test('strips a leftover -docs-test entry unless it is the one being deployed', () => {
  const dir = freshDir();
  writeFileSync(
    join(dir, 'versions.json'),
    JSON.stringify({
      latest: null,
      versions: [
        { id: 'snapshot', label: 'SNAPSHOT (main)' },
        { id: 'v0.0-docs-test', label: 'v0.0-docs-test' },
      ],
    }),
  );
  assert.equal(run(dir, 'snapshot').status, 0);
  assert.deepEqual(
    readManifest(dir).versions.map((v) => v.id),
    ['snapshot'],
  );
});

test('a -docs-test deploy is listed last and never becomes latest', () => {
  const dir = freshDir();
  assert.equal(run(dir, 'v0.1').status, 0);
  assert.equal(run(dir, 'v0.0-docs-test').status, 0);
  const manifest = readManifest(dir);
  assert.deepEqual(
    manifest.versions.map((v) => v.id),
    ['snapshot', 'v0.1', 'v0.0-docs-test'],
  );
  assert.equal(manifest.latest, 'v0.1');
});

test('refuses to clobber an unparseable manifest and exits 1, leaving it untouched', () => {
  const dir = freshDir();
  const garbage = '{not json';
  writeFileSync(join(dir, 'versions.json'), garbage);
  const res = run(dir, 'snapshot');
  assert.equal(res.status, 1);
  assert.match(res.stderr, /unreadable, refusing to clobber/);
  assert.equal(readFileSync(join(dir, 'versions.json'), 'utf8'), garbage);
});

test('refuses a manifest whose versions is not an array', () => {
  const dir = freshDir();
  writeFileSync(join(dir, 'versions.json'), JSON.stringify({ latest: null, versions: 'nope' }));
  assert.equal(run(dir, 'snapshot').status, 1);
});

test('drops malformed entries but keeps well-formed siblings when regenerating', () => {
  const dir = freshDir();
  writeFileSync(
    join(dir, 'versions.json'),
    JSON.stringify({
      latest: null,
      versions: [{ id: 'snapshot' }, { nope: true }, { id: 'v0.1', label: 'v0.1' }],
    }),
  );
  assert.equal(run(dir, 'snapshot').status, 0);
  assert.deepEqual(
    readManifest(dir).versions.map((v) => v.id),
    ['snapshot', 'v0.1'],
  );
  assert.equal(readManifest(dir).latest, 'v0.1');
});

test('creates the manifest inside the given deploy root even when nested', () => {
  const dir = join(freshDir(), 'gh-pages-deploy');
  mkdirSync(dir);
  assert.equal(run(dir, 'snapshot').status, 0);
  assert.equal(readManifest(dir).latest, null);
});
