#!/usr/bin/env node
// Regenerates versions.json at the root of a gh-pages deploy tree.
// Called by .github/workflows/docs-site.yml after every deploy and by the
// bad-publish recovery procedure in docs-site/VERSIONING.md — it is the
// ONLY writer of the deployed versions.json (PLAN 2.2: never hand-edited).
//
// Usage: node regen-versions.mjs <deploy-root-dir> <current-version-id>
//   <deploy-root-dir>  directory containing versions.json (created if absent)
//   <current-version-id> 'snapshot' or a released id like 'v0.1'
//
// Contract: { "latest": <highest released id or null>,
//             "versions": [ {id, label}, snapshot first, releases newest-first ] }
// Existing entries for other versions are preserved verbatim; the current
// version's entry is (re)written with its canonical label.

import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const SNAPSHOT = 'snapshot';
const SNAPSHOT_LABEL = 'SNAPSHOT (main)';
const RELEASED_ID = /^v\d+(\.\d+)+$/;

const rootDir = process.argv[2];
const currentId = process.argv[3];
if (!rootDir || !currentId) {
  console.error('usage: regen-versions.mjs <deploy-root-dir> <current-version-id>');
  process.exit(2);
}
if (currentId !== SNAPSHOT && !RELEASED_ID.test(currentId)) {
  // Test ids like v0.0-docs-test are allowed only via the workflow_dispatch
  // test path; they deploy but must not become "latest" and are stripped on
  // the next regen unless re-declared. Anything else is a caller bug.
  if (!/^v\d+\.\d+-docs-test$/.test(currentId)) {
    console.error(`invalid version id: ${currentId}`);
    process.exit(2);
  }
}

const file = join(rootDir, 'versions.json');
let existing = [];
if (existsSync(file)) {
  try {
    const parsed = JSON.parse(readFileSync(file, 'utf8'));
    if (!Array.isArray(parsed.versions)) throw new Error('missing versions array');
    existing = parsed.versions.filter(
      (v) => v && typeof v.id === 'string' && typeof v.label === 'string',
    );
  } catch (e) {
    console.error(`versions.json unreadable, refusing to clobber: ${e.message}`);
    process.exit(1);
  }
}

const isReleased = (id) => RELEASED_ID.test(id);
// Transient doc-test subtrees never persist in the contract unless the
// caller is deploying one right now.
const keep = existing.filter((v) => isReleased(v.id) || v.id === SNAPSHOT || v.id === currentId);

const byId = new Map(keep.map((v) => [v.id, v]));
byId.set(SNAPSHOT, { id: SNAPSHOT, label: SNAPSHOT_LABEL });
if (currentId !== SNAPSHOT) byId.set(currentId, { id: currentId, label: currentId });

const releases = [...byId.values()].filter((v) => isReleased(v.id));
releases.sort((a, b) => {
  const pa = a.id.slice(1).split('.').map(Number);
  const pb = b.id.slice(1).split('.').map(Number);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const d = (pb[i] ?? 0) - (pa[i] ?? 0);
    if (d !== 0) return d;
  }
  return 0;
});

const manifest = {
  latest: releases[0]?.id ?? null,
  versions: [byId.get(SNAPSHOT), ...releases, ...(currentId !== SNAPSHOT && !isReleased(currentId) ? [byId.get(currentId)] : [])],
};

writeFileSync(file, `${JSON.stringify(manifest, null, 2)}\n`);
console.log(`versions.json written: latest=${manifest.latest} versions=[${manifest.versions.map((v) => v.id).join(', ')}]`);
