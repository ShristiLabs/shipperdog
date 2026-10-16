---
description: Quickstart for Tenant Admins — tenant members, portal testers, version grants, the device fleet, and the tenant dashboard, all scoped to your tenant.
---

# Tenant Admin quickstart

## Who you are

You administer **one tenant** — the customer or business entity your
organization created for your teams. Your system role is
**`TENANT_ADMIN`**, held on your membership in that tenant, and
everything you can do is scoped to it: you will never see another
tenant's data, and other tenants' administrators will never see yours.
Your daily work is the tenant's people (members and portal testers),
what those testers can install (version grants), the device fleet they
install from, and the tenant dashboard that watches it all. If you
also hold organization-scope roles, work one track per session — the
[other role guides](/roles/) cover the rest. The
organization above you is administered by the
[Organization Admin](/roles/org-admin.md) track — tenant settings like
the tenant's own name are theirs, not yours.

## What you can do

- **Manage tenant members** — add console colleagues to your tenant,
  change their roles, suspend and reactivate them. Suspension cuts
  access on the next request; reactivation restores it without
  re-inviting.
- **Manage portal testers** — link tester records to your tenant
  (that link is what makes the Tester Portal serve them), suspend a
  noisy one, or remove them. Removal keeps the history and can be
  re-linked later.
- **Control what testers can install** — version grants, in four
  shapes: exactly one version, the whole application, a moving tag, or
  the latest N builds. You can see every tester's effective access at
  any moment — including what they have lost, and when anything
  expires.
- **Watch the device fleet** — the devices your testers install from:
  identifiers, models, OS versions, owners. You can correct metadata,
  deactivate a device, and export the provisioning list (the export is
  audited).
- **Read your tenant's picture** — the applications your tenant can
  see (owned plus shared), their working-set versions, effective
  testers, and the releases currently targeting you. The tenant
  dashboard is your landing view; tenant analytics feed it.

## First 15 minutes

The screenshots come from the locally running development stack with its
seeded demo world — what you see here is what you will see there.

1. **Sign in and open your tenant dashboard.** From the tenants
   listing, your tenant's row opens the member roster, the device
   fleet, and the dashboard — the counts, the catalog your testers can
   choose from, and the releases currently targeting your tenant:

   ![The tenant dashboard](/screenshots/roles/console-tenant-dashboard.png)

2. **Meet your members.** The member roster shows roles and membership
   status. Add the colleagues who work this tenant with you; suspend
   instead of removing when someone is only rotating out — their
   history stays.

3. **Link your first tester.** A tester record (created on the
   [QA Engineer](/roles/qa-engineer.md) or Organization Admin track)
   becomes a portal user for your tenant the moment you link it. The
   tester accepts their email invitation, sets a password, and signs
   in to the portal — the flow is the
   [Tester quickstart](/roles/tester.md) your testers will walk.

4. **Grant access to a build.** Decide what the tester may install:
   the whole application, one exact version, a tag like `BETA`, or the
   newest N builds. The effective-access view shows the result live —
   including each grant's expiry, and nothing is ever silently swept:
   revoked or expired grants stay visible as history.

5. **Check the device fleet.** Once installs happen, devices register:
   the fleet listing with its per-device facts is
   [devices](/how-to/console/devices.md) — and the CSV export (your
   provisioning list) is one audited click.

## Where to go next

Hand-off task list:

- [ ] Read [tenancy and isolation](/concepts/tenancy-isolation.md) —
      what your scope guarantees and where it ends
- [ ] Read [authorization](/concepts/authorization.md) for the four
      grant shapes and the effective-access rules you administer
- [ ] Read [feedback analytics](/concepts/feedback-analytics.md) for
      the tenant metrics on your dashboard
- [ ] How-tos as they land: [devices](/how-to/console/devices.md),
      [tenants](/how-to/console/tenants.md)
- [ ] Send your testers the [Tester quickstart](/roles/tester.md)
