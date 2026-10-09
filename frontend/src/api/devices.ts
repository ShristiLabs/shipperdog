/** Console device surface (S11-03, FR-028): the tenant's tester-device
 * registry — registration, listing, descriptive updates, the reversible
 * status switch, and the provisioning CSV export. The server owns
 * every rule: the tenant-scoped identifier uniqueness, the owner's
 * linked-tenant standing, the export's admin bar, and the identifier's
 * semi-sensitivity (this surface is admin-only end to end). */
import { apiFetch, apiFetchText, pageFetch, type Page } from "./client";

export type DevicePlatform = "IOS" | "ANDROID";

/** The reversible park (the V8 membership-status precedent): a
 * DEACTIVATED device keeps its rows and feedback links but stops
 * counting as an install and no longer links to new feedback. */
export type DeviceStatus = "ACTIVE" | "DEACTIVATED";

/** One registered device with its owner's contact record. */
export interface DeviceItem {
  id: string;
  testerId: string;
  ownerEmail: string | null;
  ownerName: string | null;
  platform: DevicePlatform;
  model: string | null;
  osVersion: string | null;
  identifier: string;
  status: DeviceStatus;
  createdAt: string;
  updatedAt: string;
}

export function listTenantDevices(
  tenantId: string,
  filters: { testerId?: string; platform?: DevicePlatform; status?: DeviceStatus; limit?: number; cursor?: string } = {},
): Promise<Page<DeviceItem>> {
  return pageFetch<DeviceItem>(`/tenants/${tenantId}/devices`, filters);
}

export function getTenantDevice(tenantId: string, deviceId: string): Promise<DeviceItem> {
  return apiFetch<DeviceItem>(`/tenants/${tenantId}/devices/${deviceId}`);
}

export function registerTenantDevice(
  tenantId: string,
  input: { testerId: string; platform: DevicePlatform; model?: string; osVersion?: string; identifier: string },
): Promise<DeviceItem> {
  return apiFetch<DeviceItem>(`/tenants/${tenantId}/devices`, { method: "POST", body: input });
}

/** Updates the descriptive fields only (the identifier and platform are
 * the device's identity and never move); null clears. */
export function updateTenantDevice(
  tenantId: string,
  deviceId: string,
  input: { model: string | null; osVersion: string | null },
): Promise<DeviceItem> {
  return apiFetch<DeviceItem>(`/tenants/${tenantId}/devices/${deviceId}`, { method: "PUT", body: input });
}

export function changeTenantDeviceStatus(
  tenantId: string,
  deviceId: string,
  status: DeviceStatus,
): Promise<DeviceItem> {
  return apiFetch<DeviceItem>(`/tenants/${tenantId}/devices/${deviceId}/status`, {
    method: "PUT",
    body: { status },
  });
}

/** The provisioning export (AC3): CSV text — identifier, platform,
 * model, OS version, owner email. The default slice is the ACTIVE
 * fleet ("ALL" carries the full registry); the caller owns the
 * download framing (the server sets the attachment disposition on the
 * same route). */
export function exportTenantDevicesCsv(tenantId: string, status?: "ACTIVE" | "DEACTIVATED" | "ALL"): Promise<string> {
  const suffix = status === undefined ? "" : `?status=${encodeURIComponent(status)}`;
  return apiFetchText(`/tenants/${tenantId}/devices/export${suffix}`, { accept: "text/csv" });
}
