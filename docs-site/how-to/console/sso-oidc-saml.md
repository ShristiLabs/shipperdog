---
description: Configure SSO - connect your corporate OIDC or SAML identity provider to the organization so console users sign in through it.
---

# Configure SSO (OIDC and SAML)

Single sign-on puts your corporate identity provider in front of the
Console: users click **Continue with SSO**, your provider
(Google Workspace, Entra ID, Okta) authenticates them, and
ShipperDog admits the session. Configuration is one typed record
per provider kind — written by an Organization Administrator through
the organization API.

How the flows work — authorization codes, tokens, sessions — is
covered in [identity and SSO](/concepts/identity-sso.md).

::: tip Break-glass stays local
Platform administrators never authenticate through an organization's
provider — their email-and-password login remains the break-glass
path regardless of this configuration.
:::

## Configure OIDC

1. Prepare the provider: create an application in your IdP with
   authorization-code flow. Note the **issuer URL**, the **client
   ID**, and a **client secret**.

2. Write the organization's IdP configuration:

   ```bash
   curl -X PUT "$SHIPPERDOG_API/api/v1/organizations/$ORG_ID/idp-config" \
     -H "Authorization: Bearer $ORG_ADMIN_TOKEN" \
     -H "Content-Type: application/json" \
     -d '{
           "providerType": "OIDC",
           "issuerUrl": "https://login.example.com/realms/northwind",
           "clientId": "shipperdog-console",
           "clientSecret": "…",
           "provisioningMode": "INVITED_ONLY",
           "enabled": true
         }'
   ```

3. Choose the provisioning posture deliberately:

   - **`INVITED_ONLY`** (default) — only existing members can sign
     in through SSO; nobody is created.
   - **`JIT`** — first sign-in provisions a password-less account
     with `jitDefaultRole` (default `VIEWER`, the least-privilege
     floor) and admits existing non-members.

4. Optional hardening fields: `requireVerifiedEmail` (strict by
   default), `groupClaimName` + `roleMappings` (a group claim value
   maps to an organization role, applied on every login — the most
   privileged mapped group wins), `inactiveClaimName`.

The secret is stored once through the platform's secret store; only
an opaque reference is ever echoed again, and rotation discards the
replaced material. `enabled: false` (the default) keeps SSO off
until the record says otherwise — configure first, enable when
tested.

## Configure SAML

SAML 2.0 rides the same route with `providerType: "SAML"`:

1. Download your IdP's **metadata XML** (the EntityDescriptor).

2. Exchange service-provider metadata: your SP metadata document
   lives at
   `GET /api/v1/auth/sso/saml/{organizationId}/metadata` — register
   its entityID and its AssertionConsumerService URL with the IdP.

3. Write the configuration:

   ```bash
   curl -X PUT "$SHIPPERDOG_API/api/v1/organizations/$ORG_ID/idp-config" \
     -H "Authorization: Bearer $ORG_ADMIN_TOKEN" \
     -H "Content-Type: application/json" \
     -d '{
           "providerType": "SAML",
           "idpMetadataXml": "<EntityDescriptor>…</EntityDescriptor>",
           "wantAssertionsSigned": true,
           "provisioningMode": "INVITED_ONLY",
           "enabled": true
         }'
   ```

   Signature policy: `wantAssertionsSigned` (default true — Entra's
   shape) and `wantResponseSigned` (default false — Google Workspace
   signs the Response only; set this true and assertions false). At
   least one must be required. The read echoes the pinned
   certificates' SHA-256 fingerprints — never the metadata XML.

## Sign in

On the login page: enter the **Organization ID (single sign-on)**
and click **Continue with SSO**. The configured provider kind
dispatches; the provider's redirect lands back in the Console with a
session. SSO sessions *are* local sessions — same claims, same
revocation surfaces.

::: tip IdP-initiated logout
The OIDC Back-Channel Logout endpoint revokes the linked account's
refresh families when the IdP says the session ended — IdP-initiated
logout and IdP session expiry land on the same leg.
:::

## Linking and provisioning

Once a user has signed in through the provider, their account is
linked to that provider subject permanently — logins resolve through
the subject and never re-derive the account from email. Unlinked
first-timers match by email (refused when the email is unverified
under the strict default). For full lifecycle automation — joins,
moves, leavers — add [SCIM
provisioning](/how-to/console/scim-provisioning.md) on top.

**Related guides:** [SCIM provisioning](/how-to/console/scim-provisioning.md) ·
[identity and SSO concepts](/concepts/identity-sso.md) ·
[org-admin quickstart](/roles/org-admin.md)
