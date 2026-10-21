---
description: Work with releases - create one from the CLI, follow it on the release dashboard, and read its lifecycle.
---

# Work with releases

A release is the governed act of distributing one version's READY
artifacts to a set of tenants on a starting ring. Releases are
created from the CLI or CI (the Console is their dashboard, not
their creator) and driven through a fixed
[lifecycle](/concepts/release-lifecycle.md).

![The Releases screen](/screenshots/console-releases.png)

## Create a release

From the [CLI](/how-to/automation/cli-reference.md):

```bash
shipperdog release create banking 7.12.0 \
  --tenants "$TENANT_ID" --ring engineering
```

- **Artifacts select themselves** — the newest READY artifact per
  platform of that version. Override one platform with
  `--artifact ios=<artifactId>` when a specific build must ship.
- **`--tenants`** takes the target tenant ids (comma-separated,
  repeatable); **`--groups`** optionally narrows each tenant to
  tester groups (`tenantId=groupId` or a bare group applying to
  every tenant).
- **`--ring`** is the starting ring — one of `engineering`, `qa`,
  `tenant-pilot`, `uat`, `production` until your organization
  [customizes the vocabulary](/how-to/console/rings-and-promotion.md).
- **`--environment`** optionally pins the deployment context; every
  target tenant must sit inside its assignments.

The release is born **DRAFT** and answers with its quotable id
(`R-1028`) — the reference every later command accepts.

## Find releases in the Console

1. Open **Releases** and pick the application — the listing is
   per-application.
2. Filter server-side by **Status**, **Version**, and **Target
   tenant**; the **Ring** box and **Search** filter the loaded rows
   client-side. **Load more** pages.
3. Click **View** on a release to open its dashboard — the
   deep-linkable page everything below lives on.

## Read the release dashboard

One screen, every block you need to judge a release:

| Block | What it answers |
|---|---|
| Lifecycle | The current status and the legal next transitions as buttons |
| Artifacts | The per-platform artifact pins — sha256, build, size freeze at APPROVED |
| [Quality gates](/how-to/console/quality-gates.md) | The ring's expected gate set with live states |
| [Approvals](/how-to/console/approvals.md) | The bound policy's rounds, waiting-on list, and signatures |
| [Feedback](/how-to/console/feedback-triage.md) | Tester reports and the open-critical count |
| Distribution | Targets, ring, the promotion controls, and the store channels |
| State timeline | Every crossing with its actor and moment |
| Promotion timeline | Every ring hop and every refusal with its complete blocker list |

**Compare** (top right) diffs this release's frozen manifest against
another APPROVED release of the same application — the answer to
"what exactly changed since the last one we shipped?"

## Drive the lifecycle

The Lifecycle block offers only the state machine's legal edges —
the server refuses every other crossing. Failed and rolled-back
crossings demand a reason in the dialog ("the release explains
itself").

- To walk a release forward hop by hop, see
  [rings and promotion](/how-to/console/rings-and-promotion.md).
- To widen a production rollout in stages, see
  [staged rollout](/how-to/console/staged-rollout.md); a release that
  carries an OTA bundle instead stages through
  [OTA updates](/concepts/ota-updates.md).
- To undo one, see [roll back a release](/how-to/console/rollback.md).

The same moves exist as CLI verbs (`release transition`, `release
promote`) for pipelines — see the
[CLI reference](/how-to/automation/cli-reference.md).

**Related guides:** [approvals](/how-to/console/approvals.md) ·
[rings and promotion](/how-to/console/rings-and-promotion.md) ·
[release lifecycle concepts](/concepts/release-lifecycle.md)
