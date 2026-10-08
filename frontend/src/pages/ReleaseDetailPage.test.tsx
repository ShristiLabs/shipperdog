/** Release dashboard behavior (issue #59, PRD 32): the block layout
 * with live data per subsystem, honest empty states while E10 lands,
 * the transition actions (legal edges; mandatory-reason dialog for
 * FAILED/ROLLED_BACK), the timeline with actors/timestamps/reasons,
 * the frozen-manifest viewer with YAML render and download, the
 * not-frozen 404 state, and the manifest diff surfacing pin identity. */
import { cleanup, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it } from "vitest";
import { Route, Routes } from "react-router-dom";
import ReleaseDetailPage from "./ReleaseDetailPage";
import { installFetchRouter, renderConsole, seedSession } from "../test/harness";
import type { ReleaseDetail, ReleaseManifest } from "../api/releases";

const user = userEvent.setup();

const ORG_ID = "10000000-0000-4000-8000-000000000001";
const TENANT_ACME = "20000000-0000-4000-8000-000000000001";
const ACTOR_ID = "10000000-0000-4000-8000-000000000010";
const APPLICATION_ID = "30000000-0000-4000-8000-000000000001";
const VERSION_ID = "40000000-0000-4000-8000-000000000001";
const RELEASE_ID = "50000000-0000-4000-8000-000000000001";
const OTHER_RELEASE_ID = "50000000-0000-4000-8000-000000000002";
const ARTIFACT_ID = "60000000-0000-4000-8000-000000000001";

const DETAIL_DRAFT: ReleaseDetail = {
  id: RELEASE_ID,
  releaseRef: "R-101",
  applicationId: APPLICATION_ID,
  versionId: VERSION_ID,
  status: "DRAFT",
  ring: "engineering",
  ringName: "Engineering",
  environmentId: null,
  environment: null,
  environmentName: null,
  artifacts: [{ platform: "ANDROID", artifactId: ARTIFACT_ID }],
  targets: [{ tenantId: TENANT_ACME, ring: "engineering", testerGroupIds: ["g-1"] }],
  createdBy: ACTOR_ID,
  targetCount: 1,
  createdAt: "2026-10-01T10:00:00Z",
  updatedAt: "2026-10-01T10:00:00Z",
};

const DETAIL_ACTIVE: ReleaseDetail = { ...DETAIL_DRAFT, status: "ACTIVE" };

const MANIFEST: ReleaseManifest = {
  releaseId: RELEASE_ID,
  releaseRef: "R-101",
  applicationId: APPLICATION_ID,
  versionId: VERSION_ID,
  sha256: "63061b4ba1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1",
  frozenAt: "2026-10-02T12:00:00Z",
  frozenBy: ACTOR_ID,
  manifest: {
    release: {
      releaseId: RELEASE_ID,
      releaseRef: "R-101",
      applicationId: APPLICATION_ID,
      applicationName: "Banking",
      versionId: VERSION_ID,
      versionName: "7.12.0",
      organizationId: ORG_ID,
    },
    artifacts: [
      {
        platform: "ANDROID",
        artifactId: ARTIFACT_ID,
        fileName: "banking-7.12.0.apk",
        buildNumber: 421,
        sha256: "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1",
        sizeBytes: 1048576,
        uploadedAt: "2026-10-01T09:00:00Z",
      },
    ],
    targets: [{ tenantId: TENANT_ACME, testerGroupIds: ["g-1"] }],
    ring: { code: "engineering", name: "Engineering", position: 0 },
    environment: null,
    rollout: null,
    configuration: null,
    approval: { required: [] },
  },
};

const HISTORY = {
  id: RELEASE_ID,
  releaseRef: "R-101",
  applicationId: APPLICATION_ID,
  versionId: VERSION_ID,
  status: "ACTIVE",
  createdAt: "2026-10-01T10:00:00Z",
  updatedAt: "2026-10-02T13:00:00Z",
  transitions: [
    {
      from: null,
      to: "DRAFT",
      reason: null,
      actorId: ACTOR_ID,
      occurredAt: "2026-10-01T10:00:00Z",
    },
    {
      from: "ACTIVE",
      to: "ROLLED_BACK",
      reason: "crash spike in the field",
      actorId: ACTOR_ID,
      occurredAt: "2026-10-02T13:00:00Z",
    },
  ],
};

