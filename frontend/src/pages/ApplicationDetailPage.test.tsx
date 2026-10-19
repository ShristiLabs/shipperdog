/** Application detail behavior (issue #16): metadata shell with the
 * E4/E5 empty states, grant/revoke of shared-application tenant
 * authorizations with audit reasons, the confirmed archive lifecycle,
 * and the audited scope transition surfacing its 409 precondition. */
import { cleanup, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it } from "vitest";
import { Route, Routes } from "react-router-dom";
import ApplicationDetailPage from "./ApplicationDetailPage";
import { installFetchRouter, renderConsole, seedSession } from "../test/harness";

const user = userEvent.setup();

const ORG_ID = "10000000-0000-4000-8000-000000000001";
const ADMIN_ID = "10000000-0000-4000-8000-000000000010";
const TENANT_ACME = "20000000-0000-4000-8000-000000000001";
const TENANT_CONTOSO = "20000000-0000-4000-8000-000000000002";
const APP_ID = "30000000-0000-4000-8000-000000000002";

const SHARED_APP = {
  id: APP_ID,
  organizationId: ORG_ID,
  tenantId: null,
  ownershipScope: "ORGANIZATION",
  name: "Field Service",
  description: "Shared field service app",
  platforms: ["IOS", "ANDROID"],
  bundleId: "com.acme.fieldservice",
  packageName: "com.acme.fieldservice.android",
  status: "ACTIVE",
  otaEnabled: false,
  createdAt: "2026-10-01T10:00:00Z",
  updatedAt: "2026-10-02T10:00:00Z",
};

const GRANT_ACME = {
  applicationId: APP_ID,
  tenantId: TENANT_ACME,
  status: "ACTIVE",
  grantedBy: ADMIN_ID,
  grantedAt: "2026-10-02T11:00:00Z",
  revokedBy: null,
  revokedAt: null,
};

function detailRoutes(extra: Parameters<typeof installFetchRouter>[0] = []) {
  const sharedApp = SHARED_APP;
  return [
    ...directoryRoutes(),
    {
      method: "GET",
      url: `/api/v1/applications/${APP_ID}`,
      status: 200,
      body: sharedApp,
    },
    {
      method: "GET",
      url: `/api/v1/applications/${APP_ID}/tenants`,
      status: 200,
      body: { items: [GRANT_ACME] },
    },
    ...extra,
  ];
}

function directoryRoutes(): Parameters<typeof installFetchRouter>[0] {
  return [
    {
      method: "GET",
      url: "/api/v1/tenants",
      status: 200,
      body: {
        items: [tenant(TENANT_ACME, "Acme Bank"), tenant(TENANT_CONTOSO, "Contoso")],
      },
    },
    {
      method: "GET",
      url: `/api/v1/organizations/${ORG_ID}/members`,
      status: 200,
      body: {
        items: [
          {
            userId: ADMIN_ID,
            email: "dev@shipperdog.local",
            fullName: "Dev Admin",
            role: "ORG_ADMIN",
            createdAt: "2026-10-01T10:00:00Z",
          },
        ],
      },
    },
  ];
}

function tenant(id: string, name: string) {
  return {
    id,
    organizationId: ORG_ID,
    name,
    externalId: `crm-${name.toLowerCase().replace(" ", "-")}`,
    status: "ACTIVE",
    createdAt: "2026-10-01T10:00:00Z",
    updatedAt: "2026-10-01T10:00:00Z",
  };
}

function fetchCalls(): Array<[string, RequestInit | undefined]> {
  const mock = fetch as unknown as { mock: { calls: Array<[string, RequestInit]> } };
  return mock.mock.calls;
}

