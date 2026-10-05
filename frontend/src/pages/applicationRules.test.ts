/** Application field-rule mirrors (issue #16): the forms give instant
 * feedback with the same verdicts the API would return. */
import { describe, expect, it } from "vitest";
import {
  REVERSE_DNS_PATTERN,
  validateApplicationDescription,
  validateApplicationName,
  validatePlatformIdentifier,
  validateReason,
} from "./applicationRules";

describe("applicationRules", () => {
  it("validates the name (required, max 200 after trim)", () => {
    expect(validateApplicationName("Banking Mobile")).toBeUndefined();
    expect(validateApplicationName("   ")).toBe("Name is required");
    expect(validateApplicationName("x".repeat(201))).toBe("Name must be at most 200 characters");
  });

  it("validates the description (optional, max 2000)", () => {
    expect(validateApplicationDescription("")).toBeUndefined();
    expect(validateApplicationDescription("x".repeat(2000))).toBeUndefined();
    expect(validateApplicationDescription("x".repeat(2001))).toBe(
      "Description must be at most 2000 characters",
    );
  });

  it("requires per-platform identifiers in reverse-DNS form (max 255)", () => {
    expect(validatePlatformIdentifier("com.acme.banking", "bundleId")).toBeUndefined();
    expect(validatePlatformIdentifier("com.acme.banking_2", "packageName")).toBeUndefined();
    expect(validatePlatformIdentifier("", "bundleId")).toBe(
      "bundleId is required when its platform is chosen",
    );
    expect(validatePlatformIdentifier("not-a-domain", "bundleId")).toBe(
      "bundleId must be a reverse-DNS identifier like com.example.app",
    );
    expect(validatePlatformIdentifier("1com.app", "packageName")).toBe(
      "packageName must be a reverse-DNS identifier like com.example.app",
    );
    expect(validatePlatformIdentifier(`com.${"x".repeat(260)}`, "bundleId")).toBe(
      "bundleId must be a reverse-DNS identifier like com.example.app",
    );
    expect(REVERSE_DNS_PATTERN.test("com.acme.app")).toBe(true);
    expect(REVERSE_DNS_PATTERN.test("com")).toBe(false);
  });

  it("validates the optional grant/revoke reason (max 500 when given)", () => {
    expect(validateReason("")).toBeUndefined();
    expect(validateReason("   ")).toBeUndefined();
    expect(validateReason("Pilot rollout")).toBeUndefined();
    expect(validateReason("x".repeat(501))).toBe("Reason must be at most 500 characters");
  });
});
