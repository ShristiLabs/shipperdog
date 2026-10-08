/** Release-ring surface client (S10-04 backend, docs/releases.md):
 * the organization-grain ring routes (the effective vocabulary, and
 * the add/rename/reorder/re-rule CRUD an Organization Admin customizes
 * the rollout ladder with) and the release-addressed eligibility read
 * (who the release's current rings admit — the distribution targets
 * INTERSECT ring eligibility arithmetic, inspectable). Field shapes
 * mirror the release module's served views exactly; the eligibility
 * rules are the two axes (tenants, groups), an absent axis
 * unconstrained. */
import { apiFetch, pageFetch } from "./client";

/** One gate of a ring's set: the type and its required standing (the
 * S10-03 definitions attached to the ring plus the organization-wide
 * standing). */
export interface GateSetEntry {
  gateType: string;
  required: boolean;
}

/** The ring view: identity and order, whether the row is the
 * organization's own or a system default, the PRD 8.10 properties,
 * and the gate set. */
export interface Ring {
  id: string;
  code: string;
  name: string;
  position: number;
  organizationScoped: boolean;
  eligibility: { tenants?: string[]; groups?: string[] };
  approvalPolicy: string | null;
  promotionPolicy: "MANUAL" | "AUTOMATIC";
  distributionChannel: string | null;
  gateSet: GateSetEntry[];
}

/** The create body: a new code of the organization's own, or a SYSTEM
 * code — the shadow that redefines the code's meaning for the
 * organization. */
export interface RingCreateInput {
  organizationId: string;
  code: string;
  name: string;
  position: number;
  eligibility?: { tenants?: string[]; groups?: string[] } | null;
  approvalPolicy?: string | null;
  promotionPolicy?: "MANUAL" | "AUTOMATIC" | null;
  distributionChannel?: string | null;
}

/** The edit body: absent fields keep their standing; an EMPTY
 * eligibility object CLEARS the rules. The code never moves. */
export interface RingUpdateInput {
  name?: string;
  position?: number;
  eligibility?: { tenants?: string[]; groups?: string[] } | null;
  approvalPolicy?: string | null;
  promotionPolicy?: "MANUAL" | "AUTOMATIC" | null;
  distributionChannel?: string | null;
}

/** One target's admission row: the tenant, its resolved ring, the
 * verdict, the audience groups distribution narrows to (empty with
 * tenantAtLarge = the tenant at large), and the exclusion reasons. */
export interface TargetAdmission {
  tenantId: string;
  ring: string | null;
  admitted: boolean;
  audienceGroups: string[];
  tenantAtLarge: boolean;
  reasons: string[];
}

/** The release's ring eligibility: the admission rows of every target
 * at its current ring, and (when a tester was named) the ring
 * visibility decision for that tester. */
export interface EligibilityView {
  releaseId: string;
  releaseRef: string;
  status: string;
  admission: { releaseId: string; releaseRef: string; ring: string | null; targets: TargetAdmission[] };
  admitsTester: boolean | null;
}

/** The whole effective vocabulary: the ladder is small (the seeded five
 * plus customs), one capped walk collects it for the editor. */
export async function listRings(organizationId: string): Promise<Ring[]> {
  const rings: Ring[] = [];
  let cursor: string | undefined;
  let pages = 0;
  do {
    const page = await pageFetch<Ring>("/rings", { organizationId, limit: 100, cursor });
    rings.push(...page.items);
    cursor = page.nextCursor;
    pages += 1;
  } while (cursor !== undefined && pages < 20);
  return rings;
}

export function getRing(organizationId: string, ringId: string): Promise<Ring> {
  return apiFetch<Ring>(`/rings/${ringId}?organizationId=${encodeURIComponent(organizationId)}`);
}

export function createRing(input: RingCreateInput): Promise<Ring> {
  return apiFetch<Ring>("/rings", { method: "POST", body: input });
}

export function updateRing(organizationId: string, ringId: string, input: RingUpdateInput): Promise<Ring> {
  return apiFetch<Ring>(`/rings/${ringId}?organizationId=${encodeURIComponent(organizationId)}`, {
    method: "PATCH",
    body: input,
  });
}

export function deleteRing(organizationId: string, ringId: string): Promise<void> {
  return apiFetch<void>(`/rings/${ringId}?organizationId=${encodeURIComponent(organizationId)}`, {
    method: "DELETE",
  });
}

/** The release eligibility read; testerId adds the ring-visibility
 * decision for that one tester. */
export function getReleaseEligibility(
  applicationId: string,
  releaseId: string,
  testerId?: string,
): Promise<EligibilityView> {
  const params = new URLSearchParams({ applicationId });
  if (testerId) params.set("testerId", testerId);
  return apiFetch<EligibilityView>(`/releases/${releaseId}/eligibility?${params.toString()}`);
}

/** One tenant's tester groups for the eligibility picker (the groups
 * axis lists ids the backend validates against the organization). */
export function listTenantTesterGroups(
  tenantId: string,
): Promise<{ id: string; tenantId: string; name: string }[]> {
  return apiFetch<{ items: { id: string; tenantId: string; name: string }[] }>(
    `/tester-groups?tenantId=${encodeURIComponent(tenantId)}&limit=100`,
  ).then((page) => page.items);
}