function renderDetail(routes: Parameters<typeof installFetchRouter>[0]) {
  installFetchRouter(routes);
  seedSession();
  renderConsole(
    <Routes>
      <Route path="/applications/:applicationId" element={<ApplicationDetailPage />} />
    </Routes>,
    { route: `/applications/${APP_ID}` },
  );
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("ApplicationDetailPage", () => {
  it("renders metadata, resolved grant names, and the E4/E5 empty states", async () => {
    renderDetail(detailRoutes());

    expect(await screen.findByTestId("application-title")).toHaveTextContent("Field Service");
    expect(screen.getByText("Organization-shared")).toBeInTheDocument();
    expect(screen.getByTestId("authorization-section")).toBeInTheDocument();
    // Tenant and grantor names resolve through the organization
    // directory; grantedBy has no user-name port on the API.
    expect(await screen.findByRole("gridcell", { name: "Acme Bank" })).toBeInTheDocument();
    expect(screen.getByRole("gridcell", { name: "Dev Admin" })).toBeInTheDocument();
    expect(screen.getByTestId("versions-empty")).toHaveTextContent("No versions yet");
    expect(screen.getByTestId("artifacts-empty")).toHaveTextContent("No artifacts yet");
  });

  it("grants a tenant authorization with the audit reason", async () => {
    renderDetail(
      detailRoutes([
        {
          method: "POST",
          url: `/api/v1/applications/${APP_ID}/tenants/${TENANT_CONTOSO}`,
          status: 201,
          body: { ...GRANT_ACME, tenantId: TENANT_CONTOSO },
        },
      ]),
    );
    await screen.findByTestId("authorization-grid");

    await user.click(screen.getByTestId("grant-button"));
    const picker = screen.getByTestId("grant-tenant-picker");
    await user.click(within(picker).getByRole("combobox"));
    // Already-authorized Acme Bank is not offered.
    await user.click(await screen.findByRole("option", { name: "Contoso" }));
    await user.type(screen.getByTestId("grant-reason"), "Pilot rollout");
    await user.click(screen.getByTestId("grant-submit"));

    await waitFor(() => {
      const posts = fetchCalls().filter(
        ([url, init]) =>
          url.includes(`/tenants/${TENANT_CONTOSO}`) && init?.method === "POST",
      );
      expect(posts).toHaveLength(1);
      expect(JSON.parse(String(posts[0]?.[1]?.body))).toEqual({ reason: "Pilot rollout" });
    });
    expect(await screen.findByText("Tenant authorized")).toBeInTheDocument();
  });

  it("revokes only after confirmation, sending the audit reason", async () => {
    renderDetail(
      detailRoutes([
        {
          method: "DELETE",
          url: `/api/v1/applications/${APP_ID}/tenants/${TENANT_ACME}`,
          status: 204,
        },
      ]),
    );
    await screen.findByTestId("authorization-grid");
    await screen.findByRole("gridcell", { name: "Acme Bank" });

    await user.click(screen.getByTestId(`revoke-${TENANT_ACME}`));
    expect(await screen.findByText("Revoke tenant authorization?")).toBeInTheDocument();
    await user.type(screen.getByTestId("revoke-reason"), "Offboarding");
    await user.click(screen.getByTestId("confirm-revoke"));

    await waitFor(() => {
      const deletes = fetchCalls().filter(
        ([url, init]) =>
          url.includes(`/tenants/${TENANT_ACME}`) && init?.method === "DELETE",
      );
      expect(deletes).toHaveLength(1);
      expect(JSON.parse(String(deletes[0]?.[1]?.body))).toEqual({ reason: "Offboarding" });
    });
    expect(await screen.findByText("Tenant de-authorized")).toBeInTheDocument();
  });

  it("archives only after a confirmation naming the application", async () => {
    renderDetail(
      detailRoutes([
        {
          method: "PATCH",
          url: `/api/v1/applications/${APP_ID}`,
          status: 200,
          body: { ...SHARED_APP, status: "ARCHIVED" },
        },
      ]),
    );
    await screen.findByTestId("application-title");

    await user.click(screen.getByTestId("archive-application"));
    expect(await screen.findByText(/Confirm archiving of/i, { exact: false })).toBeInTheDocument();
    await user.click(screen.getByTestId("confirm-archive"));

    await waitFor(() => {
      const patches = fetchCalls().filter(
        ([url, init]) => url.includes(`/api/v1/applications/${APP_ID}`) && init?.method === "PATCH",
      );
      expect(patches).toHaveLength(1);
      expect(JSON.parse(String(patches[0]?.[1]?.body))).toEqual({ status: "ARCHIVED" });
    });
    expect(await screen.findByText(/archived \(reversible\)/i)).toBeInTheDocument();
  });

  it("sends the audited scope transition and surfaces the 409 precondition readably", async () => {
    renderDetail(
      detailRoutes([
        {
          method: "PATCH",
          url: `/api/v1/applications/${APP_ID}`,
          status: 409,
          body: {
            title: "Conflict",
            status: 409,
            code: "APPLICATION_AUTHORIZED_TENANTS_PRESENT",
            detail:
              "The application has authorized tenants; de-authorize them before converting ownership.",
          },
        },
      ]),
    );
    await screen.findByTestId("application-title");

    await user.click(screen.getByTestId("edit-application"));
    const scopeControl = await screen.findByTestId("app-scope");
    await user.click(within(scopeControl).getByRole("combobox"));
    await user.click(await screen.findByRole("option", { name: "Tenant-owned" }));
    const ownerPicker = screen.getByTestId("app-owner-tenant");
    await user.click(within(ownerPicker).getByRole("combobox"));
    await user.click(await screen.findByRole("option", { name: "Contoso" }));
    await user.click(screen.getByTestId("application-submit"));

    await waitFor(() => {
      const patches = fetchCalls().filter(
        ([url, init]) => url.includes(`/api/v1/applications/${APP_ID}`) && init?.method === "PATCH",
      );
      expect(patches).toHaveLength(1);
      expect(JSON.parse(String(patches[0]?.[1]?.body))).toMatchObject({
        name: "Field Service",
        platforms: ["IOS", "ANDROID"],
        bundleId: "com.acme.fieldservice",
        packageName: "com.acme.fieldservice.android",
        ownershipScope: "TENANT",
        tenantId: TENANT_CONTOSO,
      });
    });
    // The problem+json code and registry detail render through the
    // toast convention (correlation joinable server-side).
    expect(
      await screen.findByText(/APPLICATION_AUTHORIZED_TENANTS_PRESENT/),
    ).toBeInTheDocument();
    expect(await screen.findByText(/de-authorize them before converting/i)).toBeInTheDocument();
  });

  it("renders the concealed-404 envelope for an application it cannot see", async () => {
    installFetchRouter([
      ...directoryRoutes(),
      {
        method: "GET",
        url: `/api/v1/applications/${APP_ID}`,
        status: 404,
        body: {
          title: "Not Found",
          status: 404,
          code: "APPLICATION_NOT_FOUND",
          detail: "Resource not found.",
        },
      },
    ]);
    seedSession();
    renderConsole(
      <Routes>
        <Route path="/applications/:applicationId" element={<ApplicationDetailPage />} />
      </Routes>,
      { route: `/applications/${APP_ID}` },
    );

    const alert = await screen.findByTestId("application-error");
    expect(alert).toHaveTextContent("Failed to load the application.");
    // The screen degrades to the envelope + way back; nothing else renders.
    expect(screen.queryByTestId("application-title")).not.toBeInTheDocument();
    expect(screen.queryByTestId("authorization-section")).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: /applications/i })).toHaveAttribute("href", "/applications");
  });

  it("edits metadata without the audited scope fields when the scope is unchanged", async () => {
    renderDetail(
      detailRoutes([
        {
          method: "PATCH",
          url: `/api/v1/applications/${APP_ID}`,
          status: 200,
          body: { ...SHARED_APP, name: "Field Service 2" },
        },
      ]),
    );
    await screen.findByTestId("application-title");

    await user.click(screen.getByTestId("edit-application"));
    const nameInput = await screen.findByTestId("app-name");
    await user.clear(nameInput);
    await user.type(nameInput, "Field Service 2");
    await user.click(screen.getByTestId("application-submit"));

    await waitFor(() => {
      const patches = fetchCalls().filter(
        ([url, init]) =>
          url.includes(`/api/v1/applications/${APP_ID}`) && init?.method === "PATCH",
      );
      expect(patches).toHaveLength(1);
      const body = JSON.parse(String(patches[0]?.[1]?.body));
      expect(body.name).toBe("Field Service 2");
      // The audited transition rides along only when actually requested:
      // an unchanged scope must not emit the fields.
      expect(body).not.toHaveProperty("ownershipScope");
      expect(body).not.toHaveProperty("tenantId");
    });
    expect(await screen.findByText("Application Field Service 2 updated")).toBeInTheDocument();
  });

  // The OTA block (S16-15, issue #177): the signing key's copyable
  // pair and the OTA-enable toggle's PATCH.
  it("shows the ACTIVE signing key with its keyId and copy affordances (S16-15)", async () => {
    renderDetail(
      detailRoutes([
        {
          method: "GET",
          url: "/api/v1/ota/signing-keys",
          status: 200,
          body: {
            applicationId: APP_ID,
            keyId: "k1k1k1k1k1k1k1k1k1k1k1k1k1k1k1k1",
            publicKey: "MCowBQYDK2VwAyEAGb9ECWmEzf6FQbrBZ9w7lshQhqowtrbLDFw4rXAxZuE=",
            algorithm: "ED25519",
            createdAt: "2026-10-05T10:00:00Z",
          },
        },
      ]),
    );

    expect(await screen.findByTestId("ota-signing-key")).toBeInTheDocument();
    expect(screen.getByText(/keyId k1k1k1k1k1k1k1k1k1k1k1k1k1k1k1k1/)).toBeInTheDocument();
    expect(screen.getByTestId("ota-public-key")).toHaveTextContent("MCowBQYDK2VwAyEAGb9");
    expect(screen.getByTestId("copy-ota-key-id")).toBeInTheDocument();
    expect(screen.getByTestId("copy-ota-public-key")).toBeInTheDocument();
  });

  it("explains the missing key honestly (a finalize-time act, never a read side effect)", async () => {
    renderDetail(
      detailRoutes([
        {
          method: "GET",
          url: "/api/v1/ota/signing-keys",
          status: 404,
          body: { title: "Not Found", status: 404, code: "OTA_SIGNING_KEY_NOT_FOUND" },
        },
      ]),
    );
    await screen.findByTestId("ota-section");
    expect(await screen.findByTestId("ota-key-empty")).toHaveTextContent("No signing key yet");
  });

  it("flips the OTA-enable toggle and PATCHes otaEnabled (S16-15)", async () => {
    renderDetail(
      detailRoutes([
        {
          method: "GET",
          url: "/api/v1/ota/signing-keys",
          status: 404,
          body: { title: "Not Found", status: 404, code: "OTA_SIGNING_KEY_NOT_FOUND" },
        },
        {
          method: "PATCH",
          url: `/api/v1/applications/${APP_ID}`,
          status: 200,
          body: { ...SHARED_APP, otaEnabled: true },
        },
      ]),
    );
    await screen.findByTestId("ota-section");
    expect(screen.getByText(/OTA disabled/)).toBeInTheDocument();

    await user.click(screen.getByRole("switch"));

    await waitFor(() => {
      const patches = fetchCalls().filter(
        ([url, init]) => url.includes(`/api/v1/applications/${APP_ID}`) && init?.method === "PATCH",
      );
      expect(patches).toHaveLength(1);
      expect(JSON.parse(String(patches[0]?.[1]?.body))).toEqual({ otaEnabled: true });
    });
    expect(await screen.findByText("OTA enabled — bundles can be authored and devices can check")).toBeInTheDocument();
  });
});
