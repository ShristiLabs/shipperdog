# Environments

The environment surface (S9-04, issue #58; PRD 8.11, FR-025): an
environment is a deployment context with its own configuration —
Development, QA, UAT, Pilot, Production, plus whatever custom ones an
organization adds — the WHAT a release points at, orthogonal to rings
(the WHO receives it; separate tables by design, the story's technical
note). The same immutable artifact flows Dev -> QA -> Pilot ->
Production with environment-appropriate settings: versioned
configuration snapshots, a tenant assignment allow-list validated at
release creation, and credential *references* (secret IDs only — the
database never sees secret material, PRD 25).

## Model

Environments are organization-grain (the story's scope decision,
mirroring V1's `environments.organization_id NOT NULL`): they belong
to the organization, are served by a plain repository with explicit
organization checks (the rings/testers standing — no isolation seam,
the evaluator performs the environment-to-organization hop for the
item routes), and never carry a tenant dimension.

```text
Environment pilot
Organization: ShipperDog Demo Org
Code:         pilot              (stable kebab vocabulary; the manifest's frozen value)
Name:         Pilot
Position:     3                  (the Dev -> Production progression order)
Channel:      play-internal      (name-only binding; E13 owns channels)
Policy:       two-person         (name-only binding; S10-01 owns policies)
Status:       ACTIVE             (V1's two-value lifecycle)
Config:       version 2          (the ledger's current snapshot)
```

- **Seeded defaults (AC1)**: FR-025's five — `development` (0), `qa`
  (1), `uat` (2), `pilot` (3), `production` (4) — arrive with every
  organization: V27 seeds the organizations that existed at migration
  time, and the environment module's provisioning callback seeds every
  organization the API creates afterwards, inside the creation
  transaction (the S6-02 default-group precedent). The dev seed
  fixture carries the same block for the demo organization.
- **Custom environments** add any kebab code and non-negative
  position; one code and one name per organization
  (`uq_environments_org_code` beside V1's `uq_environments_org_name`;
  a duplicate is the precise `409 ENVIRONMENT_ALREADY_EXISTS`).
- **The code is immutable** once created — it is the addressing
  vocabulary and the value the release manifest freezes; name,
  position, and the two name-only bindings move by PATCH.
- **DISABLED is the park**: a disabled environment refuses every
  mutation (reactivation is the one crossing out) and may not be
  named by a new release (`409 ENVIRONMENT_DISABLED`). There is no
  deletion — environments are distribution history.
- **Rings stay separate**: a ring is who receives; an environment is
  what the app points to. The tables never collapse.

### Tables (V27)

| Table | Shape | Notes |
|---|---|---|
| `environments` (V1 + V27) | + `code`, `position`, `distribution_channel`, `approval_policy` (all name-only bindings nullable) | org-grain; `ck_environments_code_shape` pins the kebab vocabulary |
| `environment_config_versions` | PK `(environment_id, version)`, `entries` jsonb (the full resolved map), `created_by`, `created_at` | append-only ledger; no `updated_at` (the audit_events exception); V1's unversioned `environment_config` junction stays untouched legacy scaffolding (no writer ever populated it — the `release_targets.ring_code` precedent) |
| `environment_tenants` | PK `(environment_id, tenant_id)` | the release-target allow-list; deny-by-default |
| `environment_credentials` | uuid PK, `uq (environment_id, name)`, `secret_reference` | references only; the shape check pins printable-no-whitespace |

## Routes

The collection routes carry the organization (the testers-module
precedent for org-grain resources): permission checks target the
organization (`org:environments:manage` held by ORG_ADMIN, APP_OWNER,
RELEASE_MANAGER; `org:environments:read` adds DEVELOPER,
QA_ENGINEER, SECURITY_REVIEWER, COMPLIANCE_REVIEWER, VIEWER — everyone
who reads releases reads environments), and denials conceal as the
uniform `ORGANIZATION_NOT_FOUND`. Item routes address the environment
by uuid and check the `'environment'` target — the evaluator hops to
the owning organization, and a denial conceals as
`ENVIRONMENT_NOT_FOUND`, byte-identical to a genuine miss on every
read surface (pinned by tests).

| Route | What it does |
|---|---|
| `POST /api/v1/environments` | create a custom environment (`Idempotency-Key` honored, the designated-POST registry) |
| `GET /api/v1/environments?organizationId=…` | the listing, progression order (position, code), limit/cursor, each item with its current config version |
| `GET/PATCH /api/v1/environments/{id}` | the detail; PATCH moves name/position/channel/policy (null = unchanged; the code never moves) |
| `PUT /api/v1/environments/{id}/status` | ACTIVE/DISABLED both ways, optional reason; the self-crossing is the 409 with `current`/`attempted` |
| `PUT /api/v1/environments/{id}/config` | replace-set the configuration; appends the ledger's next snapshot version |
| `GET /api/v1/environments/{id}/config[?version=N]` | the current snapshot, or the snapshot AT a version; never-configured answers version 0 + `{}` |
| `GET /api/v1/environments/{id}/config/versions` | the chronological ledger (version, key count, writer, moment) |
| `PUT /api/v1/environments/{id}/tenants` | replace-set the assignment allow-list |
| `GET /api/v1/environments/{id}/tenants` | the assigned tenants with registry summaries |
| `PUT /api/v1/environments/{id}/credentials` | replace-set the bindings (label + secret reference only) |
| `GET /api/v1/environments/{id}/credentials` | the bindings — references, never material |
| `POST /api/v1/environments/{id}/credentials/{credentialId}/verify` | ask the resolver whether the reference resolves; answers only that |

## Configuration snapshots (AC2)

A config write carries the FULL desired key-value set; the service
appends it as the ledger's next version — the snapshot IS the value
(a key disappears by its absence from the new set; history never
rewrites, and rows are never updated or deleted). Versions are
monotonic per environment from 1; writes serialize on a pessimistic
lock over the environment row, so the next version is race-free.
Keys are lowercase dotted-kebab (`api.endpoint`, `flag.dark-mode`);
feature flags and per-environment API endpoints are ordinary entries
(FR-025's "define per-environment API endpoints, feature flags, and
configuration" models as one key-value surface). Retrieval by version
answers the frozen map — the feed the S9-03 manifest consumes.

## Tenant assignments (AC4)

The assignment set is the allow-list a release creation naming the
environment validates its targets against, deny-by-default: an
environment with no assignments may target no tenant (PRD P3). Every
assigned tenant must be a member of the environment's organization —
an unknown and a foreign tenant refuse with the IDENTICAL message
(one `422 ENVIRONMENT_TENANTS_INVALID` keyed by tenant id, the
release target validation's shape). At release creation the check
composes with the S3-03 visibility rule into the same per-tenant
`422 RELEASE_TARGETS_INVALID`; naming a foreign organization's
environment conceals exactly like an unknown id.

## Credential bindings (AC3/AC5, PRD 25)

Bindings carry a label (`apple-store`, `api-signing`) and an opaque
`secret_reference` — nothing else, structurally: no surface accepts
material, so no column can hold any (the schema review's proof is the
column set itself, pinned by a test). Production is not QA because
they are different rows under different environments with different
references; the verify probe asks the `SecretReferenceResolver` port
whether a reference resolves and answers ONLY that (`resolved:
true/false`, audited — probing credentials is security-relevant). The
default resolver is the honest unbacked stub (every reference resolves
to nothing) until the secrets story wires GCP Secret Manager + Cloud
KMS; the resolution probe's material never touches the platform.

## Release integration

Creation accepts an optional `environmentId` (AC4's "when environment
specified"): the environment must belong to the application's
organization (concealed `ENVIRONMENT_NOT_FOUND`, byte-identical to
unknown), must be ACTIVE (`409 ENVIRONMENT_DISABLED`), and every
target tenant must sit inside its assignments. The release detail and
listing carry the environment's code; the creation audit carries it
beside the ring.

The manifest freeze (S9-03) fills the two blocks this story owed:
`environment` freezes the environment's CODE, and `configuration`
freezes the RESOLVED values — `configVersion` (the environment's
version at the freeze moment; 0 when never configured) and
`featureFlags` (the complete resolved key-value map — deep values,
never pointers, the #57 follow-up). `tenantConfig` stays null until a
future story models per-tenant overrides; a release naming no
environment keeps the honest nulls. Post-freeze config drift never
rewrites a frozen document.

## Error codes

See [error codes](error-codes.md) for the registry; the environment
section carries `ENVIRONMENT_NOT_FOUND` (404, also the concealed
denial on every item route), `ENVIRONMENT_ALREADY_EXISTS` (409),
`ENVIRONMENT_DISABLED` (409), `ENVIRONMENT_STATUS_INVALID` (409,
current/attempted), `ENVIRONMENT_CONFIG_VERSION_NOT_FOUND` (404), and
`ENVIRONMENT_TENANTS_INVALID` (422, per-tenant); the release surface
adds its own values of `ENVIRONMENT_NOT_FOUND` and
`ENVIRONMENT_DISABLED` (one public code, the emitting surface owns
its value).
