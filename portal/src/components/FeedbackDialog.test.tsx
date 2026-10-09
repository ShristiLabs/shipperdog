/** The "Report issue" submit-flow tests (issue #46, S11-01, AC1-AC2):
 * the version screen opens the dialog pinned to its own context, the
 * golden path POSTs one multipart request whose form carries exactly
 * the tester-typed fields and the screen's release link (never an
 * identity field — the server attaches those, and the recorded form
 * proves the client never sent one), the syntactic gates refuse
 * before any request, and every server failure renders the problem
 * envelope honestly with a retry — including the server's own
 * field-level rejections. */
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { fireEvent, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App from "../App";
import { tokenStore } from "../auth/tokenStore";
import {
  entryWithSlots,
  installFetchRouter,
  recordedRequests,
  renderPortal,
  seedHistoryProbe,
  seedSession,
} from "../test/harness";

const TENANT = "30000000-0000-4000-8000-000000000030";
const APP_ID = "10000000-0000-4000-8000-000000000001";
const VERSION = "40000000-0000-4000-8000-000000000012";
const ANDROID_ARTIFACT = "50000000-0000-4000-8000-000000000002";
const IOS_ARTIFACT = "50000000-0000-4000-8000-000000000003";
const VERSION_ROUTE = `/applications/${TENANT}/${APP_ID}/versions/${VERSION}`;

const ENTRY = entryWithSlots(VERSION, "7.12.0", [
  { platform: "ANDROID", artifactId: ANDROID_ARTIFACT, buildNumber: 842 },
  { platform: "IOS", artifactId: IOS_ARTIFACT, buildNumber: 842 },
]);

function submissionRoute(status: number, report?: Record<string, unknown>, problem?: Record<string, unknown>) {
  return {
    method: "POST",
    url: "/api/v1/feedback",
    status,
    body:
      status === 201
        ? (report ?? {
            id: "60000000-0000-4000-8000-000000000001",
            releaseId: "70000000-0000-4000-8000-000000000001",
            releaseRef: "banking-7.12.0-qa",
            applicationId: APP_ID,
            versionId: VERSION,
            artifactId: null,
            organizationId: "80000000-0000-4000-8000-000000000001",
            submitterTenantId: TENANT,
            testerId: "20000000-0000-4000-8000-000000000020",
            deviceModel: null,
            os: "Android 14",
            type: "BUG",
            status: "OPEN",
            description: "The transfer button does nothing on the review step.",
            createdAt: "2026-10-09T10:00:00Z",
            attachments: [],
          })
        : (problem ?? { title: "Not Found", status, code: "FEEDBACK_RELEASE_NOT_FOUND" }),
  };
}

async function openDialog() {
  await screen.findByTestId(`feedback-trigger-${VERSION}`);
  await userEvent.click(screen.getByTestId(`feedback-trigger-${VERSION}`));
  await screen.findByTestId("feedback-dialog");
  await userEvent.type(screen.getByTestId("feedback-description"), "The transfer button does nothing on the review step.");
}

/** MUI Select opens on the combobox's mousedown (a plain click on the
 * root does not reach the input's handler). */
async function openSelect(testId: string) {
  await fireEvent.mouseDown(within(screen.getByTestId(testId)).getByRole("combobox"));
}

beforeEach(() => {
  tokenStore.clear();
});

afterEach(() => {
  tokenStore.clear();
  vi.unstubAllGlobals();
});

describe("FeedbackDialog submit flow", () => {
  it("submits the tester-typed fields and the screen's context — never an identity field", async () => {
    installFetchRouter([submissionRoute(201)]);
    seedHistoryProbe(200, [ENTRY]);
    seedSession();
    renderPortal(<App />, { route: VERSION_ROUTE });

    await openDialog();
    await userEvent.click(screen.getByTestId("feedback-submit"));

    await screen.findByTestId("feedback-success");
    expect(screen.getByTestId("feedback-success-id")).toHaveTextContent("60000000-0000-4000-8000-000000000001");

    // The ONE request: multipart POST /api/v1/feedback with the
    // release link the screen addresses, the default type, the
    // description, and the UA-derived OS. No tester_id, no
    // submitter_tenant spoof — those the server attaches (AC2).
    const submitted = recordedRequests.find((request) => request.path === "/api/v1/feedback");
    expect(submitted).toBeDefined();
    expect(submitted?.authorization).toBe("Bearer access-token");
    expect(submitted?.form).toEqual(
      expect.arrayContaining([
        ["application_id", APP_ID],
        ["version_id", VERSION],
        ["tenant_id", TENANT],
        ["type", "BUG"],
        ["description", "The transfer button does nothing on the review step."],
      ]),
    );
    const keys = (submitted?.form ?? []).map(([key]) => key);
    expect(keys).not.toContain("tester_id");
    expect(keys).not.toContain("submitter_tenant_id");
    expect(keys).not.toContain("organization_id");
  });

  it("pins the report to the chosen build and the chosen type", async () => {
    installFetchRouter([submissionRoute(201)]);
    seedHistoryProbe(200, [ENTRY]);
    seedSession();
    renderPortal(<App />, { route: VERSION_ROUTE });

    await openDialog();
    await openSelect("feedback-type");
    await userEvent.click(await screen.findByTestId("feedback-type-CRASH"));
    await openSelect("feedback-build");
    await userEvent.click(await screen.findByTestId(`feedback-build-${ANDROID_ARTIFACT}`));
    await userEvent.click(screen.getByTestId("feedback-submit"));

    await screen.findByTestId("feedback-success");
    const form = recordedRequests.find((request) => request.path === "/api/v1/feedback")?.form ?? [];
    expect(form).toEqual(
      expect.arrayContaining([
        ["type", "CRASH"],
        ["artifact_id", ANDROID_ARTIFACT],
      ]),
    );
  });

  it("rides the attachments in the same request as named file parts", async () => {
    installFetchRouter([submissionRoute(201)]);
    seedHistoryProbe(200, [ENTRY]);
    seedSession();
    renderPortal(<App />, { route: VERSION_ROUTE });

    await openDialog();
    const log = new File(["java.lang.IllegalStateException: crash"], "crash.log", { type: "text/plain" });
    fireEvent.change(screen.getByTestId("feedback-file-input"), { target: { files: [log] } });
    await screen.findByTestId("feedback-attachment-crash.log");
    await userEvent.click(screen.getByTestId("feedback-submit"));

    await screen.findByTestId("feedback-success");
    const form = recordedRequests.find((request) => request.path === "/api/v1/feedback")?.form ?? [];
    expect(form).toContainEqual(["attachments", "crash.log"]);
  });

  it("refuses an empty description before any request (AC1)", async () => {
    installFetchRouter([]);
    seedHistoryProbe(200, [ENTRY]);
    seedSession();
    renderPortal(<App />, { route: VERSION_ROUTE });

    await screen.findByTestId(`feedback-trigger-${VERSION}`);
    await userEvent.click(screen.getByTestId(`feedback-trigger-${VERSION}`));
    await screen.findByTestId("feedback-dialog");
    await userEvent.click(screen.getByTestId("feedback-submit"));

    await screen.findByText("Describe what happened so the report can be acted on.");
    expect(recordedRequests.filter((request) => request.path === "/api/v1/feedback")).toHaveLength(0);
  });

  it("refuses a disallowed attachment extension before any request", async () => {
    installFetchRouter([]);
    seedHistoryProbe(200, [ENTRY]);
    seedSession();
    renderPortal(<App />, { route: VERSION_ROUTE });

    await openDialog();
    const exe = new File([new Uint8Array([0])], "payload.exe", { type: "application/octet-stream" });
    fireEvent.change(screen.getByTestId("feedback-file-input"), { target: { files: [exe] } });
    await userEvent.click(screen.getByTestId("feedback-submit"));

    await screen.findByTestId("feedback-attachments-error");
    expect(screen.getByTestId("feedback-attachments-error").textContent).toContain("payload.exe");
    expect(recordedRequests.filter((request) => request.path === "/api/v1/feedback")).toHaveLength(0);
  });

  it("renders the server's field-level rejection under the offending field", async () => {
    installFetchRouter([
      submissionRoute(400, undefined, {
        title: "Bad Request",
        status: 400,
        code: "INVALID_REQUEST",
        errors: { description: "description must be at most 8000 characters" },
      }),
    ]);
    seedHistoryProbe(200, [ENTRY]);
    seedSession();
    renderPortal(<App />, { route: VERSION_ROUTE });

    // A syntactically valid form the SERVER still rejects (the bound
    // is the server's): the field error renders inline, no envelope.
    await openDialog();
    await userEvent.click(screen.getByTestId("feedback-submit"));

    await screen.findByText("description must be at most 8000 characters");
    expect(screen.queryByTestId("feedback-success")).not.toBeInTheDocument();
    expect(screen.queryByTestId("feedback-error")).not.toBeInTheDocument();
  });

  it("renders the attachment rejection keyed by file name (AC3)", async () => {
    installFetchRouter([
      submissionRoute(422, undefined, {
        title: "Unprocessable Entity",
        status: 422,
        code: "ATTACHMENT_REJECTED",
        errors: { "huge.mp4": "file exceeds the video limit of 104857600 bytes" },
      }),
    ]);
    seedHistoryProbe(200, [ENTRY]);
    seedSession();
    renderPortal(<App />, { route: VERSION_ROUTE });

    await openDialog();
    const video = new File([new Uint8Array(1024)], "huge.mp4", { type: "video/mp4" });
    fireEvent.change(screen.getByTestId("feedback-file-input"), { target: { files: [video] } });
    await userEvent.click(screen.getByTestId("feedback-submit"));

    // The 422's file-keyed rejection renders inline under the
    // attachments (fix the files, not a retry): name and reason both.
    const inline = await screen.findByTestId("feedback-attachments-error");
    expect(inline.textContent).toContain("huge.mp4");
    expect(inline.textContent).toContain("video limit");
    expect(screen.queryByTestId("feedback-success")).not.toBeInTheDocument();
  });

  it("renders the concealed release-miss envelope with a retry, then succeeds (AC2)", async () => {
    installFetchRouter([
      {
        method: "POST",
        url: "/api/v1/feedback",
        sequence: [
          { status: 404, body: { title: "Not Found", status: 404, code: "FEEDBACK_RELEASE_NOT_FOUND", trace_id: "fb-404" } },
          {
            status: 201,
            body: {
              id: "60000000-0000-4000-8000-000000000009",
              releaseId: "70000000-0000-4000-8000-000000000001",
              releaseRef: "banking-7.12.0-qa",
              applicationId: APP_ID,
              versionId: VERSION,
              artifactId: null,
              organizationId: "80000000-0000-4000-8000-000000000001",
              submitterTenantId: TENANT,
              testerId: "20000000-0000-4000-8000-000000000020",
              deviceModel: null,
              os: null,
              type: "BUG",
              status: "OPEN",
              description: "The transfer button does nothing on the review step.",
              createdAt: "2026-10-09T10:00:00Z",
              attachments: [],
            },
          },
        ],
      },
    ]);
    seedHistoryProbe(200, [ENTRY]);
    seedSession();
    renderPortal(<App />, { route: VERSION_ROUTE });

    await openDialog();
    await userEvent.click(screen.getByTestId("feedback-submit"));

    await screen.findByTestId("feedback-error");
    expect(screen.getByTestId("feedback-error-detail")).toHaveTextContent("FEEDBACK_RELEASE_NOT_FOUND");
    expect(screen.getByTestId("feedback-error").textContent).toContain("trace fb-404");

    await userEvent.click(screen.getByTestId("feedback-error-retry"));
    await screen.findByTestId("feedback-success");
  });

  it("renders the unexpected-error retry on a transport failure", async () => {
    installFetchRouter([]);
    seedHistoryProbe(200, [ENTRY]);
    seedSession();
    renderPortal(<App />, { route: VERSION_ROUTE });

    // Reach the open dialog through the router, then make fetch ITSELF
    // reject (offline) for the submission alone.
    await openDialog();
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => {
        throw new TypeError("Failed to fetch");
      }),
    );
    await userEvent.click(screen.getByTestId("feedback-submit"));

    await screen.findByTestId("feedback-error");
    expect(screen.getByTestId("feedback-error-detail")).toHaveTextContent("Unexpected error. Please retry.");
  });

  it("opens from the application screen's version card as well", async () => {
    installFetchRouter([submissionRoute(201)]);
    seedHistoryProbe(200, [ENTRY]);
    seedSession();
    renderPortal(<App />, { route: `/applications/${TENANT}/${APP_ID}` });

    // The eligibility listing is the same route for both screens; the
    // card's trigger pins the submission to that version row.
    await screen.findByTestId(`feedback-card-trigger-${VERSION}`);
    await userEvent.click(screen.getByTestId(`feedback-card-trigger-${VERSION}`));
    await screen.findByTestId("feedback-dialog");
    await userEvent.type(screen.getByTestId("feedback-description"), "Crashes on the history card.");
    await userEvent.click(screen.getByTestId("feedback-submit"));

    await screen.findByTestId("feedback-success");
    const form = recordedRequests.find((request) => request.path === "/api/v1/feedback")?.form ?? [];
    expect(form).toContainEqual(["version_id", VERSION]);
  });
});
