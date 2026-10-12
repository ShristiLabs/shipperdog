/**
 * The telemetry pairing section of the download guidance (S15-01,
 * issue #72): a paired download's issuance carries the SDK token and
 * the dialog surfaces it with a copy action; an unpaired download
 * renders the guidance exactly as before (telemetry is additive,
 * never a gate). The request also carries the portal's tenant
 * addressing (the token binds the reporting tenant).
 */
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import DownloadFlow from "./DownloadFlow";
import { installFetchRouter, renderPortal, seedDownload, seedSession, unmatchedRequests } from "../test/harness";

const VERSION_ID = "40000000-0000-4000-8000-000000000004";
const ARTIFACT_ID = "50000000-0000-4000-8000-000000000005";
const TENANT_ID = "60000000-0000-4000-8000-000000000006";

const clipboardWrite = vi.fn();

beforeEach(() => {
  installFetchRouter([]);
  seedSession();
  Object.defineProperty(window, "location", {
    value: { assign: vi.fn(), href: "http://localhost:5174/", origin: "http://localhost:5174" },
    configurable: true,
    writable: true,
  });
  Object.defineProperty(navigator, "clipboard", {
    value: { writeText: clipboardWrite },
    configurable: true,
  });
  clipboardWrite.mockClear();
});

afterEach(() => {
  expect(unmatchedRequests, "every request is stubbed").toEqual([]);
});

describe("the telemetry pairing section", () => {
  it("surfaces the SDK token with a copy action on a paired download", async () => {
    seedDownload(ARTIFACT_ID, 200, {
      telemetry: {
        token: "sdt1.fixture-token",
        expiresAt: new Date(Date.now() + 86_400_000).toISOString(),
        tenantId: TENANT_ID,
        applicationId: "a",
        releaseId: "r",
        versionId: "v",
        deviceId: null,
      },
    });
    renderPortal(
      <DownloadFlow artifactId={ARTIFACT_ID} versionId={VERSION_ID} platform="ANDROID" tenantId={TENANT_ID} />,
    );

    await userEvent.click(screen.getByTestId("download-trigger-ANDROID"));
    const section = await screen.findByTestId("download-guidance-telemetry");
    expect(section.textContent).toContain("telemetry SDK");
    expect(screen.getByTestId("download-guidance-telemetry-token").textContent).toBe("sdt1.fixture-token");

    await userEvent.click(screen.getByTestId("download-guidance-telemetry-copy"));
    await waitFor(() => expect(clipboardWrite).toHaveBeenCalledWith("sdt1.fixture-token"));
  });

  it("renders the guidance unchanged when the download carries no telemetry block", async () => {
    seedDownload(ARTIFACT_ID, 200);
    renderPortal(
      <DownloadFlow artifactId={ARTIFACT_ID} versionId={VERSION_ID} platform="ANDROID" tenantId={TENANT_ID} />,
    );

    await userEvent.click(screen.getByTestId("download-trigger-ANDROID"));
    await screen.findByTestId("download-guidance");
    expect(screen.queryByTestId("download-guidance-telemetry")).toBeNull();
  });
});
