---
description: A guided walk-through of the ShipperDog Console and Tester Portal — every screen a release team touches, with real screenshots.
---

# Platform tour

This walk-through visits every screen a release team touches, in the
order work usually flows. The screenshots come from the locally running
development stack with its seeded demo world (an organization with two
tenants, Acme and Contoso, and a Banking application) — what you see
here is what you will see there.

## Sign in

The Console opens on the login page. Organization members sign in with
email and password, or — when your organization has single sign-on
configured — enter the organization id and continue through your
corporate provider. Sign-in failures are deliberately unhelpful: the
same message for a wrong password, an unknown account, or a locked
one.

![The Console login page](/screenshots/console-login.png)

## The organization dashboard

After signing in you land on the organization dashboard: the six
counts (applications, tenants, active releases, pending approvals,
testing, production), a release-health row per application, and — the
part worth leaving open on a second monitor — the pending-actions
panel: the approval steps waiting on *you*, the security-review queue,
and the failed gates you have authority to override, each deep-linking
straight to the release it acts on. The view refreshes every 30
seconds.

![The organization dashboard](/screenshots/console-dashboard.png)

::: tip
The navigation only shows the sections your role can use — each item
probes the API before it renders. The backend enforces every call
regardless; the navigation decides what renders, never what is
allowed.
:::

## Tenants

Tenants are the customer or business entities releases target. The
listing carries the search and status filters, and each row opens
member management (the roster with roles and membership status),
device management, and the tenant dashboard. Creation and lifecycle
changes are organization-administrator territory.

![The tenants listing](/screenshots/console-tenants.png)

## Applications

The application registry has two views of the same data: all
applications (the organization-wide administration view, including
shared applications before their first tenant authorization) and by
tenant (a tenant's effective visibility — owned plus authorized
shared). Registering an application pins its bundle id and package
name, which the artifact pipeline later verifies every build against.

![The applications registry](/screenshots/console-applications.png)

Opening an application shows its metadata, ownership scope, and — for
shared applications — the authorized-tenants screen where visibility
is granted and revoked with reasons.

![The Banking application detail](/screenshots/console-application-banking.png)

## Releases

The release listing is application-addressed: pick the application
(and tenant, if you like), and the releases appear newest first with
status chips from the lifecycle vocabulary. Filters ride the server;
ring and release-ref search filter the loaded pages.

![The Banking releases listing](/screenshots/console-releases.png)

## The release dashboard

One screen per release — the surface a Release Manager lives in:

- **Lifecycle actions** offering exactly the legal edges of the state
  machine (failure and rollback open a mandatory-reason dialog);
- **Artifacts**, flipping from the creation slots to the frozen
  content pins once the manifest exists;
- **Approvals** rendering the live round: group modes, the waiting-on
  list, recorded signatures, and the approve/reject affordances on
  exactly the decidable steps;
- **Quality gates** with per-gate states, evidence links, observed
  values against thresholds, and the audited override;
- **Distribution** (starting ring, environment, resolved targets) and
  the **promotion** engine: the promote action, the "why can't I
  promote" precheck, and the append-only promotions timeline;
- The **state timeline** and the **frozen manifest** with its digest,
  YAML compliance render, and diff against another approved release.

The screenshot shows a release waiting in `APPROVAL_PENDING`: QA has
signed, Security and the Release Manager are the waiting-on list, and
both required gates passed with evidence links.

![The release dashboard](/screenshots/console-release-detail.png)

## Rings and approval workflows

Two organization-level screens shape how releases move:

- **Release rings** — the rollout ladder. The seeded five (Engineering,
  QA, Tenant Pilot, UAT, Production — always last), plus your custom
  rings, each carrying its eligibility rules, approval-policy
  reference, promotion policy, and gate set.

  ![The rings editor](/screenshots/console-rings.png)

- **Approval workflows** — the definitions editor: ordered groups of
  steps (ALL / ANY modes, conditional steps, tenant scopes) over the
  five approval roles. The definition attached to a ring is the chain
  that release's approvals run (or, when the targeted environment
  binds a policy name, that chain instead).

  ![The approval workflows editor](/screenshots/console-approval-workflows.png)

## The Tester Portal (phone)

Testers live in a separate app on their phones. After signing in, the
home screen is **My Applications** — one card per application the
server says they can install, with the latest authorized version and
per-platform availability. Tapping a card opens the version history
with download buttons per platform.

![The Tester Portal on a phone](/screenshots/portal-home.png)

![The portal version history](/screenshots/portal-history.png)

Downloads ask the authorization engine first; an allow returns a
short-lived signed URL with platform-appropriate install guidance — and
for builds embedding the telemetry SDK, the token that pairs the
install with its release. **Report issue** on any version opens the
feedback form with the device context attached automatically. The
whole tester story is its own guide:
[five-minute install](/get-started/five-minute-install.md).

## Where to go next

- [Core concepts](/get-started/concepts.md) if you skipped it
- The role guides (in the Roles section) for persona-specific paths
- [Release lifecycle](/concepts/release-lifecycle.md),
  [approvals and gates](/concepts/approvals-gates.md), and
  [rings and rollout](/concepts/rings-and-rollout.md) for the models
  behind these screens
