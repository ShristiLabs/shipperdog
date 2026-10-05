// Console smoke suite for the application registry story (issue #16,
// S3-04). Not part of the CI gate (it needs the full compose stack);
// run it manually against a local `docker compose up`:
//
//   npm install --no-save playwright && npx playwright install chromium
//   node e2e/applications-verify.mjs
//
// Exercises the golden path: the organization-wide registry listing
// (including a freshly registered shared application nobody is
// authorized for — the reachability surface), the per-tenant
// effective-visibility mode, the platform-coupled create dialog, the
// detail shell with its E4/E5 empty states, authorize with a reason,
// the scope transition's 409 precondition (revoke first), and the
// confirmed archive/restore lifecycle. Fails on any unexpected
// console error.
import { chromium } from "playwright";

const BASE = "http://localhost:5173";
const TENANT_NAME = "Acme"; // the seeded demo tenant
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
  // The deliberate scope-transition block (step 8) answers 409 on
  // PATCH /applications/{id} — expected, not an error.
  const expectedPrecondition =
    response.status() === 409 && response.request().method() === "PATCH" && /\/applications\//.test(url);
  if (url.includes("/api/") && response.status() >= 400 && !url.includes("/auth/login") && !expectedPrecondition) {
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

/** Picks ${name} in an MUI Autocomplete located by testid. */
async function pickInAutocomplete(testid, name) {
  const picker = page.getByTestId(testid);
  await picker.click();
  await picker.getByRole("combobox").fill(name);
  await page.getByRole("option", { name }).first().click();
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

  // 2. Applications nav opens the registry (organization-wide mode).
  await page.getByTestId("nav-applications").click();
  await page.getByTestId("applications-grid").waitFor({ timeout: 15_000 });
  await page.getByRole("gridcell", { name: "Banking", exact: true }).waitFor({ timeout: 15_000 });
  record("org-wide listing opens and shows the seeded Banking app", true);

  // 3. By-tenant mode resolves effective visibility for the seeded tenant.
  await page.getByTestId("application-mode-tenant").click();
  await page.getByTestId("application-tenant-picker").waitFor({ timeout: 15_000 });
  await pickInAutocomplete("application-tenant-picker", TENANT_NAME);
  await page.getByRole("gridcell", { name: "Banking", exact: true }).waitFor({ timeout: 15_000 });
  record("by-tenant mode lists the tenant's effective visibility", true);
  await page.getByTestId("application-mode-all").click();
  await page.waitForTimeout(500);

  // 4. Register a tenant-owned application through the validated dialog.
  const marker = Date.now().toString().slice(-8);
  const ownedName = `Console Wallet ${marker}`;
  await page.getByTestId("create-application-button").click();
  await page.getByTestId("app-name").fill(ownedName);
  await page.getByTestId("platform-ios").click();

  // 4a. Platform coupling: iOS without a bundle id is blocked.
  await page.getByTestId("application-submit").click();
  const couplingBlocked = await page
    .getByTestId("platforms-helper")
    .getByText("bundleId is required when its platform is chosen")
    .waitFor({ timeout: 10_000 })
    .then(() => true)
    .catch(() => false);
  record("create blocks iOS without a bundle id (API rule mirrored)", couplingBlocked);

  await page.getByTestId("app-bundle-id").fill(`local.acme.wallet.v${marker}`);
  await page.getByTestId("application-submit").click();
  await page.getByRole("gridcell", { name: ownedName }).waitFor({ timeout: 15_000 });
  record("tenant-owned application registered and listed", true);
  await page.screenshot({ path: "/tmp/s3-04-list.png" });

  // 5. Detail shell: metadata, versions/artifacts empty states.
  await page.getByRole("row", { name: new RegExp(ownedName) }).getByTestId(/^view-/).click();
  await page.getByTestId("application-title").waitFor({ timeout: 15_000 });
  const versionsEmpty = await page.getByTestId("versions-empty").isVisible();
  const artifactsEmpty = await page.getByTestId("artifacts-empty").isVisible();
  record("detail shell renders E4/E5 empty states", versionsEmpty && artifactsEmpty);
  await page.screenshot({ path: "/tmp/s3-04-detail.png" });

  // 6. Register an organization-shared application; it must be listed
  //    immediately — nobody is authorized for it yet (reachability).
  await page.getByTestId("nav-applications").click();
  await page.getByTestId("applications-grid").waitFor({ timeout: 15_000 });
  const sharedName = `Console Shared ${marker}`;
  await page.getByTestId("create-application-button").click();
  await page.getByTestId("app-name").fill(sharedName);
  await page.getByTestId("platform-ios").click();
  await page.getByTestId("app-bundle-id").fill(`local.acme.shared.v${marker}`);
  await page.getByTestId("app-scope").click();
  await page.getByRole("option", { name: "Organization-shared" }).click();
  await page.getByTestId("application-submit").click();
  await page.getByRole("gridcell", { name: sharedName }).waitFor({ timeout: 15_000 });
  record("shared application listed before any authorization (reachability)", true);

  // 7. Open the shared application and authorize the tenant with a reason.
  await page.getByRole("row", { name: new RegExp(sharedName) }).getByTestId(/^view-/).click();
  await page.getByTestId("authorization-section").waitFor({ timeout: 15_000 });
  await page.getByTestId("grant-button").click();
  await pickInAutocomplete("grant-tenant-picker", TENANT_NAME);
  await page.getByTestId("grant-reason").fill("Console smoke pilot authorization");
  await page.getByTestId("grant-submit").click();
  await page.getByTestId("authorization-grid").getByText(TENANT_NAME).waitFor({ timeout: 15_000 });
  record("tenant authorized with audit reason", true);
  await page.screenshot({ path: "/tmp/s3-04-authorization.png" });

  // 8. Scope transition is blocked while the authorization is active.
  await page.getByTestId("edit-application").click();
  await page.getByTestId("app-scope").click();
  await page.getByRole("option", { name: "Tenant-owned" }).click();
  await pickInAutocomplete("app-owner-tenant", TENANT_NAME);
  await page.getByTestId("application-submit").click();
  const blockedVisible = await page
    .getByText("APPLICATION_AUTHORIZED_TENANTS_PRESENT")
    .waitFor({ timeout: 15_000 })
    .then(() => true)
    .catch(() => false);
  record("ORG->TENANT conversion blocked readably while grants are active", blockedVisible);
  await page.screenshot({ path: "/tmp/s3-04-transition-blocked.png" });
  await page.keyboard.press("Escape");

  // 9. Revoke the authorization with a reason, then convert.
  await page.getByTestId(/^revoke-/).first().click();
  await page.getByTestId("revoke-reason").fill("Console smoke revocation");
  await page.getByTestId("confirm-revoke").click();
  await page.waitForTimeout(1500);
  await page.getByTestId("edit-application").click();
  await page.getByTestId("app-scope").click();
  await page.getByRole("option", { name: "Tenant-owned" }).click();
  await pickInAutocomplete("app-owner-tenant", TENANT_NAME);
  await page.getByTestId("application-submit").click();
  const converted = await page
    .getByText(new RegExp(`Tenant-owned — ${TENANT_NAME}`))
    .first()
    .waitFor({ timeout: 15_000 })
    .then(() => true)
    .catch(() => false);
  record("scope conversion succeeds after revocation", converted);
  await page.screenshot({ path: "/tmp/s3-04-converted.png" });

  // 10. Archive with confirmation naming the application, then restore.
  await page.getByTestId("archive-application").click();
  const confirmText = await page.getByText(/Confirm archiving of/i).textContent();
  const confirmNamesApp = confirmText.includes(sharedName);
  await page.getByTestId("confirm-archive").click();
  await page.getByText("Archived").first().waitFor({ timeout: 15_000 });
  record("archive confirmation names the application", confirmNamesApp);
  await page.getByTestId("restore-application").click();
  await page.getByTestId("archive-application").waitFor({ timeout: 15_000 });
  record("archive reversible (restore)", true);
  await page.screenshot({ path: "/tmp/s3-04-restored.png" });

  // 11. Logout.
  await page.getByTestId("logout-button").click();
  await page.waitForURL("**/login", { timeout: 10_000 });
  record("logout returns to /login", true);
} catch (error) {
  record("unexpected flow failure", false, String(error).slice(0, 400));
  await page.screenshot({ path: "/tmp/s3-04-failure.png" }).catch(() => undefined);
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
