---
description: Identity and SSO - OIDC and SAML login flows, provisioning postures, and SCIM joiner-mover-leaver cascades.
---

# Identity and SSO

ShipperDog fits your corporate identity stack rather than replacing
it. Console users can sign in with email and password, or the whole
organization can be routed through your identity provider — OIDC and
SAML 2.0 both supported — with SCIM 2.0 provisioning automating joiners
and leavers. Testers use invitation links and passwords; the portal is
their only surface.

## Local accounts

Email and password, invitation-only registration, Argon2id password
hashing, lockout after repeated failures, single-use password resets.
Sessions are short-lived access tokens with rotating refresh tokens;
suspension, deactivation, and role demotions revoke sessions
immediately. Personal access tokens (for the CLI and CI) are shown
once and stored only as digests.

## Single sign-on

An Organization Admin configures the organization's provider — OIDC
(Google Workspace, Microsoft Entra, Okta) or SAML 2.0 — and console
users sign in through it. The flows are backend-driven:

- **OIDC** uses the authorization-code flow with PKCE. The console's
  login page probes the organization's SSO options and hands the
  browser to the provider; the verified callback returns the browser
  to the console with a one-time session code that is exchanged for
  exactly the session a local login mints — SSO sessions are local
  sessions, with the same rotation and revocation.
- **SAML 2.0** supports both SP-initiated and IdP-initiated flows. The
  platform's per-organization SP metadata document gives you the
  entityID and assertion-consumer URL to register with the IdP.
  Assertions are verified against certificates pinned from the IdP
  metadata you upload, with signature wrapping, algorithm pinning, and
  replay defenses applied before anything in the payload is acted on.

Both kinds share one identity-resolution layer:

- **Linking** — once a provider subject is linked to an account,
  logins resolve through the subject, never by re-deriving from email.
- **Provisioning** — `INVITED_ONLY` links existing members only;
  `JIT` additionally provisions password-less accounts on first login
  with a configured default role (least-privilege floor: `VIEWER`).
- **Role mapping** — group claims or attributes map provider groups to
  organization roles, applied on every login; the most privileged
  mapped group wins.
- **Session end** — OIDC back-channel logout revokes the linked
  account's sessions when the IdP says so. An optional SSO mandate
  refuses password login for members entirely, while platform
  administrators always keep local break-glass login.
- **Offboarding follows the provider** — when the provider asserts the
  configured inactive claim, the account hard-locks and sessions
  revoke.

## SCIM provisioning

The SCIM 2.0 connector carries the joiner/leaver automation: your IdP
pushes Users and Groups to ShipperDog, authenticated by a
per-organization connector bearer token (shown once, rotatable,
revocable — the provisioning freeze is just turning it off).

- **Users** provision through an externalId-keyed upsert; accounts and
  organization roles derive from the pushed state.
- **Groups** map to organization roles through mapping rules you
  author (for example, an "app-owners" group maps to `APP_OWNER`).
- **The leaver cascade** is the security-critical leg: deactivating a
  user in the IdP suspends every membership and tenant-tester
  membership the account holds, in one act — access ends at the next
  request, and a deliberate doctrine means a re-provision does not
  silently reactivate what the cascade suspended; restoration is a
  human decision.

## Testers are different on purpose

Testers onboard through invitations (see the
[five-minute install](/get-started/five-minute-install.md)), not
through your IdP. Their access is tenant membership plus explicit
version grants — see [authorization](/concepts/authorization.md) — and
their login, refresh, and token use are refused the moment their
tester record is suspended or deactivated.

## Where to go deeper

- [Security model](/concepts/security-model.md) — credential storage,
  session posture, auditing
- Developer detail: the
  [authentication](https://github.com/ShristiLabs/shipperdog/blob/main/docs/authentication.md)
  module doc (endpoints, token semantics, the OIDC/SAML flows and
  their configuration) and the
  [SCIM](https://github.com/ShristiLabs/shipperdog/blob/main/docs/scim.md)
  provisioning guide (routes, mapping rules, the leaver cascade, IdP
  setup).
