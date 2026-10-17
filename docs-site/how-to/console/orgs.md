---
description: Onboard an organization - create it on the platform API and hand it to the customer's first Organization Administrator.
---

# Onboard an organization

Every ShipperDog organization is its own walled garden: the tenants,
applications, releases, and policies inside it are invisible to every
other organization. Creating one is a platform-operator task — the
organization is born through the platform API, and everything after
that happens in the Console.

::: tip Who this is for
Platform operators only. If you received a welcome email, you are
joining an organization that already exists — start with your
[role quickstart](/roles/) instead.
:::

## Create the organization

1. Authenticate against the platform API with a token that may create
   organizations (a platform administrator's).

2. Create the organization:

   ```bash
   curl -X POST "$SHIPPERDOG_API/api/v1/organizations" \
     -H "Authorization: Bearer $PLATFORM_TOKEN" \
     -H "Content-Type: application/json" \
     -H "Idempotency-Key: onboard-northwind-001" \
     -d '{"name": "Northwind Mobile", "externalId": "northwind"}'
   ```

   The response carries the organization's `id` — quote it for every
   step that follows. A duplicate `externalId` is refused
   (`409 EXTERNAL_ID_TAKEN`); the idempotency key makes a retried
   create safe.

3. You are now the organization's first **Organization Administrator**
   — the creator's `ORG_ADMIN` membership is recorded at creation
   time. Sign in to the Console to finish the setup before handing it
   over.

## Add the customer's administrator

1. Still as the creator, add the customer's admin as a member of the
   organization and assign the `ORG_ADMIN` role (the members API, `PUT
   /api/v1/organizations/{id}/members/{userId}/role`).

2. Share the Console URL and sign-in credentials with them through
   your usual secure channel. They sign in with email and password
   until [single sign-on](/how-to/console/sso-oidc-saml.md) is
   configured for the organization.

3. Walk them through the first steps every organization needs, in
   order:

   - [Create the first tenants](/how-to/console/tenants.md)
   - [Register the first application](/how-to/console/apps.md)
   - [Invite testers](/roles/tenant-admin.md) into a tenant
   - [Bind store accounts](/how-to/console/distribution-channels.md)
     if they distribute through TestFlight or Google Play

## What shapes an organization

Once it exists, the organization's settings live across the Console
rather than on one screen:

| Setting | Where |
|---|---|
| Members and organization roles | The members API (role changes audit; the last `ORG_ADMIN` guard refuses stripping the final administrator) |
| Single sign-on (OIDC, SAML) | [SSO setup](/how-to/console/sso-oidc-saml.md) |
| User provisioning (SCIM) | [SCIM provisioning](/how-to/console/scim-provisioning.md) |
| Access narrowing (ABAC) | [ABAC policies](/how-to/console/abac-policies.md) |
| Compliance feeds | [SIEM streams and the audit trail](/how-to/console/siem-streams.md) |

Deactivating an organization later is reversible and non-destructive:
memberships stay, but roles in a deactivated organization grant
nothing until it is reactivated. Why organizations are hard-walled
this way is covered in [tenancy and
isolation](/concepts/tenancy-isolation.md).

**Related guides:** [manage tenants](/how-to/console/tenants.md) ·
[register applications](/how-to/console/apps.md) ·
[the platform tour](/get-started/platform-tour.md)
