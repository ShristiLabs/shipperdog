/** OTA bundles screen behavior (S16-15, issue #177): the per-
 * application listing with the status chips, the upload wizard
 * walking create -> direct-to-storage PUT -> finalize+sign and
 * answering the SIGNED manifest facts (sha256, size, platformTag,
 * keyId), the same chain's failure legs (create, storage PUT,
 * finalize — each returning the form with the problem's wording and
 * never advancing past the failed leg), the exactly-once app-token
 * value (dismissed, then replaced by a fresh issue), the confirmed
 * bundle revocation, and the alert-rule store's create dialog. */
import { cleanup, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it } from "vitest";
import { Route, Routes } from "react-router-dom";
import OtaBundlesPage from "./OtaBundlesPage";
import { installFetchRouter, renderConsole, seedSession } from "../test/harness";

const user = userEvent.setup();

const ORG_ID = "10000000-0000-4000-8000-000000000001";
const TENANT_ACME = "20000000-0000-4000-8000-000000000001";
const ACTOR_ID = "10000000-0000-4000-8000-000000000010";
const APPLICATION_ID = "30000000-0000-4000-8000-000000000001";
const VERSION_ID = "40000000-0000-4000-8000-000000000001";
const BUNDLE_ID = "60000000-0000-4000-8000-000000000001";
const TOKEN_ID = "70000000-0000-4000-8000-000000000001";
const RULE_ID = "80000000-0000-4000-8000-000000000001";
const UPLOAD_URL = "http://storage.local:4443/upload/grant-1";

const BUNDLE = {
  id: BUNDLE_ID,
  applicationId: APPLICATION_ID,
  versionId: VERSION_ID,
  artifactId: "65000000-0000-4000-8000-000000000001",
  releaseId: null,
  bundleVersion: "2.4.1",
  platformTag: "js-bundle",
  sha256: "b1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1",
  sizeBytes: 2_097_152,
  signature: "sig-sig-sig-sig-sig-sig-sig-sig-sig-sig",
  keyId: "k1k1k1k1k1k1k1k1k1k1k1k1k1k1k1k1k1",
  forced: false,
  minAppVersion: null,
  status: "SIGNED",
  createdBy: ACTOR_ID,
  createdAt: "2026-10-10T10:00:00Z",
  updatedAt: "2026-10-10T10:05:00Z",
};

function fetchCalls(): Array<[string, RequestInit | undefined]> {
  const mock = fetch as unknown as { mock: { calls: Array<[string, RequestInit]> } };
  return mock.mock.calls;
}

function baseRoutes(extra: Parameters<typeof installFetchRouter>[0] = []) {
  return [
    {
      method: "GET",
      url: "/api/v1/applications",
      status: 200,
      body: {
        items: [
          {
            id: APPLICATION_ID,
            organizationId: ORG_ID,
            tenantId: TENANT_ACME,
            ownershipScope: "TENANT",
            name: "Banking",
            description: null,
            platforms: ["IOS"],
            bundleId: "com.acme.banking",
            packageName: null,
            status: "ACTIVE",
            createdAt: "2026-10-01T10:00:00Z",
            updatedAt: "2026-10-01T10:00:00Z",
          },
        ],
      },
    },
    {
      method: "GET",
      url: "/api/v1/ota/bundles",
      status: 200,
      body: { items: [BUNDLE] },
    },
    {
      method: "GET",
      url: "/api/v1/ota/app-tokens",
      status: 200,
      body: { items: [] },
    },
    {
      method: "GET",
      url: "/api/v1/ota/alert-rules",
      status: 200,
      body: { items: [] },
    },
    ...extra,
  ];
}

function renderPage(routes: Parameters<typeof installFetchRouter>[0], applicationId = "") {
  installFetchRouter(routes);
  seedSession();
  renderConsole(
    <Routes>
      <Route path="/ota/bundles/:applicationId?" element={<OtaBundlesPage />} />
    </Routes>,
    { route: applicationId === "" ? "/ota/bundles" : `/ota/bundles/${applicationId}` },
  );
}

/** The wizard's create answer for the 2.5.0 submission (the upload
 * grant the chain's second leg PUTs to). */
const DRAFT = {
  id: BUNDLE_ID,
  applicationId: APPLICATION_ID,
  versionId: VERSION_ID,
  bundleVersion: "2.5.0",
  platformTag: "js-bundle",
  forced: false,
  minAppVersion: null,
  status: "UPLOADING",
  upload: {
    artifactId: "65000000-0000-4000-8000-000000000001",
    uploadUrl: UPLOAD_URL,
    expiresAt: "2026-10-10T11:00:00Z",
  },
};

