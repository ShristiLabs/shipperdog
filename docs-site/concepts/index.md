# Concepts

Deep dives into how ShipperDog works — the models behind every screen.
Read them in any order; each stands alone and links to the others.

- [Architecture](/concepts/architecture.md) — the platform at a glance:
  three surfaces, one API, and the engines underneath
- [Tenancy and isolation](/concepts/tenancy-isolation.md) —
  organizations, tenants, and isolation by construction
- [Release lifecycle](/concepts/release-lifecycle.md) — the
  eleven-edge state machine every release walks
- [Rings and rollout](/concepts/rings-and-rollout.md) — the progressive
  distribution ladder and the governed hop
- [Approvals and gates](/concepts/approvals-gates.md) — sign-off
  chains and quality gates as configurable data
- [Artifact pipeline](/concepts/artifact-pipeline.md) — upload,
  extraction, and the seven-stage validation to READY
- [Authorization](/concepts/authorization.md) — roles, the
  seven-layer decision chain, and how testers get access
- [Identity and SSO](/concepts/identity-sso.md) — OIDC, SAML, SCIM,
  and how corporate identity fits in
- [Feedback and analytics](/concepts/feedback-analytics.md) — the
  closed loop from tester reports to promotion decisions
- [Security model](/concepts/security-model.md) — immutability,
  signed URLs, concealment, and the audit trail

New to ShipperDog? Start with [core concepts](/get-started/concepts.md)
and the [platform tour](/get-started/platform-tour.md) instead — this
section is the reference behind them.

Developer internals (API reference, module documentation, ADRs) live in
the [shipperdog repository's docs
directory](https://github.com/ShristiLabs/shipperdog/tree/main/docs);
these pages link there for depth rather than duplicating it.
