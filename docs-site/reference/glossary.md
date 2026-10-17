---
description: The ShipperDog glossary - every term of the platform in one line, from organization to telemetry token, with links to the pages that go deeper.
---

# Glossary

The platform's vocabulary, one line per term. Each entry links to the
page that explains it properly — start at
[core concepts](/get-started/concepts.md) for the five nouns that
carry everything else.

## The object model

| Term | In one line |
|---|---|
| **Organization** | The top-level enterprise account: members, roles, rings, approval workflows, gate definitions, and policies all live here. |
| **Tenant** | A customer or business entity inside the organization, owning its testers, groups, and (usually) its applications; data is isolated per tenant by construction. |
| **Application** | A mobile app under management, registered with its bundle id (iOS) and/or package name (Android), owned by a tenant or organization-shared. |
| **Version** | A logical release-train of one application (7.12.0); carries one artifact per platform and freezes the moment any release references it. |
| **Artifact** | The concrete immutable binary a tester installs — uploaded with provenance, validated through a seven-stage pipeline, identified by SHA-256. |
| **Release** | The distribution event: one version's artifacts taken to a chosen audience through rings, approvals, and gates. |
| **Tester** | The person on the phone — linked to tenants, organized in groups, granted access to versions; uses only the [Tester Portal](/roles/tester.md). |
| **Tester group** | A named audience inside one tenant (Internal QA, Pilot, UAT) that narrows who a release targets. |
| **Environment** | An organization-level deployment context (with versioned configuration) that a release can name — for example a staging or production context. |

Deep dives: [the tenancy model](/concepts/tenancy-isolation.md) ·
[the artifact pipeline](/concepts/artifact-pipeline.md) ·
[the release lifecycle](/concepts/release-lifecycle.md)

## Access and identity

| Term | In one line |
|---|---|
| **Membership** | The link that makes a tester part of a tenant — without an active one, the tenant's apps and groups do not exist for them. |
| **Grant** | Permission for a tester to see an application's builds: explicit per version, by application, by tag, or "latest N". |
| **Authorization** | The seven-layer decision chain that every API call and download runs; only an ALLOW mints a download. |
| **Concealed denial** | The rule that a denied caller gets the byte-identical 404 a genuine miss produces — existence is information, so refusals never hint. |
| **Personal access token (PAT)** | The token that authenticates CLI and CI calls to the API; minted in the Console, shown exactly once. |
| **SSO** | Signing in through your company's identity provider (OIDC or SAML) instead of email and password. |
| **SCIM** | The provisioning protocol that pushes joiners, movers, and leavers from the identity provider into ShipperDog automatically. |
| **ABAC policy** | An attribute-based access rule (DENY or REQUIRE) evaluated inside the authorization chain, usually for tenant or release scoping. |

Deep dives: [authorization](/concepts/authorization.md) ·
[identity and SSO](/concepts/identity-sso.md) ·
[the security model](/concepts/security-model.md)

## Release governance

| Term | In one line |
|---|---|
| **Ring** | A rung of the promotion ladder (engineering, internal, beta, production...); a release sits on exactly one ring and climbs one hop at a time. |
| **Promotion** | The act of widening a release to the next ring — evaluated against gates, approvals, eligibility, and policies before it commits. |
| **Precheck** | The read-only command that runs the exact evaluation a promotion would run, returning the verdict and every blocker without writing anything. |
| **Approval workflow** | The named chain of roles (with ALL/ANY/conditional semantics) that a release must satisfy on its way through a ring. |
| **Approval round** | One pass of the workflow's decisions on one release — satisfied, rejected, or restarted after rework. |
| **Gate** | A measurable release requirement (tests passed, coverage threshold, crash-free rate) whose failed or missing state blocks, fail-closed. |
| **Evidence URL** | The mandatory http(s) link a gate result carries — the report a human can follow; a gate without proof does not exist. |
| **Override** | The governed exception that stops a failed or data-less gate from blocking — exactly once, with a mandatory, audited reason. |
| **Policy hold** | A named release-policy rule that pauses a crossing until an authorized person acknowledges it — a recorded human decision, not a silent pass. |
| **Manifest** | The frozen, immutable record of an approved release — what shipped, where it came from, and to whom it was targeted. |
| **Provenance** | The build facts captured at upload — commit, branch, pipeline, CI run URL, timestamp — so every artifact says where it was born. |
| **Rollback** | The ROLLED_BACK crossing that cuts a release's downloads at the moment of the crossing; the record is never erased. |

Deep dives: [rings and rollout](/concepts/rings-and-rollout.md) ·
[approvals and gates](/concepts/approvals-gates.md) ·
[rollback how-to](/how-to/console/rollback.md)

## Distribution

| Term | In one line |
|---|---|
| **Rollout (staged)** | A plan that widens a release's audience in stages — percentages or explicit tenant sets — each stage advancing only on plan. |
| **Cohort** | The deterministic set of tenants a rollout stage covers — the same plan on the same release always yields the same cohort. |
| **Distribution channel** | An external delivery rail (TestFlight, App Store, Google Play tracks, direct) a release dispatches through, with credentials bound per level. |
| **Signed URL** | The short-lived (minutes) download link the portal mints per tester per build — never a storage location, and it dies with the grant that minted it. |
| **Telemetry token** | The short-lived token a build carries when the team enabled telemetry — it lets the installed app report feedback and metrics without secrets. |

Deep dives: [staged rollout how-to](/how-to/console/staged-rollout.md) ·
[distribution channels](/how-to/console/distribution-channels.md) ·
[feedback and analytics](/concepts/feedback-analytics.md)

## Reading an error

| Term | In one line |
|---|---|
| **Fail-closed** | The design rule that missing data blocks rather than passes — a gate with no result is a blocking gate, not a waived one. |
| **Trace id** | The short identifier an error toast shows when present; quoting it lets an administrator join your failure to the exact server-side events. |

Deep dives: [error codes](/reference/error-codes.md) ·
[troubleshooting](/reference/troubleshooting.md)

**Next:** [error codes](/reference/error-codes.md) — the codes you can
actually see, and what to do about them.
