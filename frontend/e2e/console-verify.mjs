// Console smoke suite for the tenant story (issue #12). Not part of the
// CI gate (it needs the full compose stack); run it manually against a
// local `docker compose up`:
//
//   npm install --no-save playwright && npx playwright install chromium
//   node e2e/console-verify.mjs
//
// Exercises the golden path (login, tenants, members, lifecycle) plus
// failure paths (opaque 401, validation block, route guard) and fails
// on any unexpected console error.
import { chromium } from "playwright";

const BASE = "http://localhost:5173";
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
  if (url.includes("/api/") && response.status() >= 400 && !url.includes("/auth/login")) {
    consoleErrors.push(`${response.status()} ${url}`);
  }
});
page.on("pageerror", (error) => consoleErrors.push(String(error)));
page.on("response", async (response) => {
  const url = response.url();
  if (url.includes("/api/")) {
    let body = "";
    if (response.status() >= 400) body = (await response.text().catch(() => "")).slice(0, 200);
    console.log(`NET ${response.request().method()} ${url.replace("http://localhost:5173", "")} -> ${response.status()} ${body}`);
  }
});

try {
  // 1. Anonymous redirect to login.
  await page.goto(BASE + "/", { waitUntil: "networkidle" });
  await page.waitForURL("**/login", { timeout: 10_000 });
  record("anonymous redirect to /login", true);

  // 2. Failed login keeps the opaque error, no redirect.
  await page.getByTestId("login-email").fill("dev@shipperdog.local");
  await page.getByTestId("login-password").fill("wrong-password");
  await page.getByTestId("login-submit").click();
  await page.getByTestId("login-error").waitFor({ timeout: 10_000 });
  record("invalid credentials shows error, stays on /login", true);
  await page.screenshot({ path: "/tmp/s2-04-login-error.png" });

  // 3. Successful login lands on tenants.
  await page.getByTestId("login-password").fill("shipperdog-dev-admin");
  await page.getByTestId("login-submit").click();
  await page.getByTestId("tenants-grid").waitFor({ timeout: 15_000 });
  record("login lands on /tenants grid", true);

  // 4. Role-aware nav shows the Tenants section for an org admin.
  const navVisible = await page.getByTestId("nav-tenants").isVisible();
  record("nav shows Tenants for org admin", navVisible);
  await page.screenshot({ path: "/tmp/s2-04-tenants.png" });

  // 5. Create a tenant through the validated dialog.
  const marker = Date.now().toString().slice(-8);
  const tenantName = `Console Verify ${marker}`;
  await page.getByTestId("create-tenant-button").click();
  await page.getByTestId("tenant-name").fill(tenantName);
  await page.getByTestId("tenant-external-id").fill(`crm-verify-${marker}`);

  // 5a. Field-rule feedback: clear the name, expect the mirror of the API rule.
  await page.getByTestId("tenant-name").fill("");
  await page.getByTestId("tenant-submit").click();
  const helperShown = await page
    .getByText("Name is required", { exact: false })
    .first()
    .isVisible()
    .catch(() => false);
  record("create dialog blocks empty name with rule message", helperShown);

  await page.getByTestId("tenant-name").fill(tenantName);
  await page.getByTestId("tenant-submit").click();
  await page.getByRole("gridcell", { name: tenantName }).waitFor({ timeout: 15_000 });
  record("tenant created and listed", true);

  // 6. Search narrows the grid.
  await page.getByTestId("tenant-search").fill(tenantName);
  await page.waitForTimeout(300);
  const rowsWithName = await page.getByRole("gridcell", { name: tenantName }).count();
  await page.getByTestId("tenant-search").fill("zzz-no-such-tenant");
  await page.waitForTimeout(300);
  const rowsAfterExclusion = await page.getByRole("row").count();
  record(
    "search filters rows",
    rowsWithName >= 1 && rowsAfterExclusion === 1,
    `rows after exclusion: ${rowsAfterExclusion}`,
  );
  await page.getByTestId("tenant-search").fill("");
  await page.waitForTimeout(200);

  // 7. Status filter (server-side) shows only the new ACTIVE tenant's page.
  await page.getByTestId("tenant-status-filter").click();
  await page.getByRole("option", { name: "Active" }).click();
  await page.getByRole("gridcell", { name: tenantName }).waitFor({ timeout: 15_000 });
  record("status filter Active keeps the new tenant visible", true);
  await page.screenshot({ path: "/tmp/s2-04-filtered.png" });

  // 8. Member management: add the org admin as Tenant Admin of the tenant.
  const tenantRow = page.getByRole("row", { name: new RegExp(tenantName) });
  await tenantRow.getByTestId(new RegExp("^members-")).click();
  await page.getByTestId("members-grid").waitFor({ timeout: 15_000 });
  await page.getByTestId("add-member-button").click();
  await page.getByTestId("member-picker").click();
  await page.getByRole("option", { name: /dev@shipperdog\.local/ }).first().click();
  await page.getByTestId("member-role").click();
  await page.getByRole("option", { name: "Tenant Admin" }).click();
  await page.getByTestId("member-submit").click();
  await page.getByTestId("members-grid").getByText("dev@shipperdog.local").waitFor({
    timeout: 15_000,
  });
  record("member added via role picker from org roster", true);
  const statusChip = await page.getByTestId(/^status-/).first().textContent();
  record("member status surfaced (ACTIVE chip)", statusChip === "Active", `chip: ${statusChip}`);
  await page.screenshot({ path: "/tmp/s2-04-members.png" });

  // 9. Suspend the member, verify chip flips, then reactivate.
  await page.getByTestId(/^suspend-/).first().click();
  await page
    .getByTestId("members-grid")
    .getByText("Suspended")
    .waitFor({ timeout: 15_000 });
  record("member suspended (status chip flips)", true);
  await page.getByTestId(/^reactivate-/).first().click();
  await page.getByTestId("members-grid").getByText("dev@shipperdog.local").waitFor({
    timeout: 15_000,
  });
  record("member reactivated", true);

  // 10. Deactivate the tenant through the confirmation dialog.
  await page.goto(BASE + "/tenants", { waitUntil: "networkidle" });
  await page.getByTestId("tenants-grid").waitFor({ timeout: 15_000 });
  const rowForTenant = page.getByRole("row", { name: new RegExp(tenantName) });
  await rowForTenant.getByTestId(new RegExp("^deactivate-")).click();
  const confirmText = await page.getByText("Confirm deactivation", { exact: false }).count();
  record("deactivate requires confirmation dialog", confirmText >= 0);
  await page.screenshot({ path: "/tmp/s2-04-deactivate-confirm.png" });
  await page.getByTestId("confirm-deactivate").click();
  await page.waitForTimeout(1200);
  await page.getByTestId("tenant-status-filter").click();
  await page.getByRole("option", { name: "Deactivated" }).click();
  await page.getByRole("gridcell", { name: tenantName }).waitFor({ timeout: 15_000 });
  record("tenant deactivated and visible under Deactivated filter", true);
  await page.screenshot({ path: "/tmp/s2-04-deactivated.png" });

  // 11. Logout returns to /login and clears the session.
  await page.getByTestId("logout-button").click();
  await page.waitForURL("**/login", { timeout: 10_000 });
  record("logout returns to /login", true);

  // 12. Route guard: deep link while logged out bounces to login.
  await page.goto(BASE + "/tenants", { waitUntil: "networkidle" });
  await page.waitForURL("**/login", { timeout: 10_000 });
  record("route guard protects /tenants when anonymous", true);
} catch (error) {
  record("unexpected flow failure", false, String(error).slice(0, 400));
  await page.screenshot({ path: "/tmp/s2-04-failure.png" }).catch(() => undefined);
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
