-- R__dev_seed.sql
-- Development fixture: one organization, two tenants, one application,
-- and one bootstrap platform-admin user for the identity module (issue
-- #3), who also administers the demo organization (issue #4).
--
-- Loaded ONLY when the seed Flyway location is active (the `dev` profile
-- adds classpath:db/seed to spring.flyway.locations; see
-- docs/database-migrations.md). Production and clean-database CI runs use
-- classpath:db/migration alone and never execute this file.
--
-- Repeatable migration with fixed UUIDs and ON CONFLICT DO NOTHING, so
-- re-running against an already-seeded database is a no-op. Later
-- integration tests rely on these stable identifiers.

INSERT INTO organizations (id, name, external_id)
VALUES ('10000000-0000-4000-8000-000000000001', 'ShipperDog Demo Org', 'demo-shipperdog')
ON CONFLICT (id) DO NOTHING;

-- Bootstrap dev admin. Argon2id hash of the local-only password
-- "shipperdog-dev-admin" (issue #3); never valid outside local
-- development because this file never runs in production or CI.
-- Platform administrators create and administer organizations (issue #4).
INSERT INTO users (id, email, password_hash, full_name, platform_admin)
VALUES (
    '10000000-0000-4000-8000-000000000010',
    'dev@shipperdog.local',
    '$argon2id$v=19$m=19456,t=2,p=1$rcTTp+SlrHFDVHLZ2ldpgA$IrecYlooXAarJah1NUyEO0taZ+VUYgc715akqUftZU4',
    'ShipperDog Dev Admin',
    true)
ON CONFLICT (id) DO NOTHING;

-- Keeps databases seeded before issue #4 consistent with the fixture:
-- the bootstrap admin is a platform administrator.
UPDATE users SET platform_admin = true
WHERE id = '10000000-0000-4000-8000-000000000010' AND platform_admin = false;

-- The bootstrap admin administers the demo organization (issue #4).
INSERT INTO organization_members (id, organization_id, user_id, role)
VALUES (
    '10000000-0000-4000-8000-000000000020',
    '10000000-0000-4000-8000-000000000001',
    '10000000-0000-4000-8000-000000000010',
    'ORG_ADMIN')
ON CONFLICT (id) DO NOTHING;

INSERT INTO tenants (id, organization_id, name, external_id)
VALUES
    ('10000000-0000-4000-8000-000000000002',
     '10000000-0000-4000-8000-000000000001', 'Acme', 'demo-acme'),
    ('10000000-0000-4000-8000-000000000003',
     '10000000-0000-4000-8000-000000000001', 'Contoso', 'demo-contoso')
ON CONFLICT (id) DO NOTHING;

-- The demo application is tenant-owned (Acme's Banking app; the
-- ORGANIZATION-shared mode of PRD 14/S3-02 is exercised by tests).
INSERT INTO applications (id, organization_id, tenant_id, ownership_scope, name, description, platforms, bundle_id, package_name)
VALUES ('10000000-0000-4000-8000-000000000004',
        '10000000-0000-4000-8000-000000000001',
        '10000000-0000-4000-8000-000000000002',
        'TENANT',
        'Banking',
        'The demo banking application',
        '{IOS,ANDROID}',
        'com.shipperdog.demo.banking',
        'com.shipperdog.demo.banking')
ON CONFLICT (id) DO NOTHING;

-- Default tester groups for the demo tenants (issue #27, S6-02): the
-- API seeds these on every tenant creation; the seed fixture creates
-- its tenants directly, so it reproduces the same provisioning here
-- (PRD 8.9's examples; the application default list). Fixed ids per
-- tenant slot keep re-runs idempotent.
INSERT INTO tester_groups (id, tenant_id, name)
VALUES
    ('10000000-0000-4000-8000-000000001002', '10000000-0000-4000-8000-000000000002', 'Internal QA'),
    ('10000000-0000-4000-8000-000000001003', '10000000-0000-4000-8000-000000000002', 'Tenant QA'),
    ('10000000-0000-4000-8000-000000001004', '10000000-0000-4000-8000-000000000002', 'UAT'),
    ('10000000-0000-4000-8000-000000001005', '10000000-0000-4000-8000-000000000002', 'Pilot'),
    ('10000000-0000-4000-8000-000000001006', '10000000-0000-4000-8000-000000000002', 'Executive Beta'),
    ('10000000-0000-4000-8000-000000001007', '10000000-0000-4000-8000-000000000002', 'External Beta'),
    ('10000000-0000-4000-8000-000000001102', '10000000-0000-4000-8000-000000000003', 'Internal QA'),
    ('10000000-0000-4000-8000-000000001103', '10000000-0000-4000-8000-000000000003', 'Tenant QA'),
    ('10000000-0000-4000-8000-000000001104', '10000000-0000-4000-8000-000000000003', 'UAT'),
    ('10000000-0000-4000-8000-000000001105', '10000000-0000-4000-8000-000000000003', 'Pilot'),
    ('10000000-0000-4000-8000-000000001106', '10000000-0000-4000-8000-000000000003', 'Executive Beta'),
    ('10000000-0000-4000-8000-000000001107', '10000000-0000-4000-8000-000000000003', 'External Beta')
-- Bare ON CONFLICT DO NOTHING: a local database that already holds a
-- same-named group in a demo tenant (uq_tester_groups_tenant_name,
-- not the fixed ids) must not abort the repeatable migration.
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- Demo tester (issue #39, S8-05): the post-deploy smoke test's login
-- and catalog legs run as a REAL tester persona over the seeded demo
-- org — health proves the stack, login proves the identity tier, and
-- the catalog proves the authorization engine end to end (a
-- grant-linked tester seeing the Banking application). Argon2id hash
-- of the local-only password "shipperdog-dev-tester"; same policy as
-- the dev admin above — never valid outside local/emulator
-- deployments because this file never runs in production or CI.
-- ---------------------------------------------------------------------------

INSERT INTO users (id, email, password_hash, full_name, platform_admin)
VALUES (
    '10000000-0000-4000-8000-000000000011',
    'tester@shipperdog.local',
    '$argon2id$v=19$m=19456,t=2,p=1$LMk0vcFr2R69xXSQgJLtUA$y2OrDERriM2mnno/0oGMwjtswJ29vpak1kEYkb+MMKg',
    'ShipperDog Demo Tester',
    false)
ON CONFLICT (id) DO NOTHING;

-- The tester record (PRD 8.8): the organization-scoped persona this
-- user carries (V19 columns).
INSERT INTO testers (id, user_id, organization_id, email, full_name)
VALUES (
    '10000000-0000-4000-8000-000000000014',
    '10000000-0000-4000-8000-000000000011',
    '10000000-0000-4000-8000-000000000001',
    'tester@shipperdog.local',
    'ShipperDog Demo Tester')
ON CONFLICT (id) DO NOTHING;

-- Linked to the Acme demo tenant, ACTIVE membership.
INSERT INTO tenant_tester_memberships (tenant_id, tester_id)
VALUES ('10000000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-000000000014')
ON CONFLICT (tenant_id, tester_id) DO NOTHING;

-- One working-set version so the catalog card is installable (an
-- application with no version is not yet reachable, S8-02's rule).
-- Banking is tenant-owned by Acme, so the version carries that
-- ownership pair (V12/V14 columns).
INSERT INTO versions (id, application_id, organization_id, tenant_id, version_name, status, sort_key)
VALUES (
    '10000000-0000-4000-8000-000000000015',
    '10000000-0000-4000-8000-000000000004',
    '10000000-0000-4000-8000-000000000001',
    '10000000-0000-4000-8000-000000000002',
    '1.0.0',
    'ACTIVE',
    '00001.00000.00000')
ON CONFLICT (id) DO NOTHING;

-- The application-wide grant (FR-011's APPLICATION shape): granted by
-- the bootstrap dev admin; never expires; ACTIVE by the V22 default.
INSERT INTO version_access (id, tenant_id, tester_id, application_id, version_id, access_type, created_by)
VALUES (
    '10000000-0000-4000-8000-000000000016',
    '10000000-0000-4000-8000-000000000002',
    '10000000-0000-4000-8000-000000000014',
    '10000000-0000-4000-8000-000000000004',
    NULL,
    'APPLICATION',
    '10000000-0000-4000-8000-000000000010')
ON CONFLICT DO NOTHING;

-- One READY artifact so the catalog card is INSTALLABLE (the
-- no-dead-cards rule: a version without a distributable artifact
-- renders no card) and the demo download path has a real object.
-- sha256/size are of the 54-byte placeholder the compose stack also
-- mounts into the GCS emulator (infrastructure/gcs-seed/); a Cloud
-- Run demo deployment uploads a real binary through the product
-- instead (docs/deployment.md).
INSERT INTO artifacts (
    id, version_id, organization_id, tenant_id, platform, file_name,
    storage_key, sha256, size, build_number, status, uploaded_by, format)
VALUES (
    '10000000-0000-4000-8000-000000000017',
    '10000000-0000-4000-8000-000000000015',
    '10000000-0000-4000-8000-000000000001',
    '10000000-0000-4000-8000-000000000002',
    'ANDROID',
    'demo-banking-1.0.0.apk',
    '10000000-0000-4000-8000-000000000001/10000000-0000-4000-8000-000000000002/10000000-0000-4000-8000-000000000004/10000000-0000-4000-8000-000000000015/10000000-0000-4000-8000-000000000017/artifact',
    'e69df2aa3d2e19d2dee0ab224b97b3a8e9207f0a4eb267a5cd93a206b260440e',
    54,
    1,
    'READY',
    '10000000-0000-4000-8000-000000000010',
    'APK')
ON CONFLICT (id) DO NOTHING;

-- Default environments for the demo organization (issue #58, S9-04):
-- the API seeds FR-025's five on every organization creation; the seed
-- fixture creates its organization directly, so it reproduces the same
-- provisioning here (the tester-groups precedent above). Fixed ids keep
-- re-runs idempotent; bare ON CONFLICT DO NOTHING tolerates a local
-- database where V27's migration-time seeding (or the API) already
-- provisioned them under different ids.
INSERT INTO environments (id, organization_id, code, name, position)
VALUES
    ('10000000-0000-4000-8000-000000000030',
     '10000000-0000-4000-8000-000000000001', 'development', 'Development', 0),
    ('10000000-0000-4000-8000-000000000031',
     '10000000-0000-4000-8000-000000000001', 'qa', 'QA', 1),
    ('10000000-0000-4000-8000-000000000032',
     '10000000-0000-4000-8000-000000000001', 'uat', 'UAT', 2),
    ('10000000-0000-4000-8000-000000000033',
     '10000000-0000-4000-8000-000000000001', 'pilot', 'Pilot', 3),
    ('10000000-0000-4000-8000-000000000034',
     '10000000-0000-4000-8000-000000000001', 'production', 'Production', 4)
ON CONFLICT DO NOTHING;

-- Default rings for the demo organization (issue #43, S10-04): the API
-- seeds FR-017's five on every organization creation; the seed fixture
-- creates its organization directly, so it reproduces the same
-- provisioning here (the environments precedent above). Fixed ids keep
-- re-runs idempotent; bare ON CONFLICT DO NOTHING tolerates a local
-- database where V32's migration-time seeding (or the API) already
-- provisioned them under different ids.
INSERT INTO rings (id, organization_id, code, name, position, promotion_policy)
VALUES
    ('10000000-0000-4000-8000-000000000040',
     '10000000-0000-4000-8000-000000000001', 'engineering', 'Engineering', 0, 'MANUAL'),
    ('10000000-0000-4000-8000-000000000041',
     '10000000-0000-4000-8000-000000000001', 'qa', 'QA', 1, 'MANUAL'),
    ('10000000-0000-4000-8000-000000000042',
     '10000000-0000-4000-8000-000000000001', 'tenant-pilot', 'Tenant Pilot', 2, 'MANUAL'),
    ('10000000-0000-4000-8000-000000000043',
     '10000000-0000-4000-8000-000000000001', 'uat', 'UAT', 3, 'MANUAL'),
    ('10000000-0000-4000-8000-000000000044',
     '10000000-0000-4000-8000-000000000001', 'production', 'Production', 4, 'MANUAL')
ON CONFLICT DO NOTHING;
