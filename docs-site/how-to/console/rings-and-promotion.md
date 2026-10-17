---
description: Promote through rings - customize the ring ladder, run the promotion precheck, and widen a release ring by ring.
---

# Promote through rings

Rings are the progressive-delivery ladder: `engineering` → `qa` →
`tenant-pilot` → `uat` → `production` by default. A release starts
on one ring and promotion widens it one governed hop at a time —
each hop re-checking the target ring's gates, approvals,
eligibility, and policies. You shape the ladder on the **Release
rings** screen and drive releases on the
[release dashboard](/how-to/console/releases.md).

![The Release rings screen](/screenshots/console-rings.png)

Why rings exist and how eligibility works is covered in
[rings and rollout](/concepts/rings-and-rollout.md).

## Customize the ring ladder

1. Open **Release rings**. The cards show your organization's
   effective vocabulary in progression order — **Production stays
   last**, enforced by the server.

2. The five system rings are read-only. To customize one, create
   your own ring under its code: click **New ring**, enter the
   system **Code** (lowercase kebab — a code matching a system
   entry builds your organization's shadow of it), a **Name**, and
   a **Position**.

3. Shape the ring in the same dialog:

   - **Promotion policy** — *Manual* (a person clicks) or
     *Automatic* (acts with the policy engine).
   - **Approval policy** — the workflow name that binds approvals to
     hops into this ring; **required for Production**.
   - **Distribution channel** — the channel reference its
     dispatches resolve (see
     [distribution channels](/how-to/console/distribution-channels.md)).
   - **Eligibility tenants** and **eligibility tester groups** —
     narrow who a hop into this ring may reach (empty = every
     target tenant / no group rule).

4. **Edit** reopens the dialog for organization rings; system
   shadows carry the badge of what they customize.

Each card also states its required-gate count — the gates a release
on that ring must satisfy (see
[quality gates](/how-to/console/quality-gates.md)).

## Answer "why can't I promote?" first

On the release dashboard's **Distribution** block, click **Why
can't I promote?** — the precheck runs the exact evaluation the
promote act runs, as a read:

- the verdict,
- the complete blocker list — every gate, approval, eligibility,
  and policy refusal named with its evidence,
- the target ring's gate states and the approval leg's standing.

Nothing is written and no approval round is opened; the first
promote attempt opens the round a policy demands.

## Promote, one hop at a time

1. On an ACTIVE release, click **Promote to next ring**.
2. The engine walks the release to the next ring in your
   organization's effective order — re-checking everything the
   precheck showed. The final ring carries the release to
   **PROMOTED**.
3. Any refusal stops the walk: the blocker list prints in the
   warning box and is recorded permanently on the promotion
   timeline. Fix the blocker, precheck again, promote again.

Every hop and every refusal lands on the **Promotion timeline**
with its actor, tenants moved, and evidence — the append-only record
the dashboard renders beside the state timeline.

## Promote selectively (the staged pattern)

To widen only some target tenants first — Acme this morning,
Contoso after lunch — use the CLI's selective form:

```bash
shipperdog release promote R-1028 --tenant "$ACME_ID"
```

Absent `--tenant`, the hop moves every target. For production
rollouts in percentage stages, see
[staged tenant rollout](/how-to/console/staged-rollout.md).

**Related guides:** [staged rollout](/how-to/console/staged-rollout.md) ·
[roll back a release](/how-to/console/rollback.md) ·
[rings and rollout concepts](/concepts/rings-and-rollout.md)
