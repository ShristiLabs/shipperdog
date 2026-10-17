---
description: Work with approvals - compose approval workflows in the Console, sign your step, reject with a reason, and restart a rejected round.
---

# Work with approvals

Approvals are the human gate on the release path: a workflow binds
sign-off steps to a release, and the release cannot cross to
APPROVED until the chain is satisfied. You compose workflows on the
**Approval workflows** screen and sign them on the
[release dashboard](/how-to/console/releases.md).

![The Approval workflows screen](/screenshots/console-approval-workflows.png)

How the engine evaluates groups and conditions is covered in
[approvals and gates](/concepts/approvals-gates.md).

## Compose an approval workflow

1. Open **Approval workflows** and click **New definition**.

2. Give it a **Binding name** (kebab-case, for example
   `two-person`) and optionally a **Starting ring** — the workflow
   binds to releases beginning on that ring.

3. Build the chain as ordered **groups**:

   - Each group carries a **Mode**: **ALL** — every step must
     approve — or **ANY** — first approval satisfies.
   - Each **step** names a **Required role** from the five-role
     vocabulary: QA, Security, Compliance, Product (App Owner), or
     Release Manager.
   - A step may narrow to a **Tenant scope** (only releases
     targeting that tenant) and carry a **Condition** —
     *environment equals* a code, or *feature-flag contains* a key.

4. Save. The list shows each definition with its chain summary.

Editing an existing workflow reopens the same dialog. Changes affect
the rounds a release opens from that point on — a running round
keeps the shape it was opened with.

## Sign your step

1. Open the release (Releases — View). The **Approvals** block shows
   the live round: its number, status, and the workflow name, with
   the **Waiting on** list naming the roles that can act.

2. On a step waiting on you, click **Approve**. Your signature —
   name and moment — records on the step.

3. **Reject** opens a dialog: the **reason is required** and is
   recorded permanently. A rejection terminates the round and blocks
   the release's progression until rework.

## Restart a rejected round

While the release sits in approval pending with a rejected round,
**Restart approval round (rework)** opens a fresh round of the same
workflow. The earlier rounds stay visible beneath the current one
with their rejection reasons — nothing is erased.

## Read the record

- Steps that approved show who signed and when.
- Skipped steps (an ANY sibling approved first) carry the skip
   rationale.
- A release with no bound policy states that plainly: it crossed
  without sign-offs, and its frozen manifest records the
  required-approver set.

**Related guides:** [work with releases](/how-to/console/releases.md) ·
[quality gates](/how-to/console/quality-gates.md) ·
[approvals and gates concepts](/concepts/approvals-gates.md)
