# Roles

ShipperDog's role catalog maps to nine documentation personas. Each persona
gets a dedicated quickstart ending in a hand-off task list; the quickstarts
land in the WP4 rollout (see [the plan](https://github.com/ShristiLabs/shipperdog/blob/main/docs-site/PLAN.md)).

## Platform Operator

`platform_admin` — Console and Terraform. Org onboarding, instance health,
cross-tenant operations, break-glass.

## Organization Admin

`ORG_ADMIN` — Console. Members and roles, tenants, IdP/SCIM/SSO configuration,
channel credentials, ABAC and SIEM policies.

## Release Manager

`RELEASE_MANAGER` — Console and CLI. Release creation through approvals,
gates, ring promotion, staged rollout, and rollback.

## App Owner / Product

`APP_OWNER` — Console. App registry, ownership scope, product approvals,
release manifests, dashboards.

## Developer

`DEVELOPER` — CLI, CI, and Console. Artifact upload with provenance, versions
and tags, CI actions (GitHub Actions, Jenkins, GitLab), gate feedback.

## QA Engineer

`QA_ENGINEER` — Console and Portal admin views. Tester groups, feedback
triage, crash-free gates, device fleet.

## Security / Compliance Reviewer

`SECURITY_REVIEWER`, `COMPLIANCE_REVIEWER` — Console. Security feedback,
approval signatures, audit trail, SIEM streams.

## Tenant Admin

`TENANT_ADMIN` — Console, tenant-scoped. Tenant members, devices, tenant
dashboard, app visibility.

## Tester

`TENANT_TESTER` — Tester Portal and the companion app. Accept an invite,
browse apps, install builds, submit feedback. The one persona with zero
Console access — the Portal track is fully standalone: invite email to first
install in under five minutes.
