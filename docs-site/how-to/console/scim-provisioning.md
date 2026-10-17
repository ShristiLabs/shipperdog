---
description: Configure SCIM provisioning - connect your identity provider so joins, moves, and levers flow automatically into ShipperDog.
---

# Configure SCIM provisioning

SCIM is the automation layer over membership: your identity
provider pushes users and groups, and ShipperDog keeps the rosters
in step — a joiner in the IdP becomes a member, a leaver is
deprovisioned with their accesses revoked. One live connector token
per organization; configuration by Organization Administrators
through the organization API.

The provisioning semantics — the joiner and leaver cascade — are
covered in [identity and SSO](/concepts/identity-sso.md).

## Generate the connector token

1. Generate (or rotate) the token:

   ```bash
   curl -X POST \
     "$SHIPPERDOG_API/api/v1/organizations/$ORG_ID/scim-config/token" \
     -H "Authorization: Bearer $ORG_ADMIN_TOKEN"
   ```

2. Copy the plaintext **now** — it begins with `sds_` and appears
   exactly once, in this response. Store it in your IdP's credential
   vault.

3. Rotation is atomic: a new token kills the old one in the same
   transaction. Revocation
   (`DELETE .../scim-config/token`) freezes SCIM entirely until a
   new token exists — the provisioning off-switch.

## Author the mapping rules

Write the settings and rules in one declarative state:

```bash
curl -X PUT "$SHIPPERDOG_API/api/v1/organizations/$ORG_ID/scim-config" \
  -H "Authorization: Bearer $ORG_ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
        "enabled": true,
        "groupMappings": [
          { "groupDisplayName": "shipperdog-release-managers",
            "organizationRole": "RELEASE_MANAGER" }
        ]
      }'
```

The mapping rules key on the group `displayName` your IdP pushes
and decide what each group grants — organization roles, tenant
memberships. Keep `enabled: false` while composing; flip it to go
live (an enabled connector whose rules are wrong provisions the
wrong shape — test with one group first).

## Point the IdP at ShipperDog

Every provider gets the same three facts: the SCIM base URL
`https://<your-deployment>/scim/v2`, the bearer token, and User +
Group sync enabled.

- **Microsoft Entra ID** — Enterprise application, Provisioning,
  automatic. Enable User and Group sync; group pushes carry the
  `displayName` your mapping rules key on.
- **Okta** — Application, Provisioning, SCIM 2.0.
- **Google Workspace** — sends no SCIM natively; a provisioning
  bridge supplies it (set `externalId` to the Google user id and
  send the account address as the primary email).

Discovery is automatic: Entra and Okta probe
`/scim/v2/ServiceProviderConfig` before their first write.

## Watch it work

- A joiner pushed by the IdP is provisioned through an
  externalId-first upsert: a fresh user answers `201`, a replay
  `200`.
- Group pushes map through your rules to roles and tenants.
- A **leaver** (delete, or `active: false`) triggers the deprovision
  cascade — memberships and accesses follow the user out. A
  re-provision later rebuilds the account; that doctrine is part of
  the contract.

Every leg is audited. A refused request answers SCIM's uniform 401 —
the distinct reason rides the audit line, never the response.

::: tip SSO first, SCIM second
SCIM assumes the identity story is settled — configure
[SSO](/how-to/console/sso-oidc-saml.md) first, then layer
provisioning on top so joiners land in a world they can actually
sign in to.
:::

**Related guides:** [SSO (OIDC and SAML)](/how-to/console/sso-oidc-saml.md) ·
[identity and SSO concepts](/concepts/identity-sso.md) ·
[org-admin quickstart](/roles/org-admin.md)