const WORKFLOW_ID = "70000000-0000-4000-8000-000000000001";

/** The live round of the PRD FR-016 topology — QA signed, the parallel
 * group holding a pending Security sibling beside a condition-skipped
 * Compliance sibling, an ANY group waiting — beside the rejected
 * round 1 it reworked from. */
const APPROVAL_STATE = {
  releaseId: RELEASE_ID,
  releaseRef: "R-101",
  applicationId: APPLICATION_ID,
  releaseStatus: "APPROVAL_PENDING",
  currentRoundNumber: 2,
  currentRoundStatus: "PENDING",
  rounds: [
    {
      roundNumber: 1,
      status: "REJECTED",
      workflowId: WORKFLOW_ID,
      workflowName: "enterprise",
      createdBy: ACTOR_ID,
      createdAt: "2026-10-01T11:00:00Z",
      steps: [
        {
          position: 1,
          approvalType: "QA_ENGINEER",
          tenantId: null,
          groupPosition: 1,
          groupMode: "ALL",
          status: "REJECTED",
          approverId: ACTOR_ID,
          approverRole: "QA_ENGINEER",
          reason: "flaky test suite",
          decidedAt: "2026-10-01T11:30:00Z",
        },
      ],
    },
    {
      roundNumber: 2,
      status: "PENDING",
      workflowId: WORKFLOW_ID,
      workflowName: "enterprise",
      createdBy: ACTOR_ID,
      createdAt: "2026-10-01T12:00:00Z",
      steps: [
        {
          position: 1,
          approvalType: "QA_ENGINEER",
          tenantId: null,
          groupPosition: 1,
          groupMode: "ALL",
          status: "APPROVED",
          approverId: ACTOR_ID,
          approverRole: "QA_ENGINEER",
          reason: null,
          decidedAt: "2026-10-01T12:10:00Z",
        },
        {
          position: 2,
          approvalType: "SECURITY_REVIEWER",
          tenantId: null,
          groupPosition: 2,
          groupMode: "ALL",
          status: "PENDING",
          approverId: null,
          approverRole: null,
          reason: null,
          decidedAt: null,
        },
        {
          position: 3,
          approvalType: "COMPLIANCE_REVIEWER",
          tenantId: null,
          groupPosition: 2,
          groupMode: "ALL",
          status: "SKIPPED",
          approverId: null,
          approverRole: null,
          reason: "condition ENVIRONMENT_EQUALS(production) unmet (environment=qa)",
          decidedAt: "2026-10-01T12:00:00Z",
        },
        {
          position: 4,
          approvalType: "APP_OWNER",
          tenantId: null,
          groupPosition: 3,
          groupMode: "ANY",
          status: "PENDING",
          approverId: null,
          approverRole: null,
          reason: null,
          decidedAt: null,
        },
      ],
    },
  ],
};

/** The rejected moment: the rework restart is offerable exactly here. */
const APPROVAL_STATE_REJECTED = {
  ...APPROVAL_STATE,
  currentRoundStatus: "REJECTED",
  rounds: APPROVAL_STATE.rounds.map((round) =>
    round.roundNumber === 2
      ? {
          ...round,
          status: "REJECTED",
          steps: round.steps.map((step) =>
            step.position === 2
              ? {
                  ...step,
                  status: "REJECTED",
                  approverId: ACTOR_ID,
                  approverRole: "SECURITY_REVIEWER",
                  reason: "CVE in a pinned dependency",
                  decidedAt: "2026-10-01T12:20:00Z",
                }
              : step,
          ),
        }
      : round,
  ),
};

function fetchCalls(): Array<[string, RequestInit | undefined]> {
  const mock = fetch as unknown as { mock: { calls: Array<[string, RequestInit]> } };
  return mock.mock.calls;
}

