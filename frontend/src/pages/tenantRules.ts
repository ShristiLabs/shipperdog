/**
 * Client-side mirrors of the server's tenant field rules
 * (docs/tenants.md; the API remains the authority — these only give
 * instant feedback before a request is made):
 * - name: required, 1-200 characters after trim;
 * - externalId: required, 1-100 characters after trim.
 * The server trims before validating, so the forms do too.
 */
export function validateTenantName(name: string): string | undefined {
  const trimmed = name.trim();
  if (trimmed.length === 0) return "Name is required";
  if (trimmed.length > 200) return "Name must be at most 200 characters";
  return undefined;
}

export function validateTenantExternalId(externalId: string): string | undefined {
  const trimmed = externalId.trim();
  if (trimmed.length === 0) return "External ID is required";
  if (trimmed.length > 100) return "External ID must be at most 100 characters";
  return undefined;
}
