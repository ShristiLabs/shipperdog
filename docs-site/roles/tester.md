---
description: The Tester quickstart — accept your invitation, sign in to the Tester Portal, find your first app, and install your first build. Phone only, five minutes.
---

# Tester quickstart

## Who you are

You test mobile apps — the newest builds, before anybody else sees
them. Someone on the product team invited you by email; from that
invite to a running build on your phone takes under five minutes. Your
system role is **`TENANT_TESTER`**, and your entire experience is the
**Tester Portal**, a website you open on your phone (plus, if your team
uses it, a small companion installer app). There is nothing to install
up front, no account to create anywhere else, and nobody to ask for
help: the invitation email is the whole onboarding. This guide is the
standalone track — if you also work on the product team itself, use
the [other role guides](/roles/) separately.

## What you can do

Three things, and they are the whole job:

- **See the applications you are authorized to test** — one card per
  app, with its latest available build.
- **Install those builds** — download for your platform (Android or
  iOS) with install guidance that knows what kind of build it is.
- **Report issues** — bug, crash, or wish, with your device, OS, and
  the exact build attached automatically.

What you cannot do is equally deliberate: you cannot see apps nobody
granted you, and you cannot see other testers' information. Your access
is exactly what your team configured — an honest empty list means they
have not granted you anything yet, and it is never an error.

## First 15 minutes

Realistically this takes five. The screenshots come from a phone-sized
browser against a live portal — what you see here is what you will see
on your phone.

1. **Open your invitation email on your phone.** The link takes you to
   the portal's invitation page. Set a password — that is the entire
   registration. The link is single-use and expires after seven days;
   if it has gone stale, ask your team for a fresh one.

   ![The Tester Portal sign-in](/screenshots/portal-login.png)

2. **Sign in.** Use the email address the invitation was sent to and
   the password you just set. The portal address is whatever your team
   gave you — bookmark it, you will come back every time a new build
   lands.

3. **Find your first app.** The home screen is **My Applications**:
   one card per application you are authorized to install, each with
   its latest available version and which platforms have builds ready.
   Tap the card.

   ![My Applications on the Tester Portal](/screenshots/portal-home.png)

4. **Pick a build.** The version history lists builds newest first,
   each with its status badge, build number, size, and upload date.
   When you report an issue, you report it against exactly one of
   these — the link to a single version is shareable, which keeps
   "which build were you on?" out of everyone's life.

5. **Install it.** Tap **Download** for your platform. The portal
   checks whether you may have this build, then hands you a
   short-lived download together with install guidance:

   - **Android** — allow installs from this source if your phone
     asks once, then the build installs.
   - **iOS** — for ad-hoc or enterprise-signed builds your registered
     device installs directly; most iOS testing flows through
     TestFlight instead, and the guidance says so when that is the
     route.

   ![The version page with download and report](/screenshots/roles/portal-version-install.png)

   If the download offers a **telemetry token**, the build embeds the
   ShipperDog toolkit — nothing for you to do. Install it and crash
   context flows back to the team automatically.

6. **Report what you find.** **Report issue** on any version opens the
   feedback form: pick a type (bug, feedback, crash, feature request,
   UX issue, or — if you found something that should not be public —
   security issue), describe it, attach screenshots or logs if you
   have them. The app, version, build, device, and OS are attached
   for you. Your reports go to the team's testers' queue; the critical
   ones can hold a rollout outright, so early reports matter.

That is the whole loop: install, test, report, repeat as new builds
appear on your home screen.

## Where to go next

The step-by-step guides for each part of the portal:

- [Accept an invitation](/how-to/portal/accept-invite.md)
- [Browse your applications](/how-to/portal/browse-apps.md)
- [Install on iOS](/how-to/portal/install-ios.md)
- [Install on Android](/how-to/portal/install-android.md)
- [Submit feedback](/how-to/portal/feedback.md)

The full walkthrough with the mechanics behind it:
[five-minute install](/get-started/five-minute-install.md).
