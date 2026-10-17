---
description: Dashboards and analytics - read the organization dashboard, the tenant dashboard, and the release-health picture they compute.
---

# Read the dashboards

ShipperDog computes two landing views — one for the organization,
one per tenant — and both refresh themselves every 30 seconds.
Nothing here needs configuring: the dashboards are reads over the
same facts every other screen enforces.

![The organization dashboard](/screenshots/console-dashboard.png)

What the numbers mean — the feedback-to-metric-to-gate loop — is
covered in [feedback and analytics](/concepts/feedback-analytics.md).

## The organization dashboard

Open **Dashboard** (the Console's landing screen). You get:

- **Six counts** — Applications, Tenants, Active releases, Pending
  approvals, Testing, Production.
- **Release health per application** — one row per application with
  its derived health: `HEALTHY`, `TESTING`, `BLOCKED`, or
  `INACTIVE`. The derivation is the server's, not a client's guess.
- **Your pending actions** — the approvals and decisions *you*
  personally can act on, filtered to your roles, each row
  deep-linking to the release screen it acts on and wearing a
  **Decide** chip when the call is yours.

The pending-actions list is the working queue: an approval waiting
on your signature surfaces here first, one click from the
[approval step](/how-to/console/approvals.md).

## The tenant dashboard

Tenants — **Dashboard** on a tenant's row. The tenant-grain view
carries:

- the tenant block's four counts,
- the in-flight releases targeting this tenant (each deep-linking
  to its release screen),
- the **downloads trend over the last seven days** — one folded
  point per day from the tenant's download series.

Tenant admins live here; it is their entire Console. See the
[tenant-admin quickstart](/roles/tenant-admin.md).

## Where the deeper numbers live

The dashboards are rollups; the grain lives on the artifacts they
summarize:

- Per-release feedback and its open-critical count:
  [triage feedback](/how-to/console/feedback-triage.md).
- Per-release gate states and observed values:
  [quality gates](/how-to/console/quality-gates.md).
- Per-channel distribution standing (TestFlight beta review, Play
  processing): the release dashboard's Channel distributions block.

::: tip Downloads drive gates
Metric-backed gates (`crash-free-threshold`,
`open-critical-bugs`) resolve from these same analytics feeds at
evaluation time, fail-closed until data lands — a dashboard that
shows no downloads yet also means a crash-free gate that cannot
pass yet.
:::

**Related guides:** [work with releases](/how-to/console/releases.md) ·
[quality gates](/how-to/console/quality-gates.md) ·
[tenant-admin quickstart](/roles/tenant-admin.md)