function directoryRoutes(): Parameters<typeof installFetchRouter>[0] {
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
            platforms: ["IOS", "ANDROID"],
            bundleId: "com.acme.banking",
            packageName: "com.acme.banking",
            status: "ACTIVE",
            createdAt: "2026-10-01T10:00:00Z",
            updatedAt: "2026-10-01T10:00:00Z",
          },
        ],
      },
    },
    {
      method: "GET",
      url: "/api/v1/tenants",
      status: 200,
      body: {
        items: [
          {
            id: TENANT_ACME,
            organizationId: ORG_ID,
            name: "Acme",
            externalId: "demo-acme",
            status: "ACTIVE",
            createdAt: "2026-10-01T10:00:00Z",
            updatedAt: "2026-10-01T10:00:00Z",
          },
        ],
      },
    },
    {
      method: "GET",
      url: `/api/v1/organizations/${ORG_ID}/members`,
      status: 200,
      body: {
        items: [
          {
            userId: ACTOR_ID,
            email: "dev@shipperdog.local",
            fullName: "ShipperDog Dev Admin",
            role: "ORG_ADMIN",
            status: "ACTIVE",
            createdAt: "2026-10-01T10:00:00Z",
          },
        ],
      },
    },
    {
      method: "GET",
      url: `/api/v1/applications/${APPLICATION_ID}/versions/${VERSION_ID}`,
      status: 200,
      body: {
        id: VERSION_ID,
        applicationId: APPLICATION_ID,
        versionName: "7.12.0",
        status: "ACTIVE",
        tags: [],
        createdAt: "2026-10-01T10:00:00Z",
        updatedAt: "2026-10-01T10:00:00Z",
        artifactsByPlatform: {},
      },
    },
  ];
}

function notFound(code = "RELEASE_NOT_FOUND") {
  return { title: "Not Found", status: 404, code, detail: "Resource not found." };
}

