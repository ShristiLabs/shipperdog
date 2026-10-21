// Tests for how-to/automation/cli-reference.md's self-declared contract:
// the appendix holds "the full help text of every command" and the command
// tables are generated from that help. The vitepress build gate already
// fails on dead links; these assert the command-surface consistency the
// build cannot see — a dropped or invented command would otherwise pass.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const page = readFileSync(
  join(dirname(fileURLToPath(import.meta.url)), '..', 'how-to', 'automation', 'cli-reference.md'),
  'utf8',
);

// Appendix blocks: "#### `shipperdog <path>`" followed by a ```text fence.
const blocks = new Map();
for (const m of page.matchAll(/^#### `shipperdog ([^`]+)`\s*\n+```text\n(.*?)\n```/gms)) {
  blocks.set(m[1], m[2]);
}
const rootBlock = page.match(/^#### `shipperdog` \(root\)\s*\n+```text\n(.*?)\n```/ms)[1];

// cobra builtins documented outside the appendix (help) or in prose
// (completion — the "Shell completion" section).
const notAppendixed = new Set(['completion', 'help']);

function availableCommands(block) {
  const section = block.match(/^Available Commands:\n((?:  \S+\s{2,}.*\n)+)/m);
  if (!section) return [];
  return [...section[1].matchAll(/^  (\S+)\s{2,}/gm)].map((row) => row[1]);
}

test('every command a group help lists has an appendix block (nothing dropped)', () => {
  const groups = [['', rootBlock], ...blocks];
  for (const [path, body] of groups) {
    for (const sub of availableCommands(body)) {
      if (notAppendixed.has(sub)) continue;
      const child = path ? `${path} ${sub}` : sub;
      assert.ok(blocks.has(child), `no appendix block for "${child}"`);
    }
  }
});

test('every appendix command is listed in its parent group help (nothing invented)', () => {
  for (const path of blocks.keys()) {
    const parent = path.split(' ').slice(0, -1).join(' ');
    const leaf = path.split(' ').at(-1);
    const parentBlock = parent ? blocks.get(parent) : rootBlock;
    assert.ok(parentBlock, `appendix block "${path}" has no parent block`);
    assert.ok(
      availableCommands(parentBlock).includes(leaf),
      `"${leaf}" is not listed in the "${parent || 'root'}" help`,
    );
  }
});

test('the ota command table lists exactly the ota leaf commands from help', () => {
  const section = page.match(
    /### `ota` — .*?\n\n.*?\n\n\| Command \| Purpose \| Key flags \|\n\|---\|---\|---\|\n((?:\|.*\n)+)/s,
  );
  assert.ok(section, 'no ota command table in the inventory');
  const tabled = new Set(
    [...section[1].matchAll(/^\| `ota ([^`]+)`/gm)].map((m) =>
      m[1].replace(/<[^>]*>/g, '').trim(),
    ),
  );
  const leaves = new Set(
    [...blocks.keys()]
      .filter((p) => p.startsWith('ota ') && !availableCommands(blocks.get(p)).length)
      .map((p) => p.replace(/^ota /, '')),
  );
  assert.deepEqual([...tabled].sort(), [...leaves].sort());
});

test('the provenance tip and the appendix intro cite the same commit', () => {
  const tip = page.match(/captured from the CLI as of commit `([0-9a-f]{7,40})`/);
  const intro = page.match(/^Captured from the CLI's own `--help` at commit `([0-9a-f]{7,40})`\./m);
  assert.ok(tip, 'no provenance tip commit');
  assert.ok(intro, 'no appendix intro commit');
  assert.equal(tip[1], intro[1]);
});
