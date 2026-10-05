/**
 * Client-side mirrors of the server's application field rules
 * (ApplicationService; the API remains the authority — these only give
 * instant feedback before a request is made, and the server trims
 * before validating, so the forms do too):
 * - name: required, 1-200 characters after trim;
 * - description: optional, at most 2000 characters (an empty value
 *   clears it on update);
 * - bundleId/packageName: required exactly when their platform is
 *   chosen and forbidden otherwise; reverse-DNS, at most 255;
 * - reason (grant/revoke): optional, 1-500 characters when given.
 */
export const REVERSE_DNS_PATTERN = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/;

const NAME_MAX = 200;
const DESCRIPTION_MAX = 2000;
const IDENTIFIER_MAX = 255;
const REASON_MAX = 500;

export function validateApplicationName(name: string): string | undefined {
  const trimmed = name.trim();
  if (trimmed.length === 0) return "Name is required";
  if (trimmed.length > NAME_MAX) return `Name must be at most ${NAME_MAX} characters`;
  return undefined;
}

export function validateApplicationDescription(description: string): string | undefined {
  if (description.trim().length > DESCRIPTION_MAX) {
    return `Description must be at most ${DESCRIPTION_MAX} characters`;
  }
  return undefined;
}

/** Identifier rule for a chosen platform: required, reverse-DNS, at
 * most 255 characters. */
export function validatePlatformIdentifier(
  value: string,
  field: "bundleId" | "packageName",
): string | undefined {
  const trimmed = value.trim();
  if (trimmed.length === 0) return `${field} is required when its platform is chosen`;
  if (trimmed.length > IDENTIFIER_MAX || !REVERSE_DNS_PATTERN.test(trimmed)) {
    return `${field} must be a reverse-DNS identifier like com.example.app`;
  }
  return undefined;
}

export function validateReason(reason: string): string | undefined {
  const trimmed = reason.trim();
  if (trimmed.length === 0) return undefined;
  if (trimmed.length > REASON_MAX) return `Reason must be at most ${REASON_MAX} characters`;
  return undefined;
}
