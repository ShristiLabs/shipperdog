---
description: The error codes users actually see - console toasts, portal messages, and CLI problems grouped by surface, what each means, and what to do about it.
---

# Error codes

Every failure surfaces as a stable code. This page covers the codes a
person actually sees — in a Console toast, a portal message, or CLI
output — grouped by surface, with what it means and what to do. The
complete registry, including codes only internal services and
store-side connectors produce, lives in the
[developer registry](https://github.com/ShristiLabs/shipperdog/blob/main/docs/error-codes.md).

## How errors reach you

- **Console** — a toast at the bottom of the screen: the code and the
  registry's one-line detail, with a **trace id** underneath. Quote the
  code and the trace id when you ask for help.
- **Tester Portal** — a toast at the bottom of the screen, the same
  shape as the Console's: code, one-line detail, trace id.
- **CLI** — the problem printed to your terminal plus a process
  [exit code](/how-to/automation/cli-reference.md#exit-codes)
  your pipeline can branch on.

## Console

The codes an operator meets in day-to-day administration.

| Code | What it means | What to do |
|---|---|---|
| `INVALID_REQUEST` | A form or payload failed validation; the error names the offending field. | Fix the named field and resubmit. |
| `UNAUTHENTICATED` / `INVALID_TOKEN` | Your session ended or the token is invalid. | Sign in again. |
| `FORBIDDEN` | You hold a role, but not the one this action demands. | Check [your role](/roles/index.md); ask an organization admin for the authority or have the right persona act. |
| `*_NOT_FOUND` | The thing you addressed does not exist **or** you are not allowed to see it — refusals are deliberately indistinguishable (see [troubleshooting](/reference/troubleshooting.md#a-404-for-something-you-know-exists)). | Verify the id; if it should exist, your access is the question — ask an administrator. |
| `RATE_LIMITED` | Too many requests; the answer carries a `Retry-After` hint. | Wait the indicated time and retry. |
| `MEMBER_EXISTS` / `*_TAKEN` / `*_EXISTS` | A uniqueness rule refused the write — name, email, bundle id, build number, or a definition that already exists. | Choose a different name/value, or edit the existing entry instead of creating a twin. |
| `LAST_ORG_ADMIN` | The last organization administrator cannot be removed. | Grant admin to someone else first. |
| `APPROVAL_SELF_APPROVAL` | You created this release, so you cannot approve it — whatever roles you hold. | The named approver role decides; see [approvals](/how-to/console/approvals.md). |
| `APPROVAL_ROLE_NOT_HELD` | The step demands a role you do not hold; the error names the required role. | Route the decision to a holder of that role. |
| `APPROVAL_STEP_OUT_OF_ORDER` | Sequential chains decide in order; this step is not the current one. | Decide the current step first. |
| `GATE_OVERRIDE_INVALID` | Only a failed or no-data gate can be overridden, exactly once. | Check the gate's state; if it passed, no override is needed. |
| `RELEASE_TRANSITION_*` | The lifecycle state machine refuses the crossing (illegal edge, missing reason). | The error names the current and attempted states; use the legal edge shown on the release dashboard. |
| `RELEASE_PROMOTION_BLOCKED` | The target ring's evaluation refused the hop; the error lists **every** blocker with evidence. | Fix the blockers (or [override a gate](/how-to/console/quality-gates.md) with a reason), then run the precheck and promote again. |
| `RELEASE_PROMOTION_RING_SKIP` / `_RING_SPLIT` / `_RING_FINAL` | The hop is not one legal ring step (skip, split targets, or already final). | Promote one ring at a time, per ring group; the final ring has no next hop. |
| `ENVIRONMENT_DISABLED` | A parked environment cannot be named by a new release. | Reactivate the environment or choose another. |
| `INTERNAL_ERROR` | Something unexpected failed on the platform side; no internals leak. | Retry once; if it repeats, report with the trace id. |

## Tester Portal

The codes a tester can meet — all of them are one screen away from a
fix that is not yours.

| Code | What it means | What to do |
|---|---|---|
| `INVALID_INVITATION` | The invitation link is expired, already used, or mistyped — links are single-use and live seven days. | Ask your team for a fresh invitation; see [accept an invitation](/how-to/portal/accept-invite.md). |
| `INVALID_CREDENTIALS` | Email or password is wrong, or the account is locked or disabled. | Re-check your password; if locked, ask your team. |
| `INVALID_TOKEN` | Your portal session ended while you were on a page. | Sign in again — your place is kept in the app list. |
| `DOWNLOAD_NOT_FOUND` | The download link lapsed (links live minutes) or the build was pulled / your access changed. | Tap Download again for a fresh link; if it persists, the team pulled the build — see [install on Android](/how-to/portal/install-android.md). |
| `FEEDBACK_RELEASE_NOT_FOUND` | The feedback could not be attached to a release you are targeted by. | Re-open the version page from your home screen and submit from there. |
| `ATTACHMENT_REJECTED` | An attachment broke the rules — extension (png/jpg/mp4/txt/log), size, count, or a duplicate name. | The error names the file; fix or drop it and resubmit. |

## CLI and CI

The codes the upload and release path prints (exit code 4) — the
[CLI reference](/how-to/automation/cli-reference.md) covers the
commands.

| Code | What it means | What to do |
|---|---|---|
| `ARTIFACT_FILE_TYPE_REJECTED` | The file is not an `.ipa`/`.apk`/`.aab` matching the declared platform. | Check the extension and the `--platform` flag. |
| `ARTIFACT_TOO_LARGE` | The binary exceeds the upload ceiling; large files must use the resumable session path. | Let the CLI take the session path (it does automatically past the threshold). |
| `ARTIFACT_BUILD_TAKEN` | This version+platform already carries that build number. | Bump the build number — artifacts are immutable, never overwritten. |
| `UPLOAD_SESSION_EXPIRED` | The resumable session timed out before finalize. | Start a fresh upload. |
| `ARTIFACT_NOT_VALIDATABLE` | Revalidation applies only to a FAILED or still-VALIDATING artifact. | Check the artifact's state first (`artifact view`). |
| `VERSION_IMMUTABLE` | The version's artifact set is frozen (terminal state or existing distribution). | Cut a new version for new binaries. |
| `APPLICATION_ARCHIVED` | The application is archived; no new versions, artifacts, or releases. | An organization admin unarchives it, or you target another app. |
| `RELEASE_ALREADY_EXISTS` | A release already exists for this application and version. | Drive the existing one (`release get`). |
| `RELEASE_TARGETS_INVALID` / `RELEASE_ARTIFACT_NOT_READY` | A target tenant/group does not hold the app, or a selected artifact is not READY. | The error lists every offending entry; fix the set in one pass. |
| `STORAGE_UNAVAILABLE` | Object storage refused the write; a retry is safe (a fresh id, never an overwrite). | Retry the upload. |
| `INGEST_WRITE_CONTENTION` | The telemetry batch collided with a concurrent write. | Retry the batch — the SDK treats the `Retry-After` hint as advisory. |

## Inbound webhooks

Codes your CI system or store integration can see in receiver
responses — uniform by design, so the response never hints which leg
refused.

| Code | What it means | What to do |
|---|---|---|
| `WEBHOOK_SIGNATURE_REJECTED` (401) | A shared-secret delivery (GitHub, Jenkins, GitLab) failed authenticity — wrong secret, bad signature, stale timestamp, or no secret configured; the reason is uniform. | Re-check the configured secret on both sides and the sender's clock; see [webhooks](/how-to/automation/webhooks.md). |
| `WEBHOOK_DELIVERY_REJECTED` (400) | A store delivery (App Store, Google Play) failed provider verification. | Re-check the provider's signed-token configuration. |
| `WEBHOOK_PAYLOAD_MALFORMED` (400) | A verified sender's body was not readable JSON. | Inspect what the provider actually posts. |
| `WEBHOOK_PAYLOAD_TOO_LARGE` (413) | The delivery exceeds the payload ceiling (default 25 MB). | Send payloads within the ceiling. |

::: tip The one rule underneath
A denial never confirms existence: for every resource, "not found"
and "not yours" are the same bytes. When a code surprises you, check
[troubleshooting](/reference/troubleshooting.md) before assuming a
bug — and when reporting, always quote the **trace id**.
:::

**Next:** [FAQ](/reference/faq.md) — the questions people actually
ask, answered from the system's real behavior.
