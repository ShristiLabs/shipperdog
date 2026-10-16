---
description: Quickstart for QA Engineers — the QA approval step, tester groups, feedback triage, crash-free gates, and the device fleet picture.
---

# QA Engineer quickstart

## Who you are

You are quality's working surface. Your system role is
**`QA_ENGINEER`**, and you live in the Console (with the Tester Portal
your testers see, from the other side). Two things wait on you in every
release: the **QA approval step** of the chain, and the feedback queue
— the reports your testers file, which you triage, and whose open
critical bugs can block a promotion outright. You also own tester
groups, the rings of testers releases target. If you hold other roles
too, work one track per session — the
[other role guides](/roles/) cover the rest.

## What you can do

- **Sign the QA approval** — the QA step of each release's approval
  chain is yours to approve or reject.
- **Manage tester groups** — create and shape the groups (per tenant)
  that releases target: Internal QA, UAT, Pilot, your own.
- **Triage feedback** — read every report on releases you can see, set
  severity, assign the member who works it, and move it through its
  lifecycle. Reports testers mark as security issues are not yours
  alone — see the [Security Reviewer](/roles/security-reviewer.md)
  track.
- **Watch the quality numbers** — read the release analytics your
  evidence feeds: crash-free sessions, ANR and uninstall rates, install
  success, tester engagement. The quality gates turn those numbers
  into promotion decisions; you read the gate definitions they answer
  to.
- **See everything, change the pipeline nowhere** — read-only
  visibility into environments, workflows, gates, rings, and policies.
  You judge the pipeline; the governance roles tune it.

## First 15 minutes

The screenshots come from the locally running development stack with its
seeded demo world — what you see here is what you will see there.

1. **Sign in and read the dashboard.** Your pending-actions panel leads
   with QA approvals waiting on your signature.

2. **Open the release and sign the QA step.** On the release
   dashboard's **Approvals** panel, find the QA step in the live round
   and approve or reject it. While you are there, check the **Quality
   Gates** panel — the crash-free and coverage gates read the same
   telemetry your triage works.

3. **Work the feedback queue.** The release dashboard's feedback
   section is the triage surface: every report the release's testers
   filed, with the context the platform attached (application,
   version, build, device, OS). Set severity, assign an owner, move it
   through its lifecycle — an open critical bug is a promotion blocker,
   so severity is a release decision, not bookkeeping.

   ![Feedback triage on the release dashboard](/screenshots/roles/console-qa-feedback.png)

4. **Shape a tester group.** Groups are tenant-scoped and every tenant
   starts with defaults (Internal QA, Tenant QA, UAT, Pilot, Executive
   Beta, External Beta). Rename, regroup, and populate them so the next
   release targets exactly the roster you want. (Group management is an
   API operation today; the console surface lands with the console
   how-tos — [feedback triage](/how-to/console/feedback-triage.md).)

5. **See what your testers see.** Walk the
   [five-minute install](/get-started/five-minute-install.md) once, as
   a tester would — every rough edge you find there is a bug in the
   promise your queue will hear about.

## Where to go next

Hand-off task list:

- [ ] Read [feedback analytics](/concepts/feedback-analytics.md) — how
      reports become metrics, gates, and promotion decisions
- [ ] Read [approvals and gates](/concepts/approvals-gates.md) for the
      chain you sign and the gates you feed
- [ ] Read [the artifact pipeline](/concepts/artifact-pipeline.md) to
      know what a READY build has already survived
- [ ] How-tos as they land:
      [feedback triage](/how-to/console/feedback-triage.md),
      [quality gates](/how-to/console/quality-gates.md)
- [ ] Point your testers at the [Tester quickstart](/roles/tester.md)
