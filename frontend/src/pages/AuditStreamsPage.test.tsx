/** SIEM audit-stream behavior (S14-05, issue #71): the export listing
 * with kind/format/filter chips and the bucket rollups, the create
 * POST carrying the configuration as secret-manager references, the
 * dead-letter's FAILED rows and the replay action, and the test
 * event's honest synchronous outcome. */
import { cleanup, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { Route, Routes } from "react-router-dom";
import AuditStreamsPage from "./AuditStreamsPage";
import { installFetchRouter, renderConsole, seedSession } from "../test/harness";

const user = userEvent.setup();

const ORG_ID = "10000000-0000-4000-8000-000000000001";

const EXPORT_HTTPS = {
  id: "41000000-0000-4000-8000-000000000001",
  organizationId: ORG_ID,
  kind: "HTTPS",
  format: "SPLUNK_HEC",
  eventTypes: [],
  destinationUrl: "https://siem.customer.test/services/collector/event",
  bucketUri: null,
  signingSecretRef: "sm://customer/signing",
  authHeaderName: "Authorization",
  authSecretRef: "sm://customer/hec-token",
  enabled: true,
  watermark: null,
  batchSeq: 0,
  batchesWritten: 0,
  eventsExported: 0,
  createdAt: "2026-10-12T10:00:00Z",
  updatedAt: "2026-10-12T10:00:00Z",
};

const EXPORT_BUCKET = {
  id: "41000000-0000-4000-8000-000000000002",
  organizationId: ORG_ID,
  kind: "BUCKET",
  format: null,
  eventTypes: ["ORGANIZATION_UPDATED", "ACCESS_DENIED"],
  destinationUrl: null,
  bucketUri: "gs://customer-siem/shipperdog",
  signingSecretRef: "sm://customer/manifest-signing",
  authHeaderName: null,
  authSecretRef: null,
  enabled: false,
  watermark: "2026-10-12T09:00:00Z",
  batchSeq: 3,
  batchesWritten: 3,
  eventsExported: 127,
  createdAt: "2026-10-10T10:00:00Z",
  updatedAt: "2026-10-12T09:05:00Z",
};

const DELIVERY_FAILED = {
  id: "51000000-0000-4000-8000-000000000001",
  eventId: "61000000-0000-4000-8000-000000000001",
  exportId: EXPORT_HTTPS.id,
  format: "SPLUNK_HEC",
  status: "FAILED",
  attempts: 3,
  lastError: "InternalServerError: 503 Service Unavailable",
  deliveredAt: null,
  createdAt: "2026-10-12T11:01:00Z",
};

const BATCH_LATEST = {
  id: "52000000-0000-4000-8000-000000000001",
  seq: 3,
  watermarkFrom: "2026-10-12T08:00:00Z",
  watermarkTo: "2026-10-12T09:00:00Z",
  eventCount: 42,
  batchSha256: "ab12cd34ef56".repeat(5) + "ab12",
  objectUri: "gs://customer-siem/shipperdog/audit-" + ORG_ID + "-000003.jsonl",
  manifestUri: "gs://customer-siem/shipperdog/manifest-" + ORG_ID + "-000003.json",
  createdAt: "2026-10-12T09:00:30Z",
};

function fetchCalls(): Array<[string, RequestInit | undefined]> {
  const mock = fetch as unknown as { mock: { calls: Array<[string, RequestInit]> } };
  return mock.mock.calls;
}

function bodyOf(url: string, method: string): Record<string, unknown> {
  const call = fetchCalls().find(
    ([candidate, init]) => candidate === url && (init?.method ?? "GET") === method,
  );
  expect(call, `${method} ${url} was issued`).toBeDefined();
  return JSON.parse((call![1] as RequestInit).body as string) as Record<string, unknown>;
}

function renderPage(): void {
  installFetchRouter([
    {
      method: "GET",
      url: `/api/v1/organizations/${ORG_ID}/audit-stream-exports`,
      status: 200,
      body: { items: [EXPORT_BUCKET, EXPORT_HTTPS] },
    },
    {
      method: "GET",
      url: `/api/v1/organizations/${ORG_ID}/audit-stream-exports/${EXPORT_HTTPS.id}/deliveries`,
      status: 200,
      body: { items: [DELIVERY_FAILED] },
    },
    {
      method: "GET",
      url: `/api/v1/organizations/${ORG_ID}/audit-stream-exports/${EXPORT_BUCKET.id}/batches`,
      status: 200,
      body: { items: [BATCH_LATEST] },
    },
  ]);
  seedSession();
  renderConsole(
    <Routes>
      <Route path="/audit-streams" element={<AuditStreamsPage />} />
    </Routes>,
    { route: "/audit-streams" },
  );
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("AuditStreamsPage", () => {
  it("lists the exports with kind, format, filter, and the bucket rollup", async () => {
    renderPage();

    expect(await screen.findByTestId(`audit-stream-${EXPORT_HTTPS.id}`)).toHaveTextContent(
      "SPLUNK_HEC",
    );
    expect(screen.getByTestId(`audit-stream-${EXPORT_HTTPS.id}`)).toHaveTextContent("ALL events");
    expect(screen.getByTestId(`audit-stream-destination-${EXPORT_HTTPS.id}`)).toHaveTextContent(
      "siem.customer.test",
    );
    expect(screen.getByTestId(`audit-stream-destination-${EXPORT_HTTPS.id}`)).toHaveTextContent(
      "siem.customer.test",
    );
    const bucket = screen.getByTestId(`audit-stream-${EXPORT_BUCKET.id}`);
    expect(bucket).toHaveTextContent("BUCKET");
    expect(bucket).toHaveTextContent("2 event types");
    expect(bucket).toHaveTextContent("127 events exported");
    expect(bucket).toHaveTextContent("parked");
  });

  it("shows the dead-letter with its last error and replays it", async () => {
    renderPage();

    await screen.findByTestId(`audit-stream-${EXPORT_HTTPS.id}`);
    await user.click(screen.getByRole("button", { name: "Deliveries" }));
    const failed = await screen.findByTestId(`audit-stream-delivery-${DELIVERY_FAILED.id}`);
    expect(failed).toHaveTextContent("FAILED");
    expect(failed).toHaveTextContent("503");

    installFetchRouter([
      {
        method: "GET",
        url: `/api/v1/organizations/${ORG_ID}/audit-stream-exports`,
        status: 200,
        body: { items: [EXPORT_HTTPS] },
      },
      {
        method: "POST",
        url: `/api/v1/organizations/${ORG_ID}/audit-stream-exports/${EXPORT_HTTPS.id}/replay`,
        status: 200,
        body: { exportId: EXPORT_HTTPS.id, replayed: 1 },
      },
      {
        method: "GET",
        url: `/api/v1/organizations/${ORG_ID}/audit-stream-exports/${EXPORT_HTTPS.id}/deliveries`,
        status: 200,
        body: { items: [] },
      },
    ]);
    await user.click(screen.getByTestId(`audit-stream-replay-${EXPORT_HTTPS.id}`));
    await waitFor(() => {
      expect(screen.getByText(/1 dead-lettered deliveries returned/)).toBeDefined();
    });
  });

  it("shows the bucket batches with sequence, count, and digest", async () => {
    renderPage();

    await screen.findByTestId(`audit-stream-${EXPORT_BUCKET.id}`);
    await user.click(screen.getByRole("button", { name: "Batches" }));
    const batch = await screen.findByTestId(`audit-stream-batch-${BATCH_LATEST.id}`);
    expect(batch).toHaveTextContent("seq 3");
    expect(batch).toHaveTextContent("42 events");
    expect(batch).toHaveTextContent("ab12cd34ef56");
  });

  it("creates an HTTPS export carrying secret references only", async () => {
    renderPage();

    await screen.findByTestId(`audit-stream-${EXPORT_HTTPS.id}`);
    await user.click(screen.getByTestId("audit-stream-create"));
    await user.clear(screen.getByLabelText("Collector URL (https://)"));
    await user.type(screen.getByLabelText("Collector URL (https://)"), "https://collector.customer.test/events");
    await user.clear(screen.getByLabelText(/Signing secret reference/));
    await user.type(screen.getByLabelText(/Signing secret reference/), "sm://customer/new-signing");
    await user.clear(screen.getByLabelText(/Auth header name/));
    await user.type(screen.getByLabelText(/Auth header name/), "Authorization");
    await user.clear(screen.getByLabelText(/Auth secret reference/));
    await user.type(screen.getByLabelText(/Auth secret reference/), "sm://customer/new-token");
    await user.clear(screen.getByLabelText(/Event-type filter/));
    await user.type(screen.getByLabelText(/Event-type filter/), "ACCESS_DENIED, LOGIN_FAILURE");
    await user.click(screen.getByTestId("audit-stream-save"));

    const body = bodyOf(`/api/v1/organizations/${ORG_ID}/audit-stream-exports`, "POST");
    expect(body.kind).toBe("HTTPS");
    expect(body.format).toBe("SHIPPERDOG_JSON");
    expect(body.destinationUrl).toBe("https://collector.customer.test/events");
    expect(body.signingSecretRef).toBe("sm://customer/new-signing");
    expect(body.authHeaderName).toBe("Authorization");
    expect(body.authSecretRef).toBe("sm://customer/new-token");
    expect(body.eventTypes).toEqual(["ACCESS_DENIED", "LOGIN_FAILURE"]);
  });

  it("answers the test action's outcome honestly", async () => {
    renderPage();

    await screen.findByTestId(`audit-stream-${EXPORT_HTTPS.id}`);
    installFetchRouter([
      {
        method: "GET",
        url: `/api/v1/organizations/${ORG_ID}/audit-stream-exports`,
        status: 200,
        body: { items: [EXPORT_HTTPS] },
      },
      {
        method: "POST",
        url: `/api/v1/organizations/${ORG_ID}/audit-stream-exports/${EXPORT_HTTPS.id}/test`,
        status: 200,
        body: { delivered: true, kind: "HTTPS", error: null, sentAt: "2026-10-12T12:00:00Z" },
      },
    ]);
    await user.click(screen.getByTestId(`audit-stream-test-${EXPORT_HTTPS.id}`));
    await waitFor(() => {
      expect(screen.getByText("Test event delivered")).toBeDefined();
    });
  });
});
