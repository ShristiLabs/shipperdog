---
description: Quickstart for App Owners — the application registry, versions and tags, the Product approval step, release manifests, and dashboards.
---

# App Owner quickstart

## Who you are

You own one or more applications on ShipperDog — the product side of the
house. Your system role is **`APP_OWNER`**, held on your organization
membership, and your surface is the Console. You register the
application, own its version history, sign the Product step of each
release's approval chain, watch its dashboards, and decide (with tenant
administrators) who can see shared applications. You are not the one
uploading builds — that is the [Developer](/roles/developer.md) track —
but everything around the build is yours.

## What you can do

- **Own the registry entry** — register applications, pin their bundle
  id and package name (the artifact pipeline verifies every build
  against them), edit metadata, and archive when an application
  retires.
- **Own the version history** — create versions, manage tags, archive
  old versions, and revoke distribution of a bad one. History stays
  readable forever; revocation blocks new downloads, it deletes
  nothing.
- **Run releases** — create and manage releases of your applications,
  and sign the **Product approval** step of their chains.
- **Govern what your releases ride** — define environments, approval
  workflows, gate definitions, rings, release policies, and ABAC
  policies (the same governance standing the Organization Admin and
  Release Manager hold).
- **Decide visibility** — authorize and de-authorize tenants for
  organization-shared applications, and manage the version grants that
  say which testers may install what.
- **Triage feedback** on your applications, read and recompute their
  analytics, and watch their dashboards.
- **Route notifications** for the events your releases emit.

## First 15 minutes

The screenshots come from the locally running development stack with its
seeded demo world — what you see here is what you will see there.

1. **Sign in and read the dashboard.** The counts and per-application
   release health row give you the state of your products at a glance,
   and the pending-actions panel is where the Product approval steps
   waiting on your signature collect — each one a click away from the
   release it acts on. Nothing waiting means exactly that.

   ![The dashboard as an App Owner](/screenshots/roles/console-owner-dashboard.png)

2. **Register your application.** Open **Applications** and create it:
   name, platforms (iOS, Android, or both), and the bundle id / package
   name the store builds carry. Those identifiers are pinned — the
   pipeline verifies every uploaded artifact against them, so a build
   claiming to be your app but signed for another bundle never lands.

   ![The applications registry](/screenshots/console-applications.png)

3. **Create the first version.** Versions are semver-named and born as
   drafts; your [Developers](/roles/developer.md) upload the iOS and
   Android artifacts into the version's platform slots, and metadata
   extraction fills in the build details. You manage tags — `BETA`,
   `RC` — and the working-set rules that follow them.

4. **Open a release and check the manifest.** When a release reaches
   approval, its manifest freezes: the exact content pins per platform
   with digests. That manifest is what every ring of the rollout serves
   — read [the artifact pipeline](/concepts/artifact-pipeline.md) for
   what happens between upload and READY.

5. **Watch your numbers.** The application dashboards and release
   analytics show install success, crash-free sessions, and tester
   engagement per release and ring — the inputs the quality gates and
   your promotion decisions read.

## Where to go next

Hand-off task list:

- [ ] Read [core concepts](/get-started/concepts.md) if you have not
- [ ] Read [the artifact pipeline](/concepts/artifact-pipeline.md) and
      [the release lifecycle](/concepts/release-lifecycle.md)
- [ ] Read [feedback analytics](/concepts/feedback-analytics.md) for
      the dashboards you will watch
- [ ] How-tos as they land:
      [applications](/how-to/console/apps.md),
      [versions and tags](/how-to/console/versions-and-tags.md),
      [analytics and dashboards](/how-to/console/analytics-and-dashboards.md)
- [ ] Hand the [Developer quickstart](/roles/developer.md) to the team
      that ships your builds
