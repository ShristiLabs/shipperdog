// Console smoke suite for the release dashboard story (issue #59,
// S9-05). Not part of the CI gate (it needs the full compose stack);
// run it manually against a local `docker compose up`:
//
//   npm install --no-save playwright && npx playwright install chromium
//   node e2e/releases-verify.mjs
//
// Golden path: a release is created through the S9-01 API against the
// seeded demo stack (a fresh version of Banking with a genuinely READY
// Android artifact, targeting Acme at large on the engineering ring —
// the console's create dialog is a later story), then the dashboard
// owns the rest: the application-scoped listing with filters and
// status chips, the deep-linkable detail with its PRD 32 blocks,
// lifecycle transitions through the UI (including the APPROVED
// crossing that freezes the manifest and the mandatory-reason
// rollback), the state timeline with actors and reasons, and the
// frozen manifest viewer with the YAML compliance render. Fails on any
// unexpected console error.
import { chromium } from "playwright";
import { signedApk } from "./apk-fixture.mjs";

const BASE = "http://localhost:5173";
const TENANT_ACME = "10000000-0000-4000-8000-000000000002";
const APPLICATION_ID = "10000000-0000-4000-8000-000000000004";
const BANKING_PACKAGE = "com.shipperdog.demo.banking";

const results = [];
const consoleErrors = [];

function record(step, ok, detail = "") {
  results.push({ step, ok, detail });
  console.log(`${ok ? "PASS" : "FAIL"}  ${step}${detail ? " — " + detail : ""}`);
}

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
page.on("response", (response) => {
  const url = response.url();
  // The dashboard deliberately probes the manifest before APPROVED;
  // that 404 is the honest state, not an error.
  const expectedManifestMiss =
    response.status() === 404 && /\/releases\/[^/]+\/manifest/.test(url);
  if (url.includes("/api/") && response.status() >= 400 && !url.includes("/auth/login") && !expectedManifestMiss) {
    consoleErrors.push(`${response.status()} ${url}`);
  }
});
page.on("pageerror", (error) => consoleErrors.push(String(error)));
page.on("response", async (response) => {
  const url = response.url();
  if (url.includes("/api/")) {
    let body = "";
    if (response.status() >= 400) body = (await response.text().catch(() => "")).slice(0, 200);
    console.log(
      `NET ${response.request().method()} ${url.replace("http://localhost:5173", "")} -> ${response.status()} ${body}`,
    );
  }
});

/** The S9-05 fixture, all through the real APIs (one release per
 * application+version forever, so every run mints its own): a fresh
 * version of the seeded Banking application, a genuinely READY Android
 * artifact (a programmatic signed APK whose manifest agrees with the
 * registry's package name and the version's name — the validation
 * pipeline's FORMAT/SIGNING/APPLICATION_MATCH/VERSION_MATCH stages all
 * really run), then one DRAFT release targeting Acme's Internal QA
 * group on the engineering ring. */
