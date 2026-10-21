---
description: Quickstart for Developers — artifact upload with provenance, versions, CI integrations, and gate feedback, from the CLI and the Console.
---

# Developer quickstart

## Who you are

You ship the bytes. Your system role is **`DEVELOPER`**, and your
primary surfaces are the CLI and your CI pipeline, with the Console for
watching what happens to what you shipped. In the whole role catalog
the artifact-upload permission belongs to your role and the platform
administrator alone — app owners and release managers can read
artifacts, but you are the one who puts them there. You upload builds
with their provenance, watch validation settle, follow your builds
through releases, and wire the CI that does it all automatically. If
you also own applications or run releases, pick one track per session
and use the [App Owner](/roles/app-owner.md) and
[Release Manager](/roles/release-manager.md) guides for those hats.

## What you can do

- **Upload artifacts** — IPA, APK, and AAB files, directly (multipart)
  or through resumable sessions for large builds, with provenance
  captured automatically: who, from where, built when.
- **Read the pipeline** — every artifact's validation state, extracted
  metadata (build number, minimum OS, version name), and history.
- **Follow releases** — read the releases your builds ride, the gates
  that reference your evidence, and the approval chains around them.
- **See the rules** — read-only visibility into environments,
  approval workflows, gate definitions, rings, release policies, ABAC
  policies, and notification routing: everything that decides what
  happens to your build after upload, nothing you can change. Version
  tags, lifecycles, and release driving belong to the
  [App Owner](/roles/app-owner.md) and
  [Release Manager](/roles/release-manager.md) tracks.

## First 15 minutes

The screenshots come from the locally running development stack with its
seeded demo world — what you see here is what you will see there.

1. **Install and log in with the CLI.** `shipperdog login` stores your
   credentials under `~/.shipperdog` (or point `SHIPPERDOG_API` and
   `SHIPPERDOG_TOKEN` at another instance):

   ```sh
   shipperdog login
   shipperdog version list banking
   ```

2. **Upload a build.** Point the uploader at the version your App
   Owner created — by name, with provenance flags your CI can supply:

   ```sh
   shipperdog artifact upload app-release.apk --app banking --version 1.2.0 --platform android
   ```

   The upload streams (large builds ride resumable sessions direct to
   storage), lands with a SHA-256 computed in-stream, and the artifact
   is born `VALIDATING`.

3. **Watch validation settle.** Metadata extraction reads the build
   number, version name, and minimum OS out of the package; provenance
   is recorded; the state moves to `READY` — or `FAILED` with the
   reason. `shipperdog artifact view` shows the extraction results.

4. **Check the slot in the Console.** Open your application and see the
   version's platform slots carry your artifact with its build facts —
   the same page your App Owner and Release Manager watch:

   ![The Banking application detail](/screenshots/console-application-banking.png)

5. **Wire CI so this stops being manual.** The same verbs run from
   GitHub Actions, Jenkins, or GitLab — upload on tag, gate results on
   test completion. The integrations land in the automation how-tos,
   starting with the [CLI reference](/how-to/automation/cli-reference.md)
   and [GitHub Actions](/how-to/automation/github-actions.md); the
   topology is sketched in
   [architecture](/concepts/architecture.md).

## Where to go next

Hand-off task list:

- [ ] Read [the artifact pipeline](/concepts/artifact-pipeline.md) —
      what happens between your upload and READY
- [ ] Read [architecture](/concepts/architecture.md) for how CI fits in
- [ ] Read [the release lifecycle](/concepts/release-lifecycle.md) to
      know where your build goes next
- [ ] How-tos as they land:
      [CLI reference](/how-to/automation/cli-reference.md),
      [GitHub Actions](/how-to/automation/github-actions.md)
- [ ] Self-updating app? [Integrate the OTA SDK](/how-to/ota-sdk.md)
      (Kotlin or Swift) so your build checks, verifies, and applies
      OTA bundles
- [ ] Ask your App Owner for the version slot before your first upload
      (versions are created on the
      [App Owner](/roles/app-owner.md) track)
