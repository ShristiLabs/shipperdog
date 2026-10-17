---
description: Frequently asked questions about ShipperDog, answered from the system's real behavior - invitations, installs, approvals, promotion blockers, CI tokens, and what a 404 really means.
---

# FAQ

The questions people actually ask, answered from how the system really
behaves. Persona quickstarts: [all roles](/roles/index.md). Deeper
mechanics: [troubleshooting](/reference/troubleshooting.md).

## Testers

### My invitation link does not work. Now what?

Invitation links are **single-use and expire after seven days**. If
the page tells you to open the link from your email, the link was
already used or has gone stale — ask your team for a fresh invitation
and use the new link. Nothing about your account is lost; activating
through the new link is the same one-tap flow:
[accept an invitation](/how-to/portal/accept-invite.md).

### Why can't I see an application on my home screen?

The portal lists exactly what you hold **grants** for: an active
membership in the tenant, plus a grant for the application (explicit
per version, by application, by tag, or "latest N"). If a colleague
sees an app you do not, your grant or membership is the difference —
ask your QA or admin team; there is no setting on your side. See
[how access is decided](/concepts/authorization.md).

### I tapped Download but the link expired.

Download links are **short-lived by design — minutes**. That is the
signed-URL model: no storage locations, no long-lived tokens. Tap
**Download** again on the version page and a fresh link is minted for
you. If the portal instead says the build is not available, the team
pulled it or your access changed — that one is not a link problem
([iOS](/how-to/portal/install-ios.md) /
[Android](/how-to/portal/install-android.md)).

### My iPhone refuses the install.

Direct iOS installs work **only for builds your team prepared for
registered devices**. A refused install means the build was not
prepared for your device — ask your team; they can send the same
build through TestFlight instead, which has no per-device preparation.
Details: [install on iOS](/how-to/portal/install-ios.md).

### My Android download finished but nothing installed.

Android requires a one-time permission before a browser may install
apps: **Settings - Apps - your browser - Install unknown apps**,
allow from this source. You do this once per browser; then open the
downloaded file and confirm. Step-by-step:
[install on Android](/how-to/portal/install-android.md).

## Release managers

### Who approves my release?

The **approval workflow attached to the ring** decides — a named
chain of roles (ALL/ANY/conditional semantics) defined by your
organization. The production ring always requires an approval policy;
anything past a policy hold or a blocking gate stops there. The
release dashboard names the workflow and shows the round's standing:
[work with approvals](/how-to/console/approvals.md).

### Can I approve my own release?

No. The creator of a release can never approve it, **whatever roles
they hold** — the delegation rule (`APPROVAL_SELF_APPROVAL`). Ask a
holder of the required role; the error names it when you try.

### Why is my promotion blocked?

Promotion evaluates gates, approvals, tenant eligibility, and release
policies before committing the hop, and a refusal lists **every**
blocker with evidence — not just the first. Run the precheck (it
executes the same evaluation as a read), fix what it names, and
promote again. Every refusal is recorded on the promotion timeline:
[promote through rings](/how-to/console/rings-and-promotion.md).

### What does "no data yet (fail-closed)" on a gate mean?

Required gates **block when they have no result** — missing data is a
refusal, never a pass. Metric-backed gates resolve from analytics; the
others need a result with an evidence URL from your pipeline. If the
data genuinely cannot arrive in time, a release manager or
organization admin can override once, with a mandatory, audited
reason: [work with quality gates](/how-to/console/quality-gates.md).

### A release went out bad. What happens when I roll back?

The ROLLED_BACK crossing **cuts downloads at the moment of the
crossing**: a tester outside the window gets the same "no version
available" answer as one never granted — never a stale binary. The
reason you give rides the state timeline permanently; nothing is
deleted. Testers who already installed keep the installed app —
rollback stops distribution, it does not reach onto handsets:
[roll back a release](/how-to/console/rollback.md).

## Developers and CI

### My CI upload exited with code 4. What broke?

Exit 4 means the API rejected the request — the printed problem names
the exact cause: wrong file type for the platform
(`ARTIFACT_FILE_TYPE_REJECTED`), a build number already used
(`ARTIFACT_BUILD_TAKEN`), a frozen version (`VERSION_IMMUTABLE`), or
an archived application. Each has a one-line fix in
[error codes](/reference/error-codes.md#cli-and-ci).

### The pipeline suddenly exits 3 — is that my token?

Exit 3 is an authentication failure: no stored credentials or a 401.
Tokens do not silently last forever — mint a fresh personal access
token, and prefer `SHIPPERDOG_TOKEN` in CI so nothing is written to
disk. See [the CLI reference](/how-to/automation/cli-reference.md#before-the-first-command).

### What is the difference between a version and an artifact?

The **version** is the logical release-train (7.12.0); the
**artifact** is the concrete immutable binary per platform (iOS build
421, Android build 842). A version carries one artifact per platform,
and its artifact set freezes the moment any release references it —
new binaries mean a new version:
[core concepts](/get-started/concepts.md).

## Everyone

### I get a 404 for something I know exists. Bug?

Almost never. Authorization denials are **indistinguishable from
absence** — a caller without access receives the byte-identical 404 a
genuine miss produces, so the response never leaks what exists. Check
your membership/grant/role first; administrators can see the exact
denial in the audit trail. Full reasoning:
[troubleshooting](/reference/troubleshooting.md#a-404-for-something-you-know-exists).

### Where do I see exactly what shipped in a release?

The **release manifest** — frozen and immutable at approval — records
what shipped, its provenance, and its targets. Open it on the release
dashboard or `shipperdog release manifest <id>`
([CLI reference](/how-to/automation/cli-reference.md)).