function renderDetail(detail = DETAIL_DRAFT, extraRoutes: Parameters<typeof installFetchRouter>[0] = []) {
  // The fetch router matches in order, so overrides ride ahead of the
  // defaults (the manifest stub below is the honest 404 a pre-APPROVED
  // release answers).
  installFetchRouter([
    ...directoryRoutes(),
    {
      method: "GET",
      url: `/api/v1/releases/${RELEASE_ID}`,
      status: 200,
      body: detail,
    },
    { method: "GET", url: `/api/v1/releases/${RELEASE_ID}/history`, status: 200, body: HISTORY },
    ...extraRoutes,
    {
      method: "GET",
      url: `/api/v1/releases/${RELEASE_ID}/manifest`,
      status: 404,
      body: notFound("RELEASE_MANIFEST_NOT_FOUND"),
    },
  ]);
  seedSession();
  renderConsole(
    <Routes>
      <Route
        path="/releases/:applicationId/:releaseId"
        element={<ReleaseDetailPage />}
      />
    </Routes>,
    { route: `/releases/${APPLICATION_ID}/${RELEASE_ID}` },
  );
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("ReleaseDetailPage", () => {
  it("renders the §32 blocks: artifacts, gate/approval empty states, distribution, timeline", async () => {
    renderDetail();

    // The title resolves the application and version names once the
    // directory walks land (raw ids are the pre-resolution truth).
    await waitFor(() =>
      expect(screen.getByTestId("release-title")).toHaveTextContent("R-101 — Banking 7.12.0"),
    );
    expect(screen.getByTestId("release-status-chip")).toHaveTextContent("Draft");

    // Artifacts block: the creation slot with its honest pre-freeze note.
    expect(screen.getByTestId("artifacts-section")).toHaveTextContent(ARTIFACT_ID);
    expect(screen.getByTestId("artifacts-section")).toHaveTextContent("freeze at APPROVED");

    // E10 subsystems render their empty states, not dead sections.
    expect(screen.getByTestId("gates-empty")).toBeInTheDocument();
    // The approvals block resolves its honest 404 (no bound policy)
    // asynchronously — the live state loads with the release.
    expect(await screen.findByTestId("approvals-empty")).toBeInTheDocument();

    // Distribution: the starting ring and the resolved tenant target.
    expect(screen.getByTestId("distribution-section")).toHaveTextContent("Engineering");
    expect(screen.getByTestId("distribution-section")).toHaveTextContent("Acme");
    expect(screen.getByTestId("distribution-section")).toHaveTextContent("1 tester group");

    // Timeline: actor resolved through the roster, reason rendered.
    expect(screen.getByTestId("timeline-section")).toHaveTextContent("Created as Draft");
    expect(screen.getByTestId("timeline-section")).toHaveTextContent("ShipperDog Dev Admin");
    expect(screen.getByTestId("timeline-section")).toHaveTextContent("Active → Rolled back");
    expect(screen.getByTestId("timeline-section")).toHaveTextContent("crash spike in the field");

    // Manifest: the honest 404 empty state before APPROVED.
    expect(screen.getByTestId("manifest-not-frozen")).toBeInTheDocument();
  });

  it("offers only the legal edges and performs a transition", async () => {
    renderDetail(DETAIL_DRAFT, [
      {
        method: "POST",
        url: `/api/v1/releases/${RELEASE_ID}/transitions`,
        status: 200,
        body: { ...DETAIL_DRAFT, status: "VALIDATING" },
      },
    ]);

    expect(await screen.findByTestId("lifecycle-section"));
    expect(screen.getByTestId("transition-VALIDATING")).toBeInTheDocument();
    expect(screen.queryByTestId("transition-READY")).not.toBeInTheDocument();
    expect(screen.queryByTestId("transition-FAILED")).not.toBeInTheDocument();

    await user.click(screen.getByTestId("transition-VALIDATING"));

    await waitFor(() => {
      const calls = fetchCalls().filter(([url]) => url.includes("/transitions"));
      expect(calls).toHaveLength(1);
      expect(JSON.parse(String(calls[0]?.[1]?.body))).toEqual({
        applicationId: APPLICATION_ID,
        to: "VALIDATING",
      });
    });
  });

  it("requires a reason on the ROLLED_BACK edge and sends it", async () => {
    renderDetail(DETAIL_ACTIVE, [
      {
        method: "POST",
        url: `/api/v1/releases/${RELEASE_ID}/transitions`,
        status: 200,
        body: { ...DETAIL_ACTIVE, status: "ROLLED_BACK" },
      },
    ]);

    expect(await screen.findByTestId("transition-ROLLED_BACK")).toBeInTheDocument();
    await user.click(screen.getByTestId("transition-ROLLED_BACK"));

    // Empty reason blocks the submit (the server's 422 mirrored client-side).
    expect(screen.getByTestId("confirm-transition")).toBeDisabled();
    await user.type(screen.getByTestId("transition-reason"), "pulled after incident");
    expect(screen.getByTestId("confirm-transition")).toBeEnabled();
    await user.click(screen.getByTestId("confirm-transition"));

    await waitFor(() => {
      const calls = fetchCalls().filter(([url]) => url.includes("/transitions"));
      expect(calls).toHaveLength(1);
      expect(JSON.parse(String(calls[0]?.[1]?.body))).toEqual({
        applicationId: APPLICATION_ID,
        to: "ROLLED_BACK",
        reason: "pulled after incident",
      });
    });
  });

  it("renders the frozen manifest with document facts, YAML, and download", async () => {
    renderDetail(DETAIL_ACTIVE, [
      {
        method: "GET",
        url: `/api/v1/releases/${RELEASE_ID}/manifest`,
        status: 200,
        body: MANIFEST,
      },
    ]);

    expect(await screen.findByTestId("manifest-sha")).toHaveTextContent("63061b4ba1a1...");
    // The artifact pin surfaces its content identity (the #57 follow-up).
    expect(screen.getByTestId("manifest-document")).toHaveTextContent("build 421");
    expect(screen.getByTestId("manifest-document")).toHaveTextContent("banking-7.12.0.apk");
    expect(screen.getByTestId("manifest-document")).toHaveTextContent("1.0 MB");
    // The artifacts block flips to the frozen pin view.
    expect(screen.getByTestId("artifacts-section")).toHaveTextContent("Frozen");
    await waitFor(() =>
      expect(screen.getByTestId("manifest-yaml").textContent).toContain("R-101"),
    );
    expect(screen.getByTestId("manifest-download")).toBeInTheDocument();
  });

  it("shows the manifest panel error only for real failures", async () => {
    renderDetail(DETAIL_ACTIVE, [
      {
        method: "GET",
        url: `/api/v1/releases/${RELEASE_ID}/manifest`,
        status: 500,
        body: { title: "Internal Server Error", status: 500, code: "RELEASE_MANIFEST_CORRUPT" },
      },
    ]);

    await screen.findByText("Failed to load the manifest.", { selector: "div" });
    expect(screen.queryByTestId("manifest-not-frozen")).not.toBeInTheDocument();
  });

  it("renders the manifest diff with per-platform pin identity", async () => {
    renderDetail(DETAIL_ACTIVE, [
      {
        method: "GET",
        url: `/api/v1/releases/${RELEASE_ID}/manifest`,
        status: 200,
        body: MANIFEST,
      },
      { method: "GET", url: "/api/v1/releases", status: 200, body: { items: [
        { ...DETAIL_ACTIVE, releaseRef: "R-100" },
        { ...DETAIL_ACTIVE, id: OTHER_RELEASE_ID, releaseRef: "R-102", status: "APPROVED" },
      ] } },
      {
        method: "GET",
        url: `/api/v1/releases/${RELEASE_ID}/manifest/diff`,
        status: 200,
        body: {
          from: { releaseId: RELEASE_ID, releaseRef: "R-101", sha256: "aa", frozenAt: "2026-10-02T12:00:00Z" },
          to: { releaseId: OTHER_RELEASE_ID, releaseRef: "R-102", sha256: "bb", frozenAt: "2026-10-03T12:00:00Z" },
          artifacts: {
            ANDROID: {
              change: "CHANGED",
              from: { buildNumber: 421, sha256: "a1a1a1a1a1a1", sizeBytes: 1048576, fileName: "banking-7.12.0.apk" },
              to: { buildNumber: 422, sha256: "b2b2b2b2b2b2", sizeBytes: 1048577, fileName: "banking-7.12.1.apk" },
            },
          },
          targets: { added: [TENANT_ACME], removed: [], groupsChanged: {} },
          ring: { from: "engineering", to: "qa" },
          environment: null,
          rollout: null,
          configurationChanges: ["featureFlags"],
        },
      },
    ]);

    await screen.findByTestId("manifest-sha");
    await user.click(screen.getByTestId("compare-button"));
    const picker = screen.getByTestId("compare-picker");
    await user.click(within(picker).getByRole("combobox"));
    await user.click(await screen.findByRole("option", { name: /R-102/ }));
    await user.click(screen.getByTestId("compare-run"));

    const result = await screen.findByTestId("compare-result");
    // Both sides of the CHANGED pin are distinguishable by their facts.
    expect(result).toHaveTextContent("CHANGED");
    expect(result).toHaveTextContent("build 421");
    expect(result).toHaveTextContent("build 422");
    expect(result).toHaveTextContent("Acme");
    expect(result).toHaveTextContent("engineering → qa");
    expect(result).toHaveTextContent("featureFlags");
  });

  it("renders the honest error state for an unknown release", async () => {
    installFetchRouter([
      ...directoryRoutes(),
      {
        method: "GET",
        url: `/api/v1/releases/${RELEASE_ID}`,
        status: 404,
        body: notFound(),
      },
    ]);
    seedSession();
    renderConsole(
      <Routes>
        <Route path="/releases/:applicationId/:releaseId" element={<ReleaseDetailPage />} />
      </Routes>,
      { route: `/releases/${APPLICATION_ID}/${RELEASE_ID}` },
    );

    expect(await screen.findByTestId("release-error")).toBeInTheDocument();
  });

  it("renders the environment chip, frozen configuration, and required approvers", async () => {
    renderDetail(
      {
        ...DETAIL_ACTIVE,
        environmentId: "10000000-0000-4000-8000-000000000031",
        environment: "qa",
        environmentName: "QA",
      },
      [
        {
          method: "GET",
          url: `/api/v1/releases/${RELEASE_ID}/manifest`,
          status: 200,
          body: {
            ...MANIFEST,
            manifest: {
              ...MANIFEST.manifest,
              environment: "qa",
              configuration: {
                configVersion: 2,
                featureFlags: { endpoint: "https://qa.example", darkMode: "true" },
                tenantConfig: null,
              },
              approval: { required: ["QA", "SECURITY"] },
            },
          },
        },
      ],
    );

    // The distribution block names the targeted environment (S9-04).
    expect(await screen.findByTestId("distribution-section")).toHaveTextContent("QA");
    const document = await screen.findByTestId("manifest-document");
    expect(document).toHaveTextContent("environment qa");
    // The frozen configuration block resolves its values (S9-04's
    // resolved snapshot, not pointers).
    expect(document).toHaveTextContent("version 2 · 2 config keys");
    // The frozen required-approver set renders once a policy exists.
    expect(await screen.findByTestId("approvals-empty")).toHaveTextContent("QA, SECURITY");
    // The timeline renders the crossing's timestamp, not just the row.
    expect(screen.getByTestId("timeline-section")).toHaveTextContent(
      new Date("2026-10-02T13:00:00Z").toLocaleString(),
    );
  });

  it("downloads the manifest YAML as a blob-named file", async () => {
    renderDetail(DETAIL_ACTIVE, [
      {
        method: "GET",
        url: `/api/v1/releases/${RELEASE_ID}/manifest`,
        status: 200,
        body: MANIFEST,
      },
    ]);
    await screen.findByTestId("manifest-sha");
    await waitFor(() =>
      expect(screen.getByTestId("manifest-yaml").textContent).toContain("R-101"),
    );

    const createdUrls: string[] = [];
    const revoked: string[] = [];
    const objectUrl = vi.spyOn(URL, "createObjectURL").mockImplementation((blob) => {
      expect((blob as Blob).type).toBe("application/yaml");
      createdUrls.push("blob");
      return "blob:manifest";
    });
    const revoke = vi.spyOn(URL, "revokeObjectURL").mockImplementation((url) => {
      revoked.push(url);
    });
    const clicked: string[] = [];
    const anchorClick = vi
      .spyOn(HTMLAnchorElement.prototype, "click")
      .mockImplementation(function (this: HTMLAnchorElement) {
        clicked.push(this.download);
      });

    await user.click(screen.getByTestId("manifest-download"));

    expect(createdUrls).toHaveLength(1);
    expect(clicked).toEqual(["R-101-manifest.yaml"]);
    expect(revoked).toEqual(["blob:manifest"]);
    objectUrl.mockRestore();
    revoke.mockRestore();
    anchorClick.mockRestore();
  });

  it("surfaces the server's rejection when a crossing is illegal", async () => {
    renderDetail(DETAIL_DRAFT, [
      {
        method: "POST",
        url: `/api/v1/releases/${RELEASE_ID}/transitions`,
        status: 409,
        body: {
          title: "Conflict",
          status: 409,
          code: "RELEASE_TRANSITION_INVALID",
          detail: "the crossing is not legal",
          errors: { current: "DRAFT", attempted: "COMPLETED" },
        },
      },
    ]);

    expect(await screen.findByTestId("transition-VALIDATING")).toBeInTheDocument();
    await user.click(screen.getByTestId("transition-VALIDATING"));

    // The toast convention: the problem+json code and detail, not a
    // generic message (the server stays the authority on transitions).
    expect(await screen.findByText(/RELEASE_TRANSITION_INVALID/)).toBeInTheDocument();
  });

  it("renders the live approval state: group modes, waiting-on, skips, and the earlier rejected round (S10-02)", async () => {
    renderDetail(
      { ...DETAIL_ACTIVE, status: "APPROVAL_PENDING" },
      [
        {
          method: "GET",
          url: `/api/v1/releases/${RELEASE_ID}/manifest`,
          status: 404,
          body: notFound("RELEASE_MANIFEST_NOT_FOUND"),
        },
        {
          method: "GET",
          url: `/api/v1/releases/${RELEASE_ID}/approvals`,
          status: 200,
          body: APPROVAL_STATE,
        },
      ],
    );

    const section = await screen.findByTestId("approvals-section");
    // The live round's identity: number, status, the definition's name.
    await waitFor(() => expect(section).toHaveTextContent("round 2 · pending · enterprise"));
    // AC5: the waiting-on list the evaluator derives — the active ALL
    // group's pending sibling (its sibling was condition-skipped).
    expect(screen.getByTestId("approvals-waiting-on")).toHaveTextContent("Waiting on: Security");
    // The recorded signature resolves its approver through the roster.
    expect(section).toHaveTextContent("ShipperDog Dev Admin");
    // The condition-excluded sibling renders its recorded rationale.
    expect(section).toHaveTextContent("condition ENVIRONMENT_EQUALS(production) unmet");
    // Decisions are offered on exactly the decidable step.
    expect(screen.getByTestId("approve-step-2")).toBeInTheDocument();
    expect(screen.getByTestId("reject-step-2")).toBeInTheDocument();
    expect(screen.queryByTestId("approve-step-4")).not.toBeInTheDocument();
    // The rework history: round 1's rejection with its reason.
    expect(section).toHaveTextContent("round 1 rejected");
    expect(section).toHaveTextContent("QA rejected");
    expect(section).toHaveTextContent("flaky test suite");
  });

  it("records a decision from the dashboard and invalidates the live state", async () => {
    renderDetail(
      { ...DETAIL_ACTIVE, status: "APPROVAL_PENDING" },
      [
        {
          method: "GET",
          url: `/api/v1/releases/${RELEASE_ID}/manifest`,
          status: 404,
          body: notFound("RELEASE_MANIFEST_NOT_FOUND"),
        },
        {
          method: "GET",
          url: `/api/v1/releases/${RELEASE_ID}/approvals`,
          status: 200,
          body: APPROVAL_STATE,
        },
        {
          method: "POST",
          url: `/api/v1/releases/${RELEASE_ID}/approvals`,
          status: 200,
          body: APPROVAL_STATE,
        },
      ],
    );

    await user.click(await screen.findByTestId("approve-step-2"));

    // The decision body addresses the asserted step (the server's CAS
    // is the authority; the console asserts what the evaluator named).
    const approveCall = fetchCalls().find(
      ([url, request]) =>
        url === `/api/v1/releases/${RELEASE_ID}/approvals` && request?.method === "POST",
    );
    expect(approveCall).toBeDefined();
    expect(JSON.parse((approveCall![1] as RequestInit).body as string)).toEqual({
      applicationId: APPLICATION_ID,
      decision: "APPROVE",
      step: 2,
    });
    expect(await screen.findByText(/Approval decision recorded/)).toBeInTheDocument();
  });

  it("offers the rework restart on a rejected round and posts the new round", async () => {
    renderDetail(
      { ...DETAIL_ACTIVE, status: "APPROVAL_PENDING" },
      [
        {
          method: "GET",
          url: `/api/v1/releases/${RELEASE_ID}/manifest`,
          status: 404,
          body: notFound("RELEASE_MANIFEST_NOT_FOUND"),
        },
        {
          method: "GET",
          url: `/api/v1/releases/${RELEASE_ID}/approvals`,
          status: 200,
          body: APPROVAL_STATE_REJECTED,
        },
        {
          method: "POST",
          url: `/api/v1/releases/${RELEASE_ID}/approvals/rounds`,
          status: 200,
          body: APPROVAL_STATE,
        },
      ],
    );

    await user.click(await screen.findByTestId("approvals-restart"));

    const restartCall = fetchCalls().find(
      ([url]) => url === `/api/v1/releases/${RELEASE_ID}/approvals/rounds`,
    );
    expect(restartCall).toBeDefined();
    expect(JSON.parse((restartCall![1] as RequestInit).body as string)).toEqual({
      applicationId: APPLICATION_ID,
    });
    expect(await screen.findByText(/Approval round restarted/)).toBeInTheDocument();
  });
});