/** Drives one valid wizard submission (the error-path tests vary only
 * what the router answers to it). */
async function fillWizardSubmission(): Promise<void> {
  await user.click(screen.getByTestId("upload-bundle"));
  await user.type(screen.getByTestId("wizard-bundle-version"), "2.5.0");
  await user.type(screen.getByTestId("wizard-platform-tag"), "js-bundle");
  const file = new File(["bundle-bytes"], "bundle-2.5.0.zip", { type: "application/zip" });
  await user.upload(screen.getByTestId("wizard-file-input"), file);
  await user.click(screen.getByTestId("wizard-submit"));
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("OtaBundlesPage", () => {
  it("renders the application's bundle listing with status chips", async () => {
    renderPage(baseRoutes());

    expect(await screen.findByTestId("ota-bundles-title")).toHaveTextContent("OTA bundles");
    expect(await screen.findByRole("gridcell", { name: "2.4.1" })).toBeInTheDocument();
    expect(screen.getByRole("gridcell", { name: "Signed" })).toBeInTheDocument();
    expect(screen.getByTestId("ota-app-tokens")).toBeInTheDocument();
    expect(screen.getByTestId("ota-alert-rules")).toBeInTheDocument();
  });

  it("walks the upload wizard create -> PUT bytes -> finalize and shows the SIGNED manifest", async () => {
    renderPage(
      baseRoutes([
        {
          method: "POST",
          url: "/api/v1/ota/bundles",
          status: 201,
          body: {
            id: BUNDLE_ID,
            applicationId: APPLICATION_ID,
            versionId: VERSION_ID,
            bundleVersion: "2.5.0",
            platformTag: "js-bundle",
            forced: false,
            minAppVersion: null,
            status: "UPLOADING",
            upload: {
              artifactId: "65000000-0000-4000-8000-000000000001",
              uploadUrl: UPLOAD_URL,
              expiresAt: "2026-10-10T11:00:00Z",
            },
          },
        },
        {
          method: "PUT",
          url: UPLOAD_URL,
          status: 200,
        },
        {
          method: "POST",
          url: `/api/v1/ota/bundles/${BUNDLE_ID}/finalize`,
          status: 201,
          body: {
            created: true,
            bundle: { ...BUNDLE, bundleVersion: "2.5.0" },
          },
        },
      ]),
    );
    await screen.findByRole("gridcell", { name: "2.4.1" });

    await user.click(screen.getByTestId("upload-bundle"));
    await user.type(screen.getByTestId("wizard-bundle-version"), "2.5.0");
    await user.type(screen.getByTestId("wizard-platform-tag"), "js-bundle");
    const file = new File(["bundle-bytes"], "bundle-2.5.0.zip", { type: "application/zip" });
    await user.upload(screen.getByTestId("wizard-file-input"), file);
    await user.click(screen.getByTestId("wizard-submit"));

    // The signed manifest answers the chain: sha256, size, platform,
    // keyId (the SDK embedding facts).
    const signed = await screen.findByTestId("wizard-signed");
    expect(signed).toBeInTheDocument();
    expect(screen.getByTestId("signed-sha256")).toHaveTextContent(BUNDLE.sha256);
    expect(screen.getByTestId("signed-size")).toHaveTextContent("2.0 MB");
    expect(screen.getByTestId("signed-platform")).toHaveTextContent("js-bundle");
    expect(screen.getByTestId("signed-signing-keyid")).toHaveTextContent(BUNDLE.keyId);

    const calls = fetchCalls();
    const create = calls.find(([url, init]) => url.endsWith("/ota/bundles") && init?.method === "POST");
    expect(create).toBeDefined();
    expect(JSON.parse(String(create?.[1]?.body))).toMatchObject({
      applicationId: APPLICATION_ID,
      bundleVersion: "2.5.0",
      platformTag: "js-bundle",
      fileName: "bundle-2.5.0.zip",
      forced: false,
    });
    // The bytes go direct to storage: the granted URL, octet-stream,
    // and no bearer header (the CLI PutToStorage doctrine).
    const put = calls.find(([url, init]) => url === UPLOAD_URL && init?.method === "PUT");
    expect(put).toBeDefined();
    expect(put?.[1]?.headers).toMatchObject({ "Content-Type": "application/octet-stream" });
    const finalize = calls.find(
      ([url, init]) => url.endsWith(`/ota/bundles/${BUNDLE_ID}/finalize`) && init?.method === "POST",
    );
    expect(finalize).toBeDefined();
    expect(JSON.parse(String(finalize?.[1]?.body))).toEqual({ versionId: VERSION_ID });
  });

  it("returns the wizard to the form when the create leg fails, touching neither storage nor finalize", async () => {
    renderPage(
      baseRoutes([
        {
          method: "POST",
          url: "/api/v1/ota/bundles",
          status: 409,
          body: {
            title: "Conflict",
            status: 409,
            code: "OTA_BUNDLE_VERSION_TAKEN",
            detail: "bundle version 2.5.0 already exists for the application",
          },
        },
      ]),
    );
    await screen.findByRole("gridcell", { name: "2.4.1" });
    await fillWizardSubmission();

    expect(await screen.findByTestId("wizard-error")).toHaveTextContent(
      "bundle version 2.5.0 already exists for the application",
    );
    // The chain stopped at the first leg: the form is back up for
    // correction, no storage PUT happened, no finalize ran.
    expect(screen.getByTestId("wizard-form")).toBeInTheDocument();
    expect(fetchCalls().filter(([, init]) => init?.method === "PUT")).toHaveLength(0);
    expect(
      fetchCalls().filter(([url, init]) => url.includes("/finalize") && init?.method === "POST"),
    ).toHaveLength(0);
  });

  it("surfaces the storage PUT failure and never finalizes half-stored bytes", async () => {
    renderPage(
      baseRoutes([
        { method: "POST", url: "/api/v1/ota/bundles", status: 201, body: DRAFT },
        { method: "PUT", url: UPLOAD_URL, status: 500 },
      ]),
    );
    await screen.findByRole("gridcell", { name: "2.4.1" });
    await fillWizardSubmission();

    expect(await screen.findByTestId("wizard-error")).toHaveTextContent("Upload failed (500)");
    expect(screen.getByTestId("wizard-form")).toBeInTheDocument();
    // The bytes never landed, so the finalize leg must not fire.
    expect(
      fetchCalls().filter(([url, init]) => url.includes("/finalize") && init?.method === "POST"),
    ).toHaveLength(0);
  });

  it("surfaces the finalize leg failure and returns to the form", async () => {
    renderPage(
      baseRoutes([
        { method: "POST", url: "/api/v1/ota/bundles", status: 201, body: DRAFT },
        { method: "PUT", url: UPLOAD_URL, status: 200 },
        {
          method: "POST",
          url: `/api/v1/ota/bundles/${BUNDLE_ID}/finalize`,
          status: 409,
          body: {
            title: "Conflict",
            status: 409,
            code: "OTA_BUNDLE_STATE_CONFLICT",
            detail: "the bundle is no longer awaiting finalize",
          },
        },
      ]),
    );
    await screen.findByRole("gridcell", { name: "2.4.1" });
    await fillWizardSubmission();

    expect(await screen.findByTestId("wizard-error")).toHaveTextContent(
      "the bundle is no longer awaiting finalize",
    );
    expect(screen.getByTestId("wizard-form")).toBeInTheDocument();
    expect(screen.queryByTestId("wizard-signed")).not.toBeInTheDocument();
  });

  it("issues an app token and shows the value exactly once", async () => {
    renderPage(
      baseRoutes([
        {
          method: "POST",
          url: "/api/v1/ota/app-tokens",
          status: 201,
          body: {
            token: {
              id: TOKEN_ID,
              applicationId: APPLICATION_ID,
              name: "prod-fleet",
              createdBy: ACTOR_ID,
              revoked: false,
              revokedAt: null,
            },
            value: "sdc1.only-once-value",
          },
        },
      ]),
    );
    await screen.findByTestId("ota-app-tokens");

    await user.click(screen.getByTestId("issue-app-token"));
    await user.type(screen.getByTestId("app-token-name"), "prod-fleet");
    await user.click(screen.getByTestId("confirm-issue-app-token"));

    expect(await screen.findByTestId("app-token-once")).toBeInTheDocument();
    expect(screen.getByText("sdc1.only-once-value")).toBeInTheDocument();
    await user.click(screen.getByTestId("app-token-dismiss"));
    await waitFor(() => {
      expect(screen.queryByText("sdc1.only-once-value")).not.toBeInTheDocument();
    });
  });

  it("replaces the once-only value when a second token is issued", async () => {
    renderPage(
      baseRoutes([
        {
          method: "POST",
          url: "/api/v1/ota/app-tokens",
          status: 201,
          sequence: [
            {
              status: 201,
              body: {
                token: {
                  id: TOKEN_ID,
                  applicationId: APPLICATION_ID,
                  name: "prod-fleet",
                  createdBy: ACTOR_ID,
                  revoked: false,
                  revokedAt: null,
                },
                value: "sdc1.first-value",
              },
            },
            {
              status: 201,
              body: {
                token: {
                  id: "70000000-0000-4000-8000-000000000002",
                  applicationId: APPLICATION_ID,
                  name: "canary-build",
                  createdBy: ACTOR_ID,
                  revoked: false,
                  revokedAt: null,
                },
                value: "sdc1.second-value",
              },
            },
          ],
        },
      ]),
    );
    await screen.findByTestId("ota-app-tokens");

    await user.click(screen.getByTestId("issue-app-token"));
    await user.type(screen.getByTestId("app-token-name"), "prod-fleet");
    await user.click(screen.getByTestId("confirm-issue-app-token"));
    expect(await screen.findByText("sdc1.first-value")).toBeInTheDocument();
    await user.click(screen.getByTestId("app-token-dismiss"));
    await waitFor(() => {
      expect(screen.queryByText("sdc1.first-value")).not.toBeInTheDocument();
    });

    // A fresh issue carries its own fresh value — the dialog state
    // replaces, never accumulates or replays the dismissed one.
    await user.click(screen.getByTestId("issue-app-token"));
    await user.type(screen.getByTestId("app-token-name"), "canary-build");
    await user.click(screen.getByTestId("confirm-issue-app-token"));
    expect(await screen.findByText("sdc1.second-value")).toBeInTheDocument();
    expect(screen.queryByText("sdc1.first-value")).not.toBeInTheDocument();
  });

  it("revokes a bundle only after confirmation", async () => {
    renderPage(
      baseRoutes([
        {
          method: "POST",
          url: `/api/v1/ota/bundles/${BUNDLE_ID}/revoke`,
          status: 200,
          body: { ...BUNDLE, status: "REVOKED" },
        },
      ]),
    );
    await screen.findByRole("gridcell", { name: "2.4.1" });

    await user.click(screen.getByTestId(`revoke-bundle-${BUNDLE_ID}`));
    expect(await screen.findByText("Revoke bundle?")).toBeInTheDocument();
    await user.click(screen.getByTestId("confirm-revoke-bundle"));

    await waitFor(() => {
      const revokes = fetchCalls().filter(
        ([url, init]) => url.endsWith(`/ota/bundles/${BUNDLE_ID}/revoke`) && init?.method === "POST",
      );
      expect(revokes).toHaveLength(1);
      expect(JSON.parse(String(revokes[0]?.[1]?.body))).toEqual({ applicationId: APPLICATION_ID });
    });
    expect(await screen.findByText(/revoked — devices now answer no-update/)).toBeInTheDocument();
  });

  it("creates an alert rule from the rule store", async () => {
    renderPage(
      baseRoutes([
        {
          method: "POST",
          url: "/api/v1/ota/alert-rules",
          status: 201,
          body: {
            id: RULE_ID,
            applicationId: APPLICATION_ID,
            otaBundleId: null,
            metric: "OTA_CRASH_FREE_RATE",
            comparator: "LT",
            threshold: 0.995,
            windowMinutes: 15,
            severity: "WARNING",
            createdAt: "2026-10-10T10:00:00Z",
            updatedAt: "2026-10-10T10:00:00Z",
            standingFiring: null,
          },
        },
      ]),
    );
    await screen.findByTestId("ota-alert-rules");

    await user.click(screen.getByTestId("create-alert-rule"));
    const dialog = await screen.findByTestId("alert-rule-dialog");
    // Defaults already watch crash-free < 0.995 over 15m.
    await user.click(within(dialog).getByTestId("rule-submit"));

    await waitFor(() => {
      const posts = fetchCalls().filter(
        ([url, init]) => url.endsWith("/ota/alert-rules") && init?.method === "POST",
      );
      expect(posts).toHaveLength(1);
      expect(JSON.parse(String(posts[0]?.[1]?.body))).toEqual({
        applicationId: APPLICATION_ID,
        metric: "OTA_CRASH_FREE_RATE",
        comparator: "LT",
        threshold: 0.995,
        windowMinutes: 15,
        severity: "WARNING",
      });
    });
  });
});
