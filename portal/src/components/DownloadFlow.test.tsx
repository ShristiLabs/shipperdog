/**
 * The download flow's component contract (issue #38, S8-04): the
 * per-platform guidance screen renders BEFORE the URL opens —
 * Android's install-unknown-apps consent, the iOS direct path's
 * ad-hoc/enterprise note, the TestFlight placeholder row — the
 * server-issued URL is what opens (the portal mints nothing), and a
 * denial renders the opaque problem envelope without a layer hint.
 */
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import DownloadFlow from "./DownloadFlow";
import {
  installFetchRouter,
  renderPortal,
  seedDownload,
  seedSession,
  unmatchedRequests,
} from "../test/harness";

const VERSION_ID = "40000000-0000-4000-8000-000000000004";
const ARTIFACT_ID = "50000000-0000-4000-8000-000000000005";

const assign = vi.fn();

beforeEach(() => {
  installFetchRouter([]);
  seedSession();
  // happy-dom's location is replaceable; the flow must hand the URL to
  // the browser exactly as issued, and the test pins that handoff.
  Object.defineProperty(window, "location", {
    value: { assign, href: "http://localhost:5174/", origin: "http://localhost:5174" },
    configurable: true,
    writable: true,
  });
  assign.mockClear();
});

afterEach(() => {
  expect(unmatchedRequests, "every request is stubbed").toEqual([]);
});

describe("the Android download flow", () => {
  it("shows the install-unknown-apps guidance before opening the server-issued URL", async () => {
    seedDownload(ARTIFACT_ID, 200, { platform: "ANDROID", behavior: "OPEN_SIGNED_URL" });
    renderPortal(<DownloadFlow artifactId={ARTIFACT_ID} versionId={VERSION_ID} platform="ANDROID" />);

    await userEvent.click(screen.getByTestId("download-trigger-ANDROID"));

    const guidance = await screen.findByTestId("download-guidance-android");
    expect(guidance.textContent).toContain("Install unknown apps");
    expect(screen.getByTestId("download-guidance-facts").textContent).toContain("banking.apk");

    await userEvent.click(screen.getByTestId("download-guidance-continue"));
    expect(assign).toHaveBeenCalledWith("/api/v1/downloads/test-token");
  });

  it("keeps the guidance open on Not now — no navigation, no second issuance", async () => {
    seedDownload(ARTIFACT_ID, 200);
    renderPortal(<DownloadFlow artifactId={ARTIFACT_ID} versionId={VERSION_ID} platform="ANDROID" />);

    await userEvent.click(screen.getByTestId("download-trigger-ANDROID"));
    await screen.findByTestId("download-guidance");
    await userEvent.click(screen.getByTestId("download-guidance-cancel"));

    await waitFor(() => expect(screen.queryByTestId("download-guidance")).toBeNull());
    expect(assign).not.toHaveBeenCalled();
  });
});

describe("the iOS download flows", () => {
  it("carries the ad-hoc/enterprise note on the direct path", async () => {
    seedDownload(ARTIFACT_ID, 200, { platform: "IOS", behavior: "OPEN_SIGNED_URL_ADHOC" });
    renderPortal(<DownloadFlow artifactId={ARTIFACT_ID} versionId={VERSION_ID} platform="IOS" />);

    await userEvent.click(screen.getByTestId("download-trigger-IOS"));

    const guidance = await screen.findByTestId("download-guidance-ios");
    expect(guidance.textContent).toContain("ad-hoc or enterprise-signed");

    await userEvent.click(screen.getByTestId("download-guidance-continue"));
    expect(assign).toHaveBeenCalledWith("/api/v1/downloads/test-token");
  });

  it("renders the TestFlight placeholder row the channel table carries for M2/M3", async () => {
    seedDownload(ARTIFACT_ID, 200, {
      platform: "IOS",
      behavior: "DEEP_LINK_TESTFLIGHT",
      channel: "TESTFLIGHT",
    });
    renderPortal(<DownloadFlow artifactId={ARTIFACT_ID} versionId={VERSION_ID} platform="IOS" />);

    await userEvent.click(screen.getByTestId("download-trigger-IOS"));

    const guidance = await screen.findByTestId("download-guidance-testflight");
    expect(guidance.textContent).toContain("TestFlight");
    expect(screen.getByTestId("download-guidance-continue").textContent).toContain("Open TestFlight");
  });
});

describe("denials", () => {
  it("renders the opaque problem envelope with a retry", async () => {
    seedDownload(ARTIFACT_ID, 404);
    renderPortal(<DownloadFlow artifactId={ARTIFACT_ID} versionId={VERSION_ID} platform="ANDROID" />);

    await userEvent.click(screen.getByTestId("download-trigger-ANDROID"));

    const body = await screen.findByTestId("download-denied-body");
    expect(body.textContent).toContain("VERSION_NOT_FOUND");
    expect(screen.queryByTestId("download-guidance-continue")).toBeNull();

    await userEvent.click(screen.getByTestId("download-denied-close"));
    await waitFor(() => expect(screen.queryByTestId("download-denied-body")).toBeNull());
    expect(assign).not.toHaveBeenCalled();
  });
});
