# Devices (S11-03)

The tester-device registry of one tenant (FR-028, PRD 8.12/13, issue
#48): who tests on what hardware. A device belongs to a TESTER and a
TENANT (both NOT NULL from the V1 baseline), carries the store's
platform grain (`IOS`/`ANDROID`), the descriptive `model` and
`os_version`, the `identifier` (a UDID or serial — the semi-sensitive
fingerprint the issue's privacy note guards), and, since V37, the
reversible `status` switch (`ACTIVE`/`DEACTIVATED`). Implemented by
the `device` module
(`backend/src/main/java/com/shipperdog/api/device`); the routes ride
the tenant path (`/api/v1/tenants/{id}/devices`), where the tenant
module's route interceptor binds the tenant context the isolation seam
enforces for every device read and write (the device carries its OWN
tenant column — a `TenantScope` seam row, ADR-0002).

## The registration model (manual entry, by design)

Registration is the tenant administrator's manual entry. The issue's
technical note is the constraint: an iOS UDID cannot be captured from
a web page, so the M2 answer is manual entry plus the CSV export for
store-side provisioning (Apple's developer portal, ad-hoc lists, MDM
import); the mobileconfig-profile capture is a documented follow-up,
not built. The identifier is semi-sensitive (device fingerprinting),
so every device surface — registration, listing, management, export —
is admin-only by construction (`tenant:devices:*`, held by
TENANT_ADMIN and ORG_ADMIN through the owning-organization overlay;
nobody else).

Registration's guard stack (AC1), each refusal before any write:

| Guard | Failure |
|---|---|
| platform in `IOS`/`ANDROID`; bounded model/os_version/identifier | `400 INVALID_REQUEST` keyed by field |
| the owner is a tester of the tenant's OWN organization | `404 TESTER_NOT_FOUND` (concealed) |
| a retained `tenant_tester_memberships` row in this tenant (any status — registration is not effectiveness, the two-switches rule) | `422 DEVICE_REQUEST_REFUSED` |
| the tenant accepts new affiliations (not deactivated) | `422 DEVICE_REQUEST_REFUSED` |
| the identifier is new to the tenant (V1's `uq_devices_tenant_identifier`; the lost insert race answers the same) | `409 DEVICE_IDENTIFIER_TAKEN` |

The row lands `ACTIVE`. The identifier and platform never move after
registration — they ARE the device's identity; the descriptive fields
(`model`, `os_version`) update with null-clears, and the status
switch parks and releases.

## Lifecycle: never a delete

Devices are history (the INV-007 posture, AC5): rows are never
deleted. `DEACTIVATED` is the reversible park — the V8
`tenant_member_status` precedent — where the device stops counting as
an install target and new feedback cannot attach it, but the row, the
tenant's history, and its existing feedback links stay; `ACTIVE`
releases it. Deactivating a TESTER changes nothing here: no cascade
exists from `testers` to `devices` and none was added (the story's
AC5 — the records stay for the history and the provisioning list).

The no-op status crossing refuses (`422`): a parking act that changes
nothing is a mistaken request, not an event. An unchanged descriptive
update is the honest `200` with no audit event.

## The provisioning export (AC3)

`GET /api/v1/tenants/{id}/devices/export` — permission
`tenant:devices:export`, its OWN authority apart from manage, because
the download is the identifier's mass disclosure and must be
governable apart from routine management. The answer is `text/csv`
(attachment disposition), RFC 4180 quoting, one header row and one
row per device. Because the download is opened in spreadsheets, a
field that would open a cell formula — one beginning with `=`, `+`,
`-`, `@`, or a tab — is prefixed with a neutral apostrophe before the
RFC 4180 quoting (so the prefix survives inside quoted fields); a
legitimate hyphenated word never begins with the dash and passes
through untouched:

```csv
identifier,platform,model,os_version,owner_email
```

The default slice is the `ACTIVE` fleet — the devices a provisioning
list registers; `status=DEACTIVATED` exports the parked set and
`status=ALL` the full registry. The export is the tenant's whole
qualifying set, never cursor-cut (a provisioning list cut at a page
boundary would silently under-provision). The disclosure audits as
`DEVICES_EXPORTED` with the count and slice — the identifier's value
never appears in any audit detail.

## The feedback link (AC4, S11-01 linkage)

`feedback.device_id` — V1's own nullable FK — becomes live data this
story: a submission may attach one of the tester's OWN ACTIVE
devices in the submitting tenant (`device_id` on the S11-01
submission route; ownership, tenancy, and status validated
server-side, never trusted — every failure the same concealed
`DEVICE_NOT_FOUND`). The registered device's platform/model/OS then
surface in the feedback detail as AUTO-CONTEXT beside the reported
free text, and V35's `device_model`/`os` columns stay as the history
of unregistered submissions — legacy nulls stay valid. The
cross-module port (`DeviceAccess.factsOf`) answers a projection
deliberately WITHOUT the identifier: feedback readers
(`app:feedback:read`) span beyond the tenant administrators the
identifier is visible to. A DEACTIVATED device keeps its stored
links — the reports of the moment it was active — but new submissions
cannot attach it.

Device fields on analytics queries are S11-04's story, which will
also own any device-scoped join index (V37 deliberately adds none:
the tenant grain is served by the unique constraint's leading
column, the tester grain by V1's `idx_devices_tester_id`, and
per-device feedback reads resolve by primary key).

## Endpoints

Tenant-grain (base `/api/v1/tenants/{id}`):

| Method | Path | Permission | Result |
|---|---|---|---|
| POST | `/devices` | `tenant:devices:manage` | `201` the registered view (born `ACTIVE`); `409 DEVICE_IDENTIFIER_TAKEN`; `422 DEVICE_REQUEST_REFUSED`; `404 TESTER_NOT_FOUND`; `400 INVALID_REQUEST` |
| GET | `/devices` | `tenant:devices:read` | `200` the registry, conjunctive `testerId`/`platform`/`status` filters, cursor-paginated, each row joined with its owner's contact record |
| GET | `/devices/{deviceId}` | `tenant:devices:read` | `200` one device; `404 DEVICE_NOT_FOUND` |
| PUT | `/devices/{deviceId}` | `tenant:devices:manage` | `200` model/OS only (null clears) |
| PUT | `/devices/{deviceId}/status` | `tenant:devices:manage` | `200` the reversible switch; `422` the no-op crossing |
| GET | `/devices/export` | `tenant:devices:export` | `200 text/csv` the provisioning list |

Unknown devices, foreign tenants (the tenant seam's filtered answer),
and permission denials all render as the concealed 404 a genuine miss
produces (PRD 12); all errors use the problem+json envelope (see [API
conventions](api-conventions.md) and the [error code
registry](error-codes.md)). Mutations audit `DEVICE_REGISTERED` /
`DEVICE_UPDATED` / `DEVICE_STATUS_CHANGED` / `DEVICES_EXPORTED` (see
[audit](audit.md)).

## Console surface

The console renders the registry per AC2: `TenantDevicesPage`
(`/tenants/:tenantId/devices`, reached from the tenants listing's
Devices action) — the data grid with owner, platform, identifier,
model/OS, and status chips; the register dialog (owner picker over
the tenant's linked testers, platform, identifier, optional model/OS);
descriptive edit; the reversible park; the CSV download; and the
release dashboard's feedback detail renders the linked device's
auto-context (see [frontend](frontend.md)).

## Schema and indexes (V1 + V37)

The table is V1 baseline: `id`, `tester_id` (NOT NULL, FK testers),
`tenant_id` (NOT NULL, FK tenants), `platform platform_enum`,
`model`, `os_version`, `identifier` (NOT NULL), timestamps, and
V1's own `UNIQUE (tenant_id, identifier)` with
`idx_devices_tester_id`. V37 adds the one attribute PRD 8.12 lists
that the baseline lacked: `status device_status NOT NULL DEFAULT
'ACTIVE'` (expand-only, ADR-0001 — the enum type and the column, no
rebuild). See
[database migrations](database-migrations.md).
