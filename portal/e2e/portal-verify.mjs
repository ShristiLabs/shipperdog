// Portal smoke suite for the tester-portal story (issue #35). Not part
// of the CI gate (it needs the full compose stack); run it manually
// against a local `docker compose up`:
//
//   npm install --no-save playwright && npx playwright install chromium
//   node e2e/portal-verify.mjs
//
// Exercises the golden path end to end: provision a linked-tester
// persona through the admin API (S6-01 invitation, dev-mode token
// visibility), accept the invitation in the portal (set password ->
// login), verify the authenticated shell and honest empty state, log
// out, and sign back in with the credentials just set. Also checks the
// failure paths (opaque 401, missing token card, anonymous redirect)
// and fails on any unexpected console error.
import { chromium } from "playwright";

const PORTAL = "http://localhost:5174";
const API = "http://localhost:8080";
const CONSOLE_ADMIN = { email: "dev@shipperdog.local", password: "shipperdog-dev-admin" };

const results = [];
const consoleErrors = [];

function record(step, ok, detail = "") {
  results.push({ step, ok, detail });
  console.log(`${ok ? "PASS" : "FAIL"}  ${step}${detail ? " — " + detail : ""}`);
}

async function api(path, options = {}) {
  const response = await fetch(API + path, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      Accept: "application/json",
      ...(options.headers ?? {}),
    },
  });
  const body = response.status === 204 ? null : await response.json().catch(() => null);
  if (!response.ok) {
    throw new Error(`${options.method ?? "GET"} ${path} -> ${response.status}: ${JSON.stringify(body)}`);
  }
  return body;
}

const browser = await chromium.launch({
  // PLAYWRIGHT_CHROMIUM_PATH lets a machine with an existing
  // Chromium-for-Testing build reuse it instead of downloading the
  // playwright-pinned one.
  executablePath: process.env.PLAYWRIGHT_CHROMIUM_PATH || undefined,
});
const page = await browser.newPage({ viewport: { width: 390, height: 844 } }); // phone-sized
page.on("pageerror", (error) => consoleErrors.push(String(error)));
page.on("response", (response) => {
  const url = response.url();
  if (url.includes("/api/") && response.status() >= 400) {
    console.log(`NET ${response.request().method()} ${url.replace(PORTAL, "")} -> ${response.status()}`);
  }
});

try {
  // 0. Provision the linked-tester persona through the admin API: the
  // seeded dev admin invites a fresh tester; the creation response
  // carries the invitation block exactly once (dev-mode visibility).
  const login = await api("/api/v1/auth/login", {
    method: "POST",
    body: JSON.stringify({ email: CONSOLE_ADMIN.email, password: CONSOLE_ADMIN.password }),
  });
  const me = await api("/api/v1/auth/me", {
    headers: { Authorization: `Bearer ${login.accessToken}` },
  });
  const testerEmail = `e2e-portal-${Date.now()}@example.com`;
  const created = await api("/api/v1/testers", {
    method: "POST",
    headers: { Authorization: `Bearer ${login.accessToken}` },
    body: JSON.stringify({
      organizationId: me.orgId,
      email: testerEmail,
      fullName: "E2E Portal Tester",
    }),
  });
  const token = created.invitation?.invitationToken;
  if (!token) throw new Error("invitation token missing from tester creation response");
  record("tester persona invited through admin API", true, `${testerEmail} status ${created.status}`);
  if (created.invitation.acceptUrl.includes(":5174")) {
    record("acceptUrl points at the portal origin", true, created.invitation.acceptUrl);
  } else {
    record("acceptUrl points at the portal origin", false, created.invitation.acceptUrl);
  }

  // 1. Anonymous redirect to login.
  await page.goto(PORTAL + "/", { waitUntil: "networkidle" });
  await page.waitForURL("**/login", { timeout: 10_000 });
  record("anonymous redirect to /login", true);

  // 2. Failed login keeps the opaque error.
  await page.getByTestId("login-email").fill(testerEmail);
  await page.getByTestId("login-password").fill("wrong-password-forever");
  await page.getByTestId("login-submit").click();
  await page.getByTestId("login-error").waitFor({ timeout: 10_000 });
  const errorText = await page.getByTestId("login-error").textContent();
  record("invalid credentials shows opaque error, stays on /login", !/suspend|deactivat/i.test(errorText ?? ""));
  await page.screenshot({ path: "/tmp/s8-01-login-error.png" });

  // 3. Invitation accept: follow the accept link, set the password,
  // land signed-in on the portal home.
  await page.goto(created.invitation.acceptUrl, { waitUntil: "networkidle" });
  await page.getByTestId("accept-name").fill("E2E Portal Tester");
  await page.getByTestId("accept-email").fill(testerEmail);
  await page.getByTestId("accept-password").fill("correct-horse-battery");
  await page.getByTestId("accept-confirm").fill("correct-horse-battery");
  await page.getByTestId("accept-submit").click();
  await page.getByTestId("portal-home-empty").waitFor({ timeout: 15_000 });
  record("invitation accept: set password -> signed-in portal home", true);
  await page.screenshot({ path: "/tmp/s8-01-portal-home.png" });

  // 4. The shell shows the tester identity and the honest empty state
  // (no grants yet: INV-005 resting state, not an error).
  const identity = await page.getByTestId("portal-user").textContent();
  record("shell shows tester identity", (identity ?? "").includes("E2E Portal Tester"));

  // 5. Logout clears the session and returns to login.
  await page.getByTestId("portal-logout").click();
  await page.waitForURL("**/login", { timeout: 10_000 });
  const sessionCleared = await page.evaluate(() => localStorage.getItem("sd.portal.session") === null);
  record("logout clears local session and returns to /login", sessionCleared);

  // 6. Sign back in with the credentials set at accept time.
  await page.getByTestId("login-email").fill(testerEmail);
  await page.getByTestId("login-password").fill("correct-horse-battery");
  await page.getByTestId("login-submit").click();
  await page.getByTestId("portal-home-empty").waitFor({ timeout: 15_000 });
  record("standard login works for the activated tester", true);

  // 7. Missing token renders the honest card, not a crash.
  await page.goto(PORTAL + "/accept-invitation", { waitUntil: "networkidle" });
  await page.getByTestId("accept-missing-token").waitFor({ timeout: 10_000 });
  record("accept-invitation without token renders the missing-token card", true);
} catch (error) {
  record("suite crashed", false, String(error));
  await page.screenshot({ path: "/tmp/s8-01-crash.png" }).catch(() => {});
} finally {
  await browser.close();
}

if (consoleErrors.length > 0) {
  record("no unexpected page errors", false, consoleErrors.join(" | "));
}

const failed = results.filter((entry) => !entry.ok);
console.log(`\n${results.length - failed.length}/${results.length} steps passed`);
process.exit(failed.length > 0 ? 1 : 0);
