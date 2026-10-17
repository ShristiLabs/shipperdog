---
description: Roll back a release - the ROLLED_BACK crossing, the staged-rollout rollback, and halting the store curves, with the mandatory reason everywhere.
---

# Roll back a release

ShipperDog offers three rollback moves, one per axis. Pick the one
that matches what went wrong; all of them demand a reason, and none
of them erases the record.

| What happened | The move | Where |
|---|---|---|
| The release itself is bad — pull it entirely | The `ROLLED_BACK` crossing | Release dashboard, Lifecycle block |
| A staged production rollout must stop widening | The staged-rollout rollback | Release dashboard, staged block |
| Only the store-side curve must pause or stop | Pause / Halt the curve | Release dashboard, curve block |

## Pull the release: ROLLED_BACK

1. Open the release (Releases — View). The **Lifecycle** block
   offers the state machine's legal edges — on an ACTIVE release,
   **Rolled back** is one of them.

2. Click it. The dialog requires a **reason** — a rolled-back
   release explains itself, and the reason rides the state timeline
   permanently.

3. Confirm. Downloads cut at the moment of the crossing: a tester
   outside the window gets the same "no version available" answer
   as one never granted — never a stale binary, and the
   [portal](/how-to/portal/browse-apps.md) simply stops offering
   the build.

`ROLLED_BACK` is terminal: the release stays readable with its full
history, but it never dispatches again. The same move exists as a
CLI verb for pipelines: `shipperdog release transition R-1028
ROLLED_BACK --reason "crash spike on Android 14"`.

::: tip Failed versus rolled back
**Failed** is the other terminal exit — for a release that never
became healthy (validation, deployment). Both demand a reason; both
keep the audit trail. Choose rolled back for "was good, must come
out"; failed for "never was good".
:::

## Stop a staged rollout

If the release is mid-[staged rollout](/how-to/console/staged-rollout.md),
the staged block's **Roll back** button is the one action that
stops everything: reached TestFlight groups emptied, the Play
user-fraction halted, Apple's phased curve halted, platform
availability cut — with your mandatory reason on the timeline.

## Halt only the store curve

When the release itself is fine but the store-side curve must stop:

- **App Store phased release** — **Halt (release to all)** completes
  Apple's 7-day availability curve immediately: every remaining
  user gets the update at once (Apple offers no binary rollback —
  the button says what it does). **Pause** / **Resume** freeze and
  continue the day-based widening.
- **Staged rollout (Google Play)** — **Halt** stops the
  user-fraction widening where it stands; **Resume** continues it;
  **Re-apply intent** re-drives the store to the intended fraction
  after drift.

## What stays behind

Everything: the state timeline's crossing with actor and reason,
the promotion timeline's record, the frozen manifest of what had
shipped, and every audit event. Compliance reads it all through the
[SIEM stream](/how-to/console/siem-streams.md). A bad release is a
fact; rollback makes it a well-documented one.

**Related guides:** [staged rollout](/how-to/console/staged-rollout.md) ·
[work with releases](/how-to/console/releases.md) ·
[release lifecycle concepts](/concepts/release-lifecycle.md)