async function createReleaseViaApi(token) {
  const json = async (path, init) => {
    const response = await fetch(`${BASE}/api/v1${path}`, init);
    if (!response.ok) {
      throw new Error(`${path} failed: ${response.status} ${(await response.text()).slice(0, 300)}`);
    }
    return response.json();
  };
  const auth = { Authorization: `Bearer ${token}` };

  // Version names take dotted numeric segments of at most five digits
  // each (the sort-key discipline); the millisecond timestamp split
  // across two segments keeps every run's version unique.
  const marker = Date.now().toString();
  const versionName = `9.${marker.slice(-10, -5)}.${marker.slice(-5)}`;
  const versionCode = 90000000 + Number(marker.slice(-5));
  const version = await json(`/applications/${APPLICATION_ID}/versions`, {
    method: "POST",
    headers: { ...auth, "Content-Type": "application/json" },
    body: JSON.stringify({ versionName }),
  });

  const apk = signedApk({ pkg: BANKING_PACKAGE, versionName, versionCode });
  const form = new FormData();
  form.set("version_id", version.id);
  form.set("platform", "ANDROID");
  form.set("build_number", String(versionCode));
  form.set("file", new Blob([apk], { type: "application/vnd.android.package-archive" }), `console-${marker}.apk`);
  const upload = await json("/artifacts", {
    method: "POST",
    headers: { ...auth, "Idempotency-Key": crypto.randomUUID() },
    body: form,
  });

  // Poll the artifact read until the async validation settles READY.
  let artifact = upload;
  for (let attempt = 0; attempt < 30 && artifact.status !== "READY"; attempt++) {
    await new Promise((resolve) => setTimeout(resolve, 1000));
    artifact = await json(`/artifacts/${artifact.id}?version_id=${version.id}`, { headers: auth });
  }
  if (artifact.status !== "READY") {
    throw new Error(`fixture artifact never reached READY (last: ${artifact.status})`);
  }
  console.log(`fixture: version ${versionName}, artifact READY (build ${versionCode})`);

  return json("/releases", {
    method: "POST",
    headers: {
      ...auth,
      "Content-Type": "application/json",
      "Idempotency-Key": crypto.randomUUID(),
    },
    body: JSON.stringify({
      applicationId: APPLICATION_ID,
      versionId: version.id,
      ring: "engineering",
      environmentId: null,
      artifacts: [{ platform: "ANDROID", artifactId: artifact.id }],
      // The tenant at large: the seed fixture's fixed tester-group ids
      // predate the API's UUID vocabulary, so an API-created group or
      // the empty selection are the addressable shapes.
      targets: [{ tenantId: TENANT_ACME, testerGroupIds: [] }],
    }),
  });
}

async function accessToken() {
  const session = await page.evaluate(() => localStorage.getItem("sd.console.session"));
  return JSON.parse(session).accessToken;
}

/** The chip labels releaseRules renders (PRD 17's vocabulary). */
const STATUS_LABELS = {
  DRAFT: "Draft",
  VALIDATING: "Validating",
  READY: "Ready",
  APPROVAL_PENDING: "Approval pending",
  APPROVED: "Approved",
  DEPLOYING: "Deploying",
  ACTIVE: "Active",
  PROMOTED: "Promoted",
  COMPLETED: "Completed",
  ROLLED_BACK: "Rolled back",
  FAILED: "Failed",
};

/** Clicks the lifecycle button for a target state; when a reason is
 * required (FAILED/ROLLED_BACK) fills the mandatory dialog. */
async function transition(to, reason) {
  await page.getByTestId(`transition-${to}`).click();
  if (reason !== undefined) {
    await page.getByTestId("transition-reason").fill(reason);
    await page.getByTestId("confirm-transition").click();
  }
  await page
    .getByTestId("release-status-chip")
    .filter({ hasText: STATUS_LABELS[to] })
    .waitFor({ timeout: 15_000 });
}

