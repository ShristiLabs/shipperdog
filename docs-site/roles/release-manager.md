---
description: Quickstart for Release Managers — release creation, approvals, gates, ring promotion, staged rollout, and rollback, in the Console and the CLI.
---

# Release Manager quickstart

## Who you are

You take a build from "it exists" to "it is in production" — and back
out again when it has to be. Your system role is
**`RELEASE_MANAGER`**, and your primary surfaces are the Console's
release dashboard plus the CLI, which drives the same operations from
CI or your terminal. Release Managers own the release lifecycle:
creating releases, steering them through approvals and gates, promoting
them ring by ring, widening the rollout to more tenants, and rolling
back when the numbers say so. If you also configure the governance
those releases follow, that is the same role — you hold both; see
[what you can do](#what-you-can-do). If you additionally own
applications or administer the organization, work one track per
session: the [App Owner](/roles/app-owner.md) and
[Organization Admin](/roles/org-admin.md) guides cover those hats.

## What you can do

- **Run releases** — create them, pick the version and targets, drive
  every lifecycle transition, and read everything about any release in
  the organization.
- **Sign the final approval.** The approval chain typically runs
  QA, Security, Compliance, and Product steps — and the last signature,
  the Release Manager's own release approval, is yours.
- **Hold the two audited exceptions** — overriding a failed or
  data-less quality gate, and proceeding past a policy hold. Both
  demand a written reason and both land in the audit trail: they are
  the exception records the trail exists for.
- **Govern the pipeline** — define environments, approval workflows,
  gate definitions, release rings, declarative release policies, and
  ABAC policies: the rules your releases mechanically answer to.
- **Watch the numbers** — read the release analytics every promotion
  decision rests on, and trigger a recompute when history needs repair.
- **Work the feedback queue** — triage reports alongside QA; open
  critical bugs are the metric your promotions answer to.
- **Route notifications** — decide which events reach which channels.

## First 15 minutes

The screenshots come from the locally running development stack with its
seeded demo world — what you see here is what you will see there.

1. **Sign in and read the dashboard.** The pending-actions panel
   personalizes to you: the release approvals waiting on your signature
   and the failed gates you have authority to override. Each row
   deep-links to the release it acts on.

2. **Open the release waiting on you.** The release dashboard is one
   screen per release: lifecycle actions offering exactly the legal
   next steps, the approvals round, the quality gates, distribution
   and targets, the promotion panel, and the frozen manifest. The
   screenshot shows a release waiting in `APPROVAL_PENDING` with the
   Release Manager step outstanding and both required gates passed.

   ![The release dashboard as the Release Manager](/screenshots/roles/console-rm-release-detail.png)

3. **Sign the release approval** on the **Approvals** panel — or reject
   it with a reason. Your signature is recorded with your name and
   timestamp; the chain's progress is visible to everyone who can see
   the release.

4. **Check the gates.** The **Quality Gates** panel shows each gate's
   state, the evidence behind it, and observed values against
   thresholds. If a gate failed and you decide to ship anyway, the
   override is right there — mandatory reason, permanently audited.

5. **Promote, one ring at a time.** The promotion panel's precheck
   answers "why can't I promote?" before you ask: missing approvals,
   blocking gates, and policy holds each surface with their reason.
   Promotion widens the release to the next ring — the ladder your
   organization defined:

   ![The rings editor](/screenshots/console-rings.png)

6. **Know the exits.** Staged rollout widens tenant targeting ring by
   ring; rollback takes a release out of distribution with a mandatory
   reason. Skim both how-tos now —
   [staged rollout](/how-to/console/staged-rollout.md) and
   [rollback](/how-to/console/rollback.md) — so the first time you use
   them is not during an incident.

7. **From the terminal**, the same operations ride the CLI:
   `shipperdog release create`, `release transition`, `release
   precheck`, `release promote`, and `release manifest` — the surface
   CI pipelines drive.

## Where to go next

Hand-off task list:

- [ ] Read [the release lifecycle](/concepts/release-lifecycle.md) —
      the state machine behind every button you will press
- [ ] Read [approvals and gates](/concepts/approvals-gates.md) and
      [rings and rollout](/concepts/rings-and-rollout.md)
- [ ] Read [feedback analytics](/concepts/feedback-analytics.md) — the
      metrics your promotion decisions answer to
- [ ] How-tos as they land: [releases](/how-to/console/releases.md),
      [approvals](/how-to/console/approvals.md),
      [quality gates](/how-to/console/quality-gates.md),
      [rings and promotion](/how-to/console/rings-and-promotion.md),
      [staged rollout](/how-to/console/staged-rollout.md),
      [rollback](/how-to/console/rollback.md)
- [ ] CLI depth for CI: the
      [CLI reference](/how-to/automation/cli-reference.md)
