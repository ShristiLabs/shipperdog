-- R__dev_seed.sql
-- Development fixture: one organization, two tenants, one application.
--
-- Loaded ONLY when the seed Flyway location is active (the `dev` profile
-- adds classpath:db/seed to spring.flyway.locations; see
-- docs/database-migrations.md). Production and clean-database CI runs use
-- classpath:db/migration alone and never execute this file.
--
-- Repeatable migration with fixed UUIDs and ON CONFLICT DO NOTHING, so
-- re-running against an already-seeded database is a no-op. Later
-- integration tests rely on these stable identifiers.

INSERT INTO organizations (id, name)
VALUES ('10000000-0000-4000-8000-000000000001', 'ShipperDog Demo Org')
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
