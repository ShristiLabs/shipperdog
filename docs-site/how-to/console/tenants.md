---
description: Manage tenants - create, edit, deactivate, and manage the member roster of a tenant in the Console.
---

# Manage tenants

Tenants are your customer organizations inside your ShipperDog
organization — the teams your releases target. The **Tenants** screen
in the Console lists them all; each tenant row opens its member
roster, device registry, and tenant dashboard.

![The Tenants screen](/screenshots/console-tenants.png)

Why tenants exist as a boundary is covered in
[tenancy and isolation](/concepts/tenancy-isolation.md).

## Create a tenant

1. Open **Tenants** in the navigation drawer.

2. Click **New tenant**.

3. Fill in the dialog:

   - **Name** — the tenant's display name (1–200 characters).
   - **External ID** — the globally unique mapping to your
     customer system (1–100 characters). This is the identifier your
     billing or CRM uses; it can never collide with another
     tenant's.

4. Click **Create**. The tenant appears in the grid as **Active**.

## Find and edit tenants

- **Search** filters the loaded rows by name or external ID;
  **Status** narrows to Active or Deactivated tenants. Large lists
  page through **Load more**.
- **Edit** on a row reopens the same dialog to rename the tenant or
  correct its external ID.

## Deactivate and reactivate

Tenants are never deleted — deactivation is the reversible parking
move:

1. Click **Deactivate** on the tenant's row.
2. Confirm in the dialog. Deactivation blocks new memberships but
   keeps all data, members, and applications.
3. **Reactivate** (in the same place) reverses it whenever the
   customer returns.

## Manage a tenant's members

1. Click **Members** on the tenant's row. The roster lists each
   member's email, name, role, and status.

2. Add a member with **Add member**: pick the user from your
   organization's member roster — or paste a raw user id for tenant
   testers who are not organization members — and choose the role:

   - **Tenant Admin** — manages the tenant: members, devices, and the
     tenant dashboard.
   - **Tenant Tester** — sees the tenant's applications in the
     [Tester Portal](/roles/tester.md) and installs builds.

3. Change a role with the **Role** selector on the row — it applies
   immediately.

4. **Suspend** revokes a member's sessions at once (the row shows
   **Suspended**); **Reactivate** restores access. **Remove** takes
   the member out of the tenant with a confirmation.

::: tip Testers versus members
A tester who only installs builds needs no Console presence at all —
invite them as a tester and they live entirely in the
[Portal](/how-to/portal/accept-invite.md). Add someone as a tenant
*member* only when they need the tenant's Console views.
:::

## The per-tenant screens

- **Devices** opens the [device registry](/how-to/console/devices.md).
- **Dashboard** opens the
  [tenant dashboard](/how-to/console/analytics-and-dashboards.md) —
  in-flight releases and the seven-day download trend for that
  tenant.

**Related guides:** [manage devices](/how-to/console/devices.md) ·
[onboard an organization](/how-to/console/orgs.md) ·
[tenant-admin quickstart](/roles/tenant-admin.md)
