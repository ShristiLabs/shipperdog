---
description: Quickstart for Organization Admins — members and roles, tenants, SSO and SCIM, channel credentials, release governance, and the SIEM audit stream.
---

# Organization Admin quickstart

## Who you are

You run your organization on ShipperDog: the members and their roles,
the tenants, how people sign in, and the policies that govern every
release the organization ships. Your system role is **`ORG_ADMIN`**,
granted on your organization membership. You are the top of the
organization's own hierarchy — everything inside it is yours to
configure, everything outside it (other organizations, the instance
itself) is not your surface. If you also personally own applications or
run releases, use separate accounts or separate sessions for those
tracks — see the [App Owner](/roles/app-owner.md) and
[Release Manager](/roles/release-manager.md) guides.

## What you can do

The widest role in the catalog. In plain language:

- **People** — invite members, assign and change their roles, remove
  them. A role change that removes permissions takes effect on the
  member's very next request; removals end their sessions immediately.
- **Tenants** — create tenants, manage their settings, and (through the
  owning-organization overlay) administer members, testers, and devices
  inside every tenant your organization owns.
- **Identity** — configure single sign-on (OIDC and SAML) and SCIM
  provisioning for the organization.
- **Release governance** — define approval workflows, quality-gate
  definitions, release rings, declarative release policies, and ABAC
  attribute policies. You also hold the two audited exceptions:
  overriding a blocking gate and acknowledging a policy hold.
- **Distribution and notifications** — manage distribution channels and
  their store credentials, and decide who hears about which events.
- **The SIEM audit stream** — yours alone: configuring the export of the
  audit trail to your SIEM is deliberately restricted to Organization
  Admins; no other role holds it.
- **Oversight** — read applications, versions, and releases; triage
  feedback; run analytics recompute; see both dashboards.

A last-administrator guard protects you: your organization always keeps
at least one `ORG_ADMIN`, so the role can never be accidentally
orphaned.

## First 15 minutes

The screenshots come from the locally running development stack with its
seeded demo world — what you see here is what you will see there.

1. **Sign in and read the dashboard.** The pending-actions panel is
   personalized to your role: approval steps waiting on administrators,
   the security-review queue, and failed gates you have authority to
   override. It deep-links straight to the release each item acts on.

2. **Create a tenant.** Open **Tenants** and add the customer or
   business entity your releases will target. Each tenant starts with
   its own default tester groups. Lifecycle changes (deactivation and
   reactivation) live here too — deactivation is reversible and blocks
   new memberships while preserving all data.

   ![The tenants listing](/screenshots/console-tenants.png)

3. **Invite your people.** Organization invitations carry the role you
   want the member to hold — invite an
   [App Owner](/roles/app-owner.md), a
   [Release Manager](/roles/release-manager.md), a
   [QA Engineer](/roles/qa-engineer.md), a
   [Developer](/roles/developer.md), and your
   [reviewers](/roles/security-reviewer.md). A fresh member without a
   functional role can do nothing until you grant one — deny by
   default, by design. (Member management is an API operation today;
   the Console surface lands with the console how-tos.)

4. **Set up how the organization signs in.** SSO via OIDC or SAML, and
   SCIM provisioning so joins and leaves follow your identity provider —
   guides land in [SSO](/how-to/console/sso-oidc-saml.md) and
   [SCIM provisioning](/how-to/console/scim-provisioning.md).

5. **Route the audit trail to your SIEM.** The audit stream export is
   the one surface only you hold. Open **Audit Streams** and point it at
   your HTTPS collector or bucket — the immutable trail of every
   privileged action, shipped off-platform under your own secret
   references.

   ![The SIEM audit streams page](/screenshots/roles/console-org-audit-streams.png)

## Where to go next

Hand-off task list:

- [ ] Read [tenancy and isolation](/concepts/tenancy-isolation.md)
      before creating your first tenants
- [ ] Read [approvals and gates](/concepts/approvals-gates.md) and
      [rings and rollout](/concepts/rings-and-rollout.md), then define
      your organization's workflows, gates, and rings
- [ ] Read [identity and SSO](/concepts/identity-sso.md) for the
      authentication models you are configuring
- [ ] Decide your ABAC posture:
      [authorization](/concepts/authorization.md)
- [ ] How-tos: [tenants](/how-to/console/tenants.md),
      [SSO](/how-to/console/sso-oidc-saml.md),
      [SCIM](/how-to/console/scim-provisioning.md),
      [SIEM streams and the audit trail](/how-to/console/siem-streams.md),
      [ABAC policies](/how-to/console/abac-policies.md)
- [ ] Onboard testers with the QA Engineer's
      [tester-invitation flow](/roles/qa-engineer.md)
