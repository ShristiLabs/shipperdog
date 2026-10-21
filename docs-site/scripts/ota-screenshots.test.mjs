// Content-contract tests for the OTA console screenshots (issue #199,
// WP12). The delivery is six PNGs plus their markdown embeds on the two
// OTA how-to pages — no code — so the testable contracts are content
// invariants, asserted against the working tree and the git HEAD of the
// pages: every referenced image resolves, the issue's mandatory images
// sit on their assigned pages with alt text, the PNGs carry the
// console-screenshot dimension convention, and the pages changed by
// embeds only. The helpers take a docs-site root so the failure paths
// run against throwaway fixtures, never the tracked files.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, mkdirSync, mkdtempSync, writeFileSync, rmSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const docsRoot = join(here, '..');
const repoRoot = join(docsRoot, '..');

const PAGES = {
  'how-to/console/ota-bundles.md': ['console-ota-bundles.png'],
  'how-to/console/ota-rollouts.md': ['console-ota-rollout.png', 'console-ota-dashboard.png'],
};
const NEW_IMAGES = [
  'console-ota-app-block.png',
  'console-ota-app-token.png',
  'console-ota-upload-wizard.png',
  'console-ota-bundles.png',
  'console-ota-rollout.png',
  'console-ota-dashboard.png',
];

/** All markdown image refs of one page: [/screenshots/x.png, ...]. */
function imageRefs(markdown) {
  return [...markdown.matchAll(/!\[([^\]]*)\]\(([^)]+)\)/g)].map((m) => ({
    alt: m[1],
    ref: m[2],
  }));
}

function otaRefs(markdown) {
  return imageRefs(markdown).filter((i) => /\/screenshots\/console-ota-/.test(i.ref));
}

function pageAt(root, page) {
  return readFileSync(join(root, page), 'utf8');
}

function headPage(page) {
  return execFileSync('git', ['show', `HEAD:docs-site/${page}`], {
    cwd: repoRoot,
    encoding: 'utf8',
  });
}

/** PNG IHDR width: bytes 16-19 after the 8-byte signature. */
function pngWidth(file) {
  const buf = readFileSync(file);
  assert.equal(buf.subarray(12, 16).toString('latin1'), 'IHDR', `${file} is not a PNG`);
  return buf.readUInt32BE(16);
}

test('every console-ota image referenced by the OTA pages resolves to a file', () => {
  for (const page of Object.keys(PAGES)) {
    for (const { ref } of otaRefs(pageAt(docsRoot, page))) {
      const file = join(docsRoot, 'public', ref.replace(/^\//, ''));
      assert.ok(existsSync(file), `${page} references ${ref} but ${file} is missing`);
    }
  }
});

test('the issue-mandated images sit on their assigned pages with alt text', () => {
  for (const [page, mandatory] of Object.entries(PAGES)) {
    const refs = otaRefs(pageAt(docsRoot, page));
    for (const name of mandatory) {
      const hit = refs.find((i) => i.ref === `/screenshots/${name}`);
      assert.ok(hit, `${page} must embed /screenshots/${name}`);
      assert.ok(hit.alt.trim().length > 0, `${name} embed on ${page} needs alt text`);
    }
  }
});

test('all console-ota embeds on the pages carry alt text', () => {
  for (const page of Object.keys(PAGES)) {
    for (const { alt } of otaRefs(pageAt(docsRoot, page))) {
      assert.ok(alt.trim().length > 0, `an OTA embed on ${page} has empty alt text`);
    }
  }
});

test('the six screenshots follow the console dimension convention and are non-trivial', () => {
  for (const name of NEW_IMAGES) {
    const file = join(docsRoot, 'public', 'screenshots', name);
    assert.ok(existsSync(file), `${name} must exist`);
    assert.equal(pngWidth(file), 1440, `${name} must be 1440px wide like the console set`);
    assert.ok(statSync(file).size > 20_000, `${name} looks like a blank or failed capture (<20 KB)`);
  }
});

test('the OTA pages changed by embeds only (no removals, additions are the new screenshots)', () => {
  for (const page of Object.keys(PAGES)) {
    const before = imageRefs(headPage(page)).map((i) => i.ref);
    const after = imageRefs(pageAt(docsRoot, page)).map((i) => i.ref);
    const removed = before.filter((r) => !after.includes(r));
    const added = after.filter((r) => !before.includes(r));
    assert.deepEqual(removed, [], `${page} must not lose existing image embeds`);
    for (const ref of added) {
      assert.match(ref, /^\/screenshots\/console-ota-[a-z-]+\.png$/, `unexpected addition ${ref}`);
      assert.ok(
        NEW_IMAGES.includes(ref.slice('/screenshots/'.length)),
        `${ref} is not one of the #199 screenshots`,
      );
    }
    // Normalization: drop standalone image lines, then collapse the
    // blank-line runs their insertion widens — the guarded surface is
    // every non-image line, not paragraph spacing around embeds.
    const strip = (md) =>
      md
        .split('\n')
        .filter((line) => !/^\s*!\[[^\]]*\]\([^)]+\)\s*$/.test(line))
        .join('\n')
        .replace(/\n{2,}/g, '\n\n');
    assert.equal(
      strip(pageAt(docsRoot, page)),
      strip(headPage(page)),
      `${page} changed beyond standalone image-embed lines`,
    );
  }
});

// ---------------------------------------------------------------------------
// Failure paths: the same contracts against throwaway fixtures (mutation
// checks without touching the tracked pages).
// ---------------------------------------------------------------------------

test('a broken image ref fails resolution (fixture)', () => {
  const dir = mkdtempSync(join(tmpdir(), 'ota-shots-'));
  try {
    mkdirSync(join(dir, 'public', 'screenshots'), { recursive: true });
    writeFileSync(
      join(dir, 'page.md'),
      '![Alt](/screenshots/console-ota-missing.png)\n',
    );
    const refs = otaRefs(readFileSync(join(dir, 'page.md'), 'utf8'));
    assert.equal(refs.length, 1);
    const file = join(dir, 'public', refs[0].ref.replace(/^\//, ''));
    assert.ok(!existsSync(file), 'fixture must be broken for the mutation check');
    assert.throws(() => {
      if (!existsSync(file)) throw new Error(`${refs[0].ref} missing`);
    }, /missing/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('empty alt text is detected (fixture)', () => {
  const markdown = '![](/screenshots/console-ota-bundles.png)';
  const refs = otaRefs(markdown);
  assert.equal(refs.length, 1);
  assert.equal(refs[0].alt.trim().length > 0, false);
});

test('added-ref guard rejects a non-OTA addition (fixture)', () => {
  const before = ['![Old](/screenshots/console-dashboard.png)'];
  const after = [
    ...before,
    '![New](/screenshots/console-ota-bundles.png)',
    '![Sneaky](/concepts/diagram.png)',
  ];
  const refsOf = (lines) => lines.flatMap((l) => [...l.matchAll(/!\[([^\]]*)\]\(([^)]+)\)/g)].map((m) => m[2]));
  const added = refsOf(after).filter((r) => !refsOf(before).includes(r));
  assert.ok(added.includes('/concepts/diagram.png'));
  assert.throws(
    () => added.forEach((r) => assert.match(r, /^\/screenshots\/console-ota-[a-z-]+\.png$/)),
    { name: 'AssertionError' },
  );
});
