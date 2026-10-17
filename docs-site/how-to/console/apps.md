---
description: Register and manage applications - the registry, ownership scope, authorized tenants, and the archive lifecycle.
---

# Register and manage applications

Applications are the products you distribute. The **Applications**
screen registers them and pins the store identifiers that later
artifact validation checks every upload against.

![The Applications screen](/screenshots/console-applications.png)

The ownership model — tenant-owned versus organization-shared — is
explained in [core concepts](/get-started/concepts.md).

## Two views of the registry

The listing-scope toggle above the grid switches between:

- **All applications** — every application in your organization,
  including shared applications no tenant is authorized for yet.
  This is the administrator's view.
- **By tenant** — the **Tenant (effective visibility)** picker comes
  alive; pick a tenant and see exactly what it can see: its own
  applications plus the shared ones authorized for it. This is the
  honest answer to "what does Acme get?"

## Register an application

1. Open **Applications** and click **New application**.

2. Fill in the dialog:

   - **Name** and an optional **Description**.
   - **Platforms** — iOS, Android, or both. Pick at least one.
   - **Bundle ID (iOS)** — required when iOS is selected (for
     example `com.northwind.fieldservice`).
   - **Package name (Android)** — required when Android is selected.
   - **Ownership** — **Tenant-owned** (pick the owner tenant) or
     **Organization-shared**. A tenant-owned app belongs to one
     tenant; a shared app is offered to tenants through explicit
     authorizations.

3. Click **Create**. The application appears in the grid with its
   status **Active**.

## Edit and change ownership

1. Open the application (the **View** action) and click **Edit**.

2. Names, descriptions, and per-platform identifiers edit freely —
   the dialog validates the same rules as creation.

3. Changing **Ownership** is an audited transition:

   - Converting to **organization-shared** takes effect immediately.
   - Converting a shared application to **tenant-owned** asks for
     the new owner tenant — and is refused while ACTIVE tenant
     authorizations remain. Revoke them first.

## Authorize tenants for a shared application

On a shared application's page, the **Authorized tenants** section
decides which tenants receive it:

1. Click **Authorize tenant**.
2. Pick the tenant; an optional **reason** records why.
3. **Revoke** on a row ends the authorization (confirm with an
   optional reason).

Until a tenant is authorized, a shared application simply does not
exist for that tenant's testers — no partial visibility.

## Archive and unarchive

**Archive** on the application page is reversible: the application
stays readable with its audit history but accepts no new versions or
artifacts. Confirm in the dialog naming the application; unarchive
from the same place when it returns.

**Related guides:** [versions and tags](/how-to/console/versions-and-tags.md) ·
[work with releases](/how-to/console/releases.md) ·
[app-owner quickstart](/roles/app-owner.md)
