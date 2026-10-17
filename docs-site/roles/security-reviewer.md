---
description: Quickstart for Security and Compliance Reviewers — the security approval step, scan-gate evidence, restricted security reports, and the audit trail.
---

# Security / Compliance Reviewer quickstart

## Who you are

You are the control the release pipeline answers to before it widens.
ShipperDog models this as two system roles on one page because the
workflow is one: **`SECURITY_REVIEWER`** signs the Security step of
each release's approval chain, **`COMPLIANCE_REVIEWER`** signs the
Compliance step (with conditional approvals where regulated features
changed). Your surface is the Console. You review gate evidence — SAST,
dependency scans, mobile security scans — read every standing rule the
pipeline runs, watch the audit trail, and you are one of the two
organization roles (with the Organization Admin) who can even see
reports testers filed as security issues — beyond the platform
administrator's audited override.

## What you can do

- **Approve or block on your step.** The Security (or Compliance)
  signature in the approval chain is exclusively yours — no other role
  can give it, and without it the release does not move.
- **Review gate evidence.** Quality gates that run security-class
  scans publish their evidence, observed values, and thresholds on the
  release dashboard; overrides of a failed gate are recorded decisions
  you can audit after the fact.
- **See the restricted reports.** Feedback filed as a security issue
  is invisible to the general queue — visible only to your role and
  the Organization Admin, and triaged by the same narrow circle.
- **Read the rules, author none.** Read-only visibility into
  environments, approval workflows, gate definitions, rings, release
  policies, and ABAC attribute policies: if you need a rule changed,
  that is a governance decision for the
  [Organization Admin](/roles/org-admin.md) — but every rule and its
  evaluation history are open to you.
- **Watch the trail.** The audit trail is immutable; the SIEM stream
  that exports it off-platform is configured by your Organization
  Admin — the delivery log and security-event notifications are yours
  to watch.

## First 15 minutes

The screenshots come from the locally running development stack with its
seeded demo world — what you see here is what you will see there.

1. **Sign in and read the dashboard.** The pending-actions panel
   personalizes to your role: the Security (or Compliance) steps
   waiting on your signature, each deep-linking to its release.

2. **Open the release waiting on you.** The release dashboard's
   **Approvals** panel shows the live round — who has signed, who is
   outstanding — with the approve and reject affordances sitting on
   the steps the round is currently waiting on (the platform enforces
   whose signature each step accepts):

   ![The release approvals as the Security Reviewer](/screenshots/roles/console-sec-approvals.png)

3. **Check the evidence before you sign.** On the **Quality Gates**
   panel, open the security-class gates: their state, the evidence
   links behind them, observed values against thresholds. A gate with
   no data says so honestly — `NO_DATA` is a refusal, not a pass; read
   [approvals and gates](/concepts/approvals-gates.md) for how the
   pipeline treats it.

4. **Check the restricted queue.** Reports testers filed as security
   issues appear in the release's feedback section for you (and your
   Organization Admin) alone — everyone else sees nothing, not even
   the existence of the row. Triage severity on that queue with the
   [QA Engineer](/roles/qa-engineer.md)'s discipline: it is the same
   surface, narrower visibility.

5. **Skim the standing rules.** Release policies and ABAC policies
   shape what can promote where — read them once so a refusal later is
   never a surprise: [authorization](/concepts/authorization.md) covers
   the decision chain; [the security model](/concepts/security-model.md)
   covers signed URLs, tenancy, and the audit posture.

## Where to go next

Hand-off task list:

- [ ] Read [the security model](/concepts/security-model.md) first
- [ ] Read [approvals and gates](/concepts/approvals-gates.md) for the
      chain you gate and the override records you audit
- [ ] Read [authorization](/concepts/authorization.md) for the
      decision chain your reviews ride on
- [ ] How-tos: [SIEM streams and the audit
      trail](/how-to/console/siem-streams.md) (configured by your
      Organization Admin)
