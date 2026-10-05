/** Field-rule mirrors of the tenant API (docs/tenants.md). */
import { describe, expect, it } from "vitest";
import { validateTenantExternalId, validateTenantName } from "./tenantRules";

describe("validateTenantName", () => {
  it("requires a non-blank name after trim", () => {
    expect(validateTenantName("")).toBe("Name is required");
    expect(validateTenantName("   ")).toBe("Name is required");
    expect(validateTenantName("Acme Bank")).toBeUndefined();
  });

  it("rejects names beyond 200 characters", () => {
    expect(validateTenantName("a".repeat(201))).toBe(
      "Name must be at most 200 characters",
    );
    expect(validateTenantName("a".repeat(200))).toBeUndefined();
  });
});

describe("validateTenantExternalId", () => {
  it("requires a non-blank external id after trim", () => {
    expect(validateTenantExternalId("")).toBe("External ID is required");
    expect(validateTenantExternalId("crm-1234")).toBeUndefined();
  });

  it("rejects external ids beyond 100 characters", () => {
    expect(validateTenantExternalId("e".repeat(101))).toBe(
      "External ID must be at most 100 characters",
    );
    expect(validateTenantExternalId("e".repeat(100))).toBeUndefined();
  });
});
