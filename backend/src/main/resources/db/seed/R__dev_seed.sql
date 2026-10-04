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

INSERT INTO applications (id, organization_id, tenant_id, name, bundle_id, package_name)
VALUES ('10000000-0000-4000-8000-000000000004',
        '10000000-0000-4000-8000-000000000001',
        '10000000-0000-4000-8000-000000000002',
        'Banking',
        'com.shipperdog.demo.banking',
        'com.shipperdog.demo.banking')
ON CONFLICT (id) DO NOTHING;
