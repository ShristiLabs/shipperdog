---
description: Run a staged tenant rollout - author the plan, advance stage by stage (or on checkpoints), and watch every channel arm.
---

# Run a staged tenant rollout

A staged tenant rollout widens a production release across its
target tenants in controlled stages — pilot tenants, then 10%, 50%,
100% — with one action that rolls the whole thing back. It is the
*tenant* axis, deliberately distinct from the store-side curves
(Apple's phased-release days, Play's user-fraction), which the
engine drives as its per-channel arms. OTA-channel releases stage
on a second axis — devices, not tenants; see
[run OTA rollouts](/how-to/console/ota-rollouts.md).

The staged block renders on the
[release dashboard](/how-to/console/releases.md) inside the
**Distribution** section.

![Staged tenant rollout on the release dashboard](/screenshots/console-staged-rollout.png)

## Author the plan

A plan is written **before the release's ACTIVE crossing** — one
plan per release, through the API:

```bash
curl -X PUT "$SHIPPERDOG_API/api/v1/distribution/releases/$RELEASE_ID/staged-rollout?organizationId=$ORG_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
        "mode": "CHECKPOINT",
        "stages": [
          { "tenants": ["<pilot-tenant-uuid>"] },
          { "percent": 10 },
          { "percent": 50 },
          { "percent": 100 }
        ]
      }'
```

- **Mode** — `AUTOMATIC` (the policy engine decides between stages)
  or `CHECKPOINT` (a Release Manager approves each stage).
- **Stages** — each is a percentage of the target population or an
  explicit tenant list. The final stage must reach every target
  tenant, and every stage must contain everything the previous one
  reached — the plan only widens.
- Cohorts are deterministic: the same plan on the same release
  always resolves the same tenants, ordered by a seeded hash.

The release's ACTIVE crossing starts the plan — stage 1 is reached
before the first dispatch serves anyone, so the very first
distribution already respects the first cohort. A release with no
plan dispatches to every target, exactly as before.

## Drive the stages

On the release dashboard's staged block you see the status chip
(`running`, `complete`, `rolled back`), the mode badge
(*checkpoint (RM approval)* or *automatic (policy-gated)*), the
progress bar with **N of M tenants reached**, and each stage's line.

To widen:

1. Click **Approve and advance** (checkpoint mode) or **Advance**
   (automatic mode — available when the policy engine clears it).
2. Each advance executes the newly reached delta across every
   channel the release dispatches on: TestFlight group assignments,
   the Play user-fraction, the platform's own download availability.
   Each arm's outcome lands on the block's timeline.

## Store-side curves ride along

Where a channel has its own curve, the plan drives it, and the
release dashboard shows the curve's own controls beside the staged
block:

- **Phased release (App Store)** — Apple's fixed 7-day availability
  curve with **Pause**, **Resume**, and **Halt (release to all)**.
- **Staged rollout (Google Play)** — the user-fraction curve with
  its **Advance** (to a chosen percent), **Halt**, **Resume**, and
  **Re-apply intent** (re-drives the store after drift) controls.

A failed store dispatch blocks every staged advancement until it is
resolved — the store-side standing surfaces as drift on the channel
distribution row.

## Roll the whole thing back

Enter the rollback **reason** in the staged block's field and click
**Roll back**. One action: reached TestFlight groups are emptied,
the Play fraction halts, Apple's phased curve halts, the platform's
own availability cuts. The reason is mandatory and the whole
timeline records it. See [roll back a release](/how-to/console/rollback.md)
for the full rollback picture.

**Related guides:** [rings and promotion](/how-to/console/rings-and-promotion.md) ·
[roll back a release](/how-to/console/rollback.md) ·
[rings and rollout concepts](/concepts/rings-and-rollout.md)
