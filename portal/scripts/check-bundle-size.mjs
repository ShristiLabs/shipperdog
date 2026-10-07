/**
 * Bundle budget (issue #39, S8-05): fails when the built SPA's hashed
 * assets exceed the raw-size ceiling. The budget is deliberately
 * generous headroom over the current bundle (a regression guard
 * against accidentally importing the console, a fat dependency, or an
 * un-split giant) — not a tuning tool; shrinking the budget is a
 * conscious decision recorded with the number.
 *
 * Run after `npm run build` from the app root: node scripts/check-bundle-size.mjs
 */
import { readdirSync, statSync } from "node:fs";
import { join } from "node:path";

const BUDGET_TOTAL_KB = 1000;
const BUDGET_SINGLE_KB = 700;
const ASSETS = "dist/assets";

let total = 0;
let biggest = { name: "-", kb: 0 };
for (const entry of readdirSync(ASSETS)) {
  if (!/\.(js|css)$/.test(entry)) continue;
  const kb = statSync(join(ASSETS, entry)).size / 1024;
  total += kb;
  if (kb > biggest.kb) biggest = { name: entry, kb: Math.round(kb) };
}
const totalKb = Math.round(total);
console.log(`bundle total: ${totalKb} KB (budget ${BUDGET_TOTAL_KB} KB); largest: ${biggest.name} ${biggest.kb} KB`);
if (totalKb > BUDGET_TOTAL_KB) {
  console.error(`FAIL: bundle exceeds the ${BUDGET_TOTAL_KB} KB budget`);
  process.exit(1);
}
if (biggest.kb > BUDGET_SINGLE_KB) {
  console.error(`FAIL: single chunk ${biggest.name} exceeds the ${BUDGET_SINGLE_KB} KB budget`);
  process.exit(1);
}
