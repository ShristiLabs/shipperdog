/** The feedback API module's unit tests (issue #46, S11-01): the
 * UA-derived OS prefill — the one piece of submission logic that
 * lives client-side. The submission contract itself is exercised
 * through FeedbackDialog.test.tsx (the recorded multipart form). */
import { describe, expect, it } from "vitest";
import { deriveOsFromUserAgent, FEEDBACK_TYPES } from "./feedback";

describe("deriveOsFromUserAgent", () => {
  it("derives iOS with the major version from an iPhone UA", () => {
    expect(
      deriveOsFromUserAgent(
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4_1 like Mac OS X) AppleWebKit/605.1.15",
      ),
    ).toBe("iOS 17.4.1");
  });

  it("derives plain iOS when the UA carries no version", () => {
    expect(deriveOsFromUserAgent("Mozilla/5.0 (iPad like Mac OS X)")).toBe("iOS");
  });

  it("derives Android with the version", () => {
    expect(
      deriveOsFromUserAgent("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36"),
    ).toBe("Android 14");
  });

  it("leaves the field empty for an unrecognized UA — honest, not guessed", () => {
    expect(deriveOsFromUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)")).toBe("");
  });
});

describe("FEEDBACK_TYPES", () => {
  it("carries exactly PRD 8.13's six types", () => {
    expect([...FEEDBACK_TYPES]).toEqual([
      "BUG",
      "FEEDBACK",
      "CRASH",
      "FEATURE_REQUEST",
      "UX_ISSUE",
      "SECURITY_ISSUE",
    ]);
  });
});
