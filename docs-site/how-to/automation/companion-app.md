---
description: The ShipperDog Android companion app - an optional preview installer for testers on Android.
---

# Android companion app

The companion app is an optional Android installer for ShipperDog
testing. It is a **preview** — a working prototype, not broadly
available: unless your team explicitly hands you the app and asks
you to use it, you do not need it. The
[Tester Portal](/how-to/portal/browse-apps.md) in your phone's
browser is the standard way to install builds, and everything in the
portal guides works without the companion.

## What it does

For a tester whose team provides it:

- **Sign in once** with your regular portal email and password.
- **See your apps** — the same authorized builds the portal shows,
  refreshed live every time you open it.
- **Install in one tap** — tap Install on a build and the app
  handles the download; Android still shows its own install
  confirmation (every install on Android asks, by design).
- **Spot updates** — the list marks which builds are installed and
  which have an update available.
- **Report issues** — the Report action opens the portal's
  [feedback form](/how-to/portal/feedback.md) for that build.

Android only — Apple's rules give iOS installs a single road
(TestFlight), so there is no iOS companion.

## Why "preview"

The companion started as a time-boxed prototype to answer one
question: is a dedicated installer worth building, or does the
browser portal serve testers well enough? The prototype works end
to end, but the team decided not to polish and announce it until
real usage shows testers need it. The decision and the conditions
that would flip it are on record in the
[companion spike decision memo](https://github.com/ShristiLabs/shipperdog/blob/main/docs/companion-spike.md).

**See also:** the portal way — [install on Android](/how-to/portal/install-android.md).
