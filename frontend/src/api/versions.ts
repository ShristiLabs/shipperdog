/** Version reads the console needs for release dashboards (S9-05):
 * the per-application listing feeds the release list's version filter
 * and resolves version names for release headers. The view mirrors the
 * version module's VersionView (S4); write surfaces stay console-out
 * (the CLI owns them, S5-06/S9-06). */
import { apiFetch, pageFetch, type Page } from "./client";

export interface VersionArtifactSlot {
  id: string;
  platform: string;
  fileName: string;
  size: number;
  sha256: string;
  status: string;
  uploadedAt: string;
  buildNumber: number | null;
}

export interface Version {
  id: string;
  applicationId: string;
  versionName: string;
  status: string;
  tags: string[];
  createdAt: string;
  updatedAt: string;
  artifactsByPlatform: Record<string, VersionArtifactSlot[]>;
}

export function listApplicationVersions(
  applicationId: string,
  params: { limit?: number; cursor?: string } = {},
): Promise<Page<Version>> {
  return pageFetch<Version>(`/applications/${applicationId}/versions`, params);
}

export function getApplicationVersion(applicationId: string, versionId: string): Promise<Version> {
  return apiFetch<Version>(`/applications/${applicationId}/versions/${versionId}`);
}
