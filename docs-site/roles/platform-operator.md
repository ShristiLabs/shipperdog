---
description: Quickstart for platform operators — the team that runs the ShipperDog instance itself. Organization onboarding, instance health, cross-tenant operations, and break-glass.
---

# Platform Operator quickstart

## Who you are

You run the ShipperDog instance itself — the person (or team) ShristiLabs
or your own operations group trusts with the platform as a whole. Your
account carries the **platform administrator flag** (the `platform_admin`
system role): it is set on your user account directly, not granted through
an organization membership, and it works across every organization on the
instance. You onboard new organizations, watch instance health, step into
cross-tenant situations no one else can reach, and hold the break-glass
keys. If you also hold a role inside an organization, treat that as a
separate track: work one persona per session and pick the
[Organization Admin](/roles/org-admin.md) guide when you are wearing that
hat.

## What you can do

Your account holds **every permission in the catalog** — nothing in the
product is closed to you. In practice the platform-operator surface is:

- **Organizations** — create organizations and keep their configuration
  current; deactivate one to strip every role and resource access its
  members hold, in one move.
- **Cross-tenant operations** — reach into any organization's tenants for
  administrative work. Reads stay scoped to the tenant you opened; your
  cross-tenant writes are always recorded in the audit trail as explicit
  cross-tenant access events.
- **Inspection and audit** — read the platform audit trail and ask the
  authorization engine to explain any access decision (which layer of the
  chain allowed or denied it, and why) for any subject.
- **Break-glass** — override a blocking quality gate, acknowledge a
  policy hold, or reassign the last unreachable administrator. One rule
  binds even you: an organization always keeps at least one Organization
  Administrator — the last one cannot be demoted or removed, by anyone.

Everything you do is audited. The platform administrator flag is an
operations tool, not a daily-driver role; most work inside an
organization belongs to the roles that own it.

## First 15 minutes

The screenshots come from the locally running development stack with its
seeded demo world — what you see here is what you will see there.

1. **Sign in to the Console.** Use your platform account. The Console
   treats you like any other member for sign-in; your flag applies after.

   ![The Console login page](/screenshots/console-login.png)

2. **Read the organization dashboard.** You land on the dashboard of an
   organization you belong to: the counts, per-application release
   health, and the pending-actions panel. For instance-wide health this
   is your fastest single glance — check that active releases and
   pending approvals look sane.

   ![The organization dashboard](/screenshots/console-dashboard.png)

3. **Open Tenants for a cross-tenant check.** The tenants listing is
   your cross-organization rosters view: each row opens member
   management, devices, and the tenant dashboard. This is where
   "something is wrong in a customer tenant" starts.

   ![The tenants listing](/screenshots/console-tenants.png)

4. **Onboard a new organization.** Creating organizations is an
   API-level operation today (the Console renders organizations, it does
   not create them): `POST /api/v1/organizations` with your platform
   credentials, then invite the first Organization Administrator into
   it. The step-by-step guide lands with the console how-tos —
   [Onboard an organization](/how-to/console/orgs.md).

5. **Know your break-glass rules before you need them.** Gate overrides
   and policy acknowledgements demand a written reason and land in the
   audit trail with your name on them. Read
   [approvals and gates](/concepts/approvals-gates.md) so the first time
   you use that authority is not during an incident.

## Where to go next

Hand-off task list:

- [ ] Read [architecture](/concepts/architecture.md) for how the
      surfaces fit together
- [ ] Read [tenancy and isolation](/concepts/tenancy-isolation.md) —
      what your cross-tenant authority does and does not touch
- [ ] Read [authorization](/concepts/authorization.md) for the decision
      chain your inspection queries walk
- [ ] Bookmark the how-tos:
      [onboard an organization](/how-to/console/orgs.md),
      [SIEM streams and the audit trail](/how-to/console/siem-streams.md)
- [ ] Operations depth (deployment, infrastructure) lives in the
      developer docs:
      [deployment](https://github.com/ShristiLabs/shipperdog/blob/main/docs/deployment.md)