try {
  // 1. Login lands on the console.
  await page.goto(BASE + "/", { waitUntil: "networkidle" });
  await page.waitForURL("**/login", { timeout: 10_000 });
  await page.getByTestId("login-email").fill("dev@shipperdog.local");
  await page.getByTestId("login-password").fill("shipperdog-dev-admin");
  await page.getByTestId("login-submit").click();
  await page.getByTestId("tenants-grid").waitFor({ timeout: 15_000 });
  record("login lands on /tenants grid", true);

  // 2. Fixture: create the release through the S9-01 API.
  await page.waitForTimeout(500);
  const release = await createReleaseViaApi(await accessToken());
  console.log(`fixture: release ${release.releaseRef} (${release.id}) created via API`);

  // 3. The Releases nav opens the application-scoped listing; the new
  //    release is listed with its DRAFT chip (auto-refresh picks it up).
  await page.getByTestId("nav-releases").click();
  await page.getByTestId("releases-grid").waitFor({ timeout: 15_000 });
  await page.getByRole("gridcell", { name: release.releaseRef, exact: true }).waitFor({ timeout: 20_000 });
  const draftChip = await page
    .getByTestId("releases-grid")
    .getByText("Draft", { exact: true })
    .first()
    .isVisible();
  record("release listed with status chip after API creation", draftChip);

  // 3a. The status filter rides the query string server-side.
  await page.getByTestId("release-status-filter").click();
  await page.getByRole("option", { name: "Active" }).click();
  await page.waitForTimeout(800);
  const filteredGone = (await page.getByRole("gridcell", { name: release.releaseRef, exact: true }).isVisible().catch(() => false)) === false;
  record("status filter narrows the listing server-side", filteredGone);
  await page.getByTestId("release-status-filter").click();
  await page.getByRole("option", { name: "All statuses" }).click();
  await page.getByRole("gridcell", { name: release.releaseRef, exact: true }).waitFor({ timeout: 15_000 });
  await page.screenshot({ path: "/tmp/s9-05-list.png" });

  // 4. Deep-linkable detail: the §32 blocks with live data and E10
  //    empty states, the timeline's creation row.
  await page
    .getByRole("row", { name: new RegExp(release.releaseRef) })
    .getByTestId(/^view-/)
    .click();
  await page.getByTestId("release-title").waitFor({ timeout: 15_000 });
  const titleOk = (await page.getByTestId("release-title").textContent()).includes("Banking");
  const gatesEmpty = await page.getByTestId("gates-empty").isVisible();
  const approvalsEmpty = await page.getByTestId("approvals-empty").isVisible();
  const distribution = await page.getByTestId("distribution-section").getByText("Acme").isVisible();
  const timelineCreated = await page.getByTestId("timeline-section").getByText("Created as Draft").isVisible();
  record(
    "detail renders live blocks and E10 empty states",
    titleOk && gatesEmpty && approvalsEmpty && distribution && timelineCreated,
  );
  const notFrozen = await page.getByTestId("manifest-not-frozen").isVisible();
  record("manifest answers the honest not-frozen state before APPROVED", notFrozen);
  await page.screenshot({ path: "/tmp/s9-05-detail-draft.png" });

  // 5. Walk the state machine through the UI to APPROVED; the manifest
  //    freezes at that crossing.
  await transition("VALIDATING");
  await transition("READY");
  await transition("APPROVAL_PENDING");
  await transition("APPROVED");
  await page.getByTestId("manifest-sha").waitFor({ timeout: 15_000 });
  const frozenPin = await page
    .getByTestId("manifest-document")
    .getByText(/build 9\d{7} ·/)
    .isVisible()
    .catch(() => false);
  record("manifest frozen at APPROVED with artifact pin identity", frozenPin);

  // 5a. The YAML compliance render and the download affordance.
  await page.getByTestId("manifest-yaml").getByText("releaseRef").waitFor({ timeout: 15_000 });
  const downloadEnabled = await page.getByTestId("manifest-download").isEnabled();
  record("manifest YAML render and download present", downloadEnabled);
  await page.screenshot({ path: "/tmp/s9-05-manifest.png" });

  // 6. Continue to ACTIVE, then the mandatory-reason rollback; the
  //    timeline carries the reason.
  await transition("DEPLOYING");
  await transition("ACTIVE");
  await transition("ROLLED_BACK", "console smoke rollback: pulling the direct channel");
  const reasonOnTimeline = await page
    .getByTestId("timeline-section")
    .getByText("console smoke rollback")
    .isVisible();
  record("rollback requires a reason that lands on the timeline", reasonOnTimeline);

  // 7. Terminal state: no further lifecycle actions offered.
  const noActions = await page.getByText("is terminal", { exact: false }).isVisible();
  record("terminal state offers no transitions", noActions);
  await page.screenshot({ path: "/tmp/s9-05-terminal.png" });

  // 8. Logout.
  await page.getByTestId("logout-button").click();
  await page.waitForURL("**/login", { timeout: 10_000 });
  record("logout returns to /login", true);
} catch (error) {
  record("unexpected flow failure", false, String(error).slice(0, 400));
  await page.screenshot({ path: "/tmp/s9-05-failure.png" }).catch(() => undefined);
}

await browser.close();

const failures = results.filter((entry) => !entry.ok);
console.log(`\n${results.length - failures.length}/${results.length} steps passed`);
if (consoleErrors.length > 0) {
  console.log("console errors:");
  for (const error of consoleErrors) console.log("  " + error.slice(0, 200));
} else {
  console.log("console errors: none");
}
process.exit(failures.length > 0 || consoleErrors.length > 0 ? 1 : 0);
