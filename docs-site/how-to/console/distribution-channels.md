---
description: Set up distribution channels - the channel catalog, the store-account credential chain from organization to app to environment, and the store-side metadata.
---

# Set up distribution channels

The **Distribution channels** screen is where store distribution
becomes real: the channel catalog tells you which adapters exist,
and the credential-delegation chain decides which store account a
release's dispatches use — with App Store and Google Play both
supported.

![The Distribution channels screen](/screenshots/console-channels.png)

How dispatch resolves a channel and its credentials is covered in
[the security model](/concepts/security-model.md).

## Read the channel catalog

The first table lists the registered channel adapters — their
display names, capabilities, credential providers, and
descriptions. The catalog is read-only: the registry owns these
facts. What you own is the accounts their dispatches resolve.

## Bind a store account (the delegation chain)

Secret material enters exactly once, through the bind dialog, and is
never echoed again — the table carries only the opaque *secret
reference* the account resolves through.

1. Click **Bind store account**.

2. Choose the **Level** — the heart of the delegation chain:

   - **Organization (the inherited default)** — every application
     and environment in the organization inherits this account.
   - **Application override** — pick the application; its dispatches
     use this account regardless of the organization default.
   - **Application + environment override** — pick both; the most
     specific binding wins.

   Resolution walks **environment → application → organization**:
   the first defined binding along that walk is the one used.

3. Fill in the **Provider** — the store's vocabulary: `apple`,
   `google-play` — an **Account name** for the table, and the
   **Secret material** itself (the field masks it): the API key or
   service-account credentials your store issued.

4. Bind. The row appears with its level chip and secret reference.

::: tip Why the chain exists
One ASC key for the whole company, a separate one for the
regulated app, a third for that app's production environment —
without re-entering secrets per release. Bind the organization
default first, then override only where a store account genuinely
differs.
:::

## Verify and rotate

- **Verify** on a binding answers exactly one question: does the
  secret reference still *resolve* through the secret store? Green
  means the credential is reachable — the store's own opinion of
  the credential surfaces when a dispatch uses it.
- **Remove** deletes the binding (the chain falls back to the next
  level). To rotate a credential, bind a new account at the same
  level and remove the old one.

## App Store listing metadata

The **App Store listing metadata** section keeps the per-locale
store facts a TestFlight/App Store dispatch can carry: pick the
application and locale, then set the **Description**, **Keywords**,
**Screenshot references**, and the **Release notes (what's new)**
for that locale. Save per locale.

## Google Play release notes

The **Google Play release notes** section holds the per-locale
*what's new* text Play dispatches carry — pick the application and
locale, write the notes, save.

## Google Play tester tracks

The **Google Play tester tracks** section maps your tenants' tester
groups to Play tracks. Pick an application under **Application
tracks** to see its Play tracks with Google's own status words
(the store-side standing, live). Then **Link a tester group to a
Play track** by picking the application, tenant, tester group, and
the Play track. When a release dispatches (or a staged rollout
widens), the mapped groups land on the mapped tracks.

**Related guides:** [staged rollout](/how-to/console/staged-rollout.md) ·
[rings and promotion](/how-to/console/rings-and-promotion.md) ·
[security model concepts](/concepts/security-model.md)
