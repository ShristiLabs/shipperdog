---
description: Triage tester feedback - classify severity, assign owners, resolve or reject with a note, and watch the open-critical gate respond.
---

# Triage tester feedback

Every report testers submit from the
[Portal](/how-to/portal/feedback.md) lands in the **Feedback** block
of the [release dashboard](/how-to/console/releases.md), with the
context they never had to type attached. Triage is the working of
that queue — and a triage act here is what unblocks a promotion
there.

![Tester feedback on the release dashboard](/screenshots/roles/console-qa-feedback.png)

How feedback flows into metrics and gates is covered in
[feedback and analytics](/concepts/feedback-analytics.md).

## Read the queue

The headline states the standing — `14 reports · 1 critical open` —
with the per-severity and per-status chips beneath. Each row shows
the report type, status, submitting tenant, and moment. **Details**
expands the description, the device context (auto-attached when the
tester's registered device reported it), attachments, and any
resolution note.

## Work a report

1. **Classify** — the **Severity** selector on the row sets
   `critical`, `major`, or `minor`. Classification is the triage
   act: it moves the open-critical count the moment you set it.

2. **Assign** — the **Assignee** field picks an owner from your
   organization's roster.

3. **Move the lifecycle** — only the legal edges are offered:

   - An **OPEN** report: **Triage** (you have looked at it).
   - A **TRIAGED** report: **Resolve** or **Reject** — both open a
     dialog with a required note (the resolution note, or the
     reason it is not a bug).
   - A closed report: **Re-open** when it comes back.

::: tip The promotion connection
The `open-critical-bugs` gate reads this block's open-critical count
live: resolve or re-open a report and the next promotion attempt
re-evaluates. A critical open blocks promotion — resolve the bug
instead of overriding the gate whenever you can. See
[quality gates](/how-to/console/quality-gates.md).
:::

## Security reports

Reports testers file as *Security issue* are visible only to the
team members cleared to handle security — the restricted visibility
is automatic on the [portal side](/how-to/portal/feedback.md), and
the same restriction follows the report here.

**Related guides:** [submit feedback (tester view)](/how-to/portal/feedback.md) ·
[quality gates](/how-to/console/quality-gates.md) ·
[qa-engineer quickstart](/roles/qa-engineer.md)
