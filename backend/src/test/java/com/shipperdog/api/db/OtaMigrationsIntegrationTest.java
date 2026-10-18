package com.shipperdog.api.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The S16-02 migration acceptance (issue #164): the V55-V57 OTA schema
 * applies cleanly beside its V38/V48 ancestors -- the six OTA event
 * types insert into analytics_events WITHOUT weakening any standing
 * CHECK shape, the rollout triple enforces the V48 mirrors (unique
 * plan per release, optimistic-lock version, basis-point stages), and
 * the rollups gain the nullable bundle dimension WITHOUT touching the
 * primary-key grain.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef")
@Testcontainers
class OtaMigrationsIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @Autowired
    private JdbcTemplate jdbc;

    private World world;

    /** The fixture chain the OTA tables hang off, one shared world. */
    private record World(
            UUID organization,
            UUID tenant,
            UUID application,
            UUID version,
            UUID ring,
            UUID release,
            UUID artifact,
            UUID bundle) {}

    @BeforeEach
    void setUp() {
        UUID organization = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        UUID application = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        UUID ring = UUID.randomUUID();
        UUID release = UUID.randomUUID();
        UUID artifact = UUID.randomUUID();
        UUID bundle = UUID.randomUUID();

        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'OTA Org')", organization);
        jdbc.update(
                "INSERT INTO tenants (id, organization_id, name, external_id) VALUES (?, ?, 'OTA Tenant', ?)",
                tenant,
                organization,
                "ota-external-" + tenant);
        jdbc.update(
                "INSERT INTO applications (id, organization_id, tenant_id, name, bundle_id, ownership_scope, platforms, ota_enabled)"
                        + " VALUES (?, ?, ?, 'OTA App', 'com.shipperdog.ota', 'TENANT', ARRAY['IOS'], true)",
                application,
                organization,
                tenant);
        jdbc.update(
                "INSERT INTO versions (id, application_id, organization_id, version_name, sort_key)"
                        + " VALUES (?, ?, ?, '2.0.0', '00002.00000.00000')",
                version,
                application,
                organization);
        jdbc.update(
                "INSERT INTO rings (id, organization_id, code, name, position) VALUES (?, ?, ?, 'OTA Ring', 0)",
                ring,
                organization,
                "r" + UUID.randomUUID());
        jdbc.update(
                "INSERT INTO releases (id, application_id, version_id, organization_id, starting_ring_id, status)"
                        + " VALUES (?, ?, ?, ?, ?, 'ACTIVE')",
                release,
                application,
                version,
                organization,
                ring);
        jdbc.update(
                "INSERT INTO artifacts (id, version_id, organization_id, platform, file_name, storage_key, sha256, size, build_number)"
                        + " VALUES (?, ?, ?, 'IOS', 'bundle.zip', ?, ?, 1024, 1)",
                artifact,
                version,
                organization,
                "ota/" + artifact + "/bundle.zip",
                "a".repeat(64));
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update(
                "INSERT INTO ota_bundles (id, organization_id, tenant_id, application_id, version_id, artifact_id,"
                        + " bundle_version, platform_tag, sha256, size_bytes, signature, key_id, forced, status,"
                        + " created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, '1.0.0', 'ios', ?, 1024, ?, 'key-1', false, 'SIGNED', ?, ?)",
                bundle,
                organization,
                tenant,
                application,
                version,
                artifact,
                "a".repeat(64),
                "sig-" + bundle,
                now,
                now);

        world = new World(organization, tenant, application, version, ring, release, artifact, bundle);
    }

    /** AC: the six OTA values ride the frozen vocabulary, append-only. */
    @Test
    void analyticsEventTypeGainsExactlyTheSixOtaValues() {
        List<String> values = jdbc.queryForList(
                "SELECT e.enumlabel FROM pg_enum e "
                        + "JOIN pg_type t ON t.oid = e.enumtypid "
                        + "WHERE t.typname = 'analytics_event_type' "
                        + "ORDER BY e.enumsortorder",
                String.class);

        assertThat(values)
                .containsExactly(
                        "INSTALL",
                        "SESSION",
                        "UNINSTALL",
                        "TESTER_VERDICT",
                        "OTA_CHECK",
                        "OTA_SEEN",
                        "OTA_DOWNLOAD",
                        "OTA_APPLIED",
                        "OTA_FAILED",
                        "OTA_ROLLED_BACK");
    }

    /** AC: the OTA tables exist and ota_enabled defaults to false. */
    @Test
    void otaTablesExistAndTheFlagDefaultsToFalse() {
        Set<String> tables = new HashSet<>(jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class));

        assertThat(tables)
                .contains(
                        "ota_bundles",
                        "ota_app_tokens",
                        "ota_rollout_plans",
                        "ota_rollout_stages",
                        "ota_rollout_events",
                        "ota_alert_rules",
                        "ota_alert_firings");

        Boolean enabled = jdbc.queryForObject(
                "SELECT ota_enabled FROM applications WHERE id = ?", Boolean.class, world.application);
        assertThat(enabled).isTrue();

        // The flag's permanent default: a fresh non-OTA app lands false.
        UUID plainApp = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO applications (id, organization_id, tenant_id, name, ownership_scope, platforms)"
                        + " VALUES (?, ?, ?, 'Plain', 'TENANT', ARRAY['ANDROID'])",
                plainApp,
                world.organization,
                world.tenant);
        assertThat(jdbc.queryForObject("SELECT ota_enabled FROM applications WHERE id = ?", Boolean.class, plainApp))
                .isFalse();
    }

    /**
     * THE acceptance: every OTA-shaped analytics_events insert lands --
     * the rewritten V38 CHECKs admit the six types, and the legacy
     * INSTALL shape still lands beside them.
     */
    @Test
    void otaShapedAnalyticsEventsInsertCleanly() {
        Timestamp now = Timestamp.from(Instant.now());

        insertEvent("OTA_CHECK", "UPDATE_AVAILABLE", now, null);
        insertEvent("OTA_SEEN", null, now, null);
        insertEvent("OTA_DOWNLOAD", "SUCCEEDED", now, null);
        insertEvent("OTA_APPLIED", null, now, null);
        // The open OTA failure vocabulary rides result/ota_payload rows.
        insertEvent(
                "OTA_FAILED",
                "checksum_mismatch",
                now,
                "{\"expectedSha256\":\"" + "a".repeat(64) + "\",\"reason\":\"checksum_mismatch\"}");
        insertEvent("OTA_ROLLED_BACK", null, now, null);

        // The pre-existing contract is untouched by the widening.
        insertLegacyInstall("SUCCEEDED", now);

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM analytics_events WHERE ota_bundle_id = ?", Long.class, world.bundle))
                .isEqualTo(6);
        // Scoped to this test's own world: the constraint test beside us
        // also commits a NULL-bundle legacy install into the shared
        // container -- the count must not depend on method order.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM analytics_events WHERE ota_bundle_id IS NULL AND application_id = ?",
                        Long.class,
                        world.application))
                .isEqualTo(1);
    }

    /** The widening admits the OTA family WITHOUT weakening the shapes. */
    @Test
    void rewrittenConstraintsStillEnforceTheLegacyShapes() {
        Timestamp now = Timestamp.from(Instant.now());

        // INSTALL keeps its closed result vocabulary. (A NULL result
        // passes by SQL's three-valued CHECK semantics -- exactly as
        // it did under V38; the rewrite preserves that fidelity. The
        // ingest service owns the non-null enforcement.)
        insertLegacyInstall(null, now);
        assertThatThrownBy(() -> insertLegacyInstall("UPDATE_AVAILABLE", now))
                .isInstanceOf(DataIntegrityViolationException.class);

        // SESSION keeps its aggregate contract.
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO analytics_events (organization_id, tenant_id, application_id, release_id,"
                                + " event_type, occurred_at) VALUES (?, ?, ?, ?, 'SESSION', ?)",
                        world.organization,
                        world.tenant,
                        world.application,
                        world.release,
                        now))
                .isInstanceOf(DataIntegrityViolationException.class);

        // The OTA family MUST carry bundle attribution, an object (or
        // no) payload, and a non-empty result vocabulary.
        assertThatThrownBy(() -> insertEvent("OTA_SEEN", null, now, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertLegacyInstallWithBundle("SUCCEEDED", now))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertEvent("OTA_FAILED", "", now, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertEvent("OTA_FAILED", "apply_error", now, "[1,2]"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** The rollout triple enforces its V48 mirrors. */
    @Test
    void rolloutTablesMirrorTheV48TripleShape() {
        Timestamp now = Timestamp.from(Instant.now());
        UUID plan = UUID.randomUUID();

        jdbc.update(
                "INSERT INTO ota_rollout_plans (id, organization_id, release_id, application_id, version_id,"
                        + " mode, status, current_stage, plan_salt, version, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, 'CHECKPOINT', 'RUNNING', 1, 'salt-1', 0, ?, ?)",
                plan,
                world.organization,
                world.release,
                world.application,
                world.version,
                now,
                now);

        // ONE plan per release.
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO ota_rollout_plans (id, organization_id, release_id, application_id,"
                                + " version_id, mode, status, plan_salt, created_at, updated_at)"
                                + " VALUES (?, ?, ?, ?, ?, 'AUTOMATIC', 'RUNNING', 'salt-2', ?, ?)",
                        UUID.randomUUID(),
                        world.organization,
                        world.release,
                        world.application,
                        world.version,
                        now,
                        now))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update(
                "INSERT INTO ota_rollout_stages (id, plan_id, position, basis_points, status, created_at, updated_at)"
                        + " VALUES (?, ?, 1, 500, 'REACHED', ?, ?)",
                UUID.randomUUID(),
                plan,
                now,
                now);
        jdbc.update(
                "INSERT INTO ota_rollout_stages (id, plan_id, position, basis_points, status, created_at, updated_at)"
                        + " VALUES (?, ?, 2, 10000, 'PENDING', ?, ?)",
                UUID.randomUUID(),
                plan,
                now,
                now);

        // The basis-point domain is 1..10000, and positions are unique.
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO ota_rollout_stages (id, plan_id, position, basis_points, status, created_at, updated_at)"
                                + " VALUES (?, ?, 3, 10001, 'PENDING', ?, ?)",
                        UUID.randomUUID(),
                        plan,
                        now,
                        now))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO ota_rollout_stages (id, plan_id, position, basis_points, status, created_at, updated_at)"
                                + " VALUES (?, ?, 1, 500, 'PENDING', ?, ?)",
                        UUID.randomUUID(),
                        plan,
                        now,
                        now))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update(
                "INSERT INTO ota_rollout_events (id, plan_id, action, stage_position, detail, occurred_at)"
                        + " VALUES (?, ?, 'ADVANCED', 1, 'checkpoint approved', ?)",
                UUID.randomUUID(),
                plan,
                now);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO ota_rollout_events (id, plan_id, action, occurred_at)"
                                + " VALUES (?, ?, 'PAUSED', ?)",
                        UUID.randomUUID(),
                        plan,
                        now))
                .isInstanceOf(DataIntegrityViolationException.class);

        // The alert tables hold their vocabularies.
        UUID rule = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO ota_alert_rules (id, organization_id, application_id, metric, comparator,"
                        + " threshold, window_minutes, severity, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 'ota.apply.failure', 'GTE', 5, 60, 'CRITICAL', ?, ?)",
                rule,
                world.organization,
                world.application,
                now,
                now);
        jdbc.update(
                "INSERT INTO ota_alert_firings (id, rule_id, ota_bundle_id, observed_value, detail, fired_at, created_at)"
                        + " VALUES (?, ?, ?, 7, 'window breach', ?, ?)",
                UUID.randomUUID(),
                rule,
                world.bundle,
                now,
                now);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO ota_alert_rules (id, organization_id, application_id, metric, comparator,"
                                + " threshold, window_minutes, severity, created_at, updated_at)"
                                + " VALUES (?, ?, ?, 'ota.apply.failure', 'EQ', 5, 60, 'CRITICAL', ?, ?)",
                        UUID.randomUUID(),
                        world.organization,
                        world.application,
                        now,
                        now))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** AC: the bundle status values and the manifest/signing pair shapes. */
    @Test
    void bundleAuthoringHoldsItsStatusVocabularyAndPairShapes() {
        UUID secondVersion = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO versions (id, application_id, organization_id, version_name, sort_key)"
                        + " VALUES (?, ?, ?, '2.0.1', '00002.00000.00001')",
                secondVersion,
                world.application,
                world.organization);

        // The status vocabulary is closed.
        assertThatThrownBy(() -> insertBundle(
                        UUID.randomUUID(),
                        secondVersion,
                        "1.0.1",
                        "ios",
                        "b".repeat(64),
                        2048L,
                        "sig-2",
                        "key-2",
                        "PUBLISHED"))
                .isInstanceOf(DataIntegrityViolationException.class);

        // The manifest lands as a coherent pair.
        assertThatThrownBy(() -> insertBundle(
                        UUID.randomUUID(),
                        secondVersion,
                        "1.0.2",
                        "ios",
                        "b".repeat(64),
                        null,
                        "sig-3",
                        "key-3",
                        "SIGNED"))
                .isInstanceOf(DataIntegrityViolationException.class);

        // The signature names its key id.
        assertThatThrownBy(() -> insertBundle(
                        UUID.randomUUID(),
                        secondVersion,
                        "1.0.3",
                        "ios",
                        "b".repeat(64),
                        2048L,
                        "sig-4",
                        null,
                        "SIGNED"))
                .isInstanceOf(DataIntegrityViolationException.class);

        // The platform tag is a trimmed non-empty shape.
        assertThatThrownBy(() -> insertBundle(
                        UUID.randomUUID(),
                        secondVersion,
                        "1.0.4",
                        " ",
                        "b".repeat(64),
                        2048L,
                        "sig-5",
                        "key-5",
                        "SIGNED"))
                .isInstanceOf(DataIntegrityViolationException.class);

        // A bundle owns its versions row exclusively.
        assertThatThrownBy(() -> insertBundle(
                        UUID.randomUUID(),
                        world.version,
                        "1.0.5",
                        "ios",
                        "b".repeat(64),
                        2048L,
                        "sig-6",
                        "key-6",
                        "SIGNED"))
                .isInstanceOf(DataIntegrityViolationException.class);

        // The bundle label is unique inside its application.
        assertThatThrownBy(() -> insertBundle(
                        UUID.randomUUID(),
                        secondVersion,
                        "1.0.0",
                        "ios",
                        "b".repeat(64),
                        2048L,
                        "sig-7",
                        "key-7",
                        "SIGNED"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** AC: the hashed sdc1. registry -- unique hashes, revocable rows. */
    @Test
    void appTokenRegistryIsHashUniqueAndRevocable() {
        Timestamp now = Timestamp.from(Instant.now());
        UUID token = UUID.randomUUID();

        jdbc.update(
                "INSERT INTO ota_app_tokens (id, organization_id, application_id, name, token_hash, created_at,"
                        + " updated_at) VALUES (?, ?, ?, 'Pixel 10 primary', ?, ?, ?)",
                token,
                world.organization,
                world.application,
                "c".repeat(64),
                now,
                now);

        // One registry row per issued token: the hash IS the identity.
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO ota_app_tokens (id, organization_id, application_id, name, token_hash,"
                                + " created_at, updated_at) VALUES (?, ?, ?, 'Impostor', ?, ?, ?)",
                        UUID.randomUUID(),
                        world.organization,
                        world.application,
                        "c".repeat(64),
                        now,
                        now))
                .isInstanceOf(DataIntegrityViolationException.class);

        // The label is a trimmed non-empty shape.
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO ota_app_tokens (id, organization_id, application_id, name, token_hash,"
                                + " created_at, updated_at) VALUES (?, ?, ?, ' ', ?, ?, ?)",
                        UUID.randomUUID(),
                        world.organization,
                        world.application,
                        "d".repeat(64),
                        now,
                        now))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Revocation stamps revoked_at; the row (the audit trail) stands.
        jdbc.update("UPDATE ota_app_tokens SET revoked_at = ? WHERE id = ?", now, token);
        assertThat(jdbc.queryForObject("SELECT revoked_at FROM ota_app_tokens WHERE id = ?", Timestamp.class, token))
                .isNotNull();
    }

    /** The bundle dimension lands beside an UNCHANGED rollup grain. */
    @Test
    void rollupsGainTheBundleDimensionWithoutTouchingTheGrain() {
        Timestamp bucket = Timestamp.from(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.HOURS));

        // The legacy grain: an explicit-column insert lands NULL on the
        // new dimension, exactly as before V57.
        jdbc.update(
                "INSERT INTO metric_hourly (organization_id, tenant_id, application_id, release_id, ring_id,"
                        + " metric, bucket_start, value) VALUES (?, ?, ?, ?, '00000000-0000-0000-0000-000000000000',"
                        + " 'DOWNLOADS', ?, 10)",
                world.organization,
                world.tenant,
                world.application,
                world.release,
                bucket);

        // The OTA dimension: a distinct metric name carries the bundle.
        jdbc.update(
                "INSERT INTO metric_hourly (organization_id, tenant_id, application_id, release_id, ring_id,"
                        + " metric, bucket_start, value, ota_bundle_id)"
                        + " VALUES (?, ?, ?, ?, '00000000-0000-0000-0000-000000000000', 'OTA_APPLIED', ?, 4, ?)",
                world.organization,
                world.tenant,
                world.application,
                world.release,
                bucket,
                world.bundle);

        // The PK conflict target is still the five-column grain: the
        // same (application, metric, bucket, release, ring) refuses.
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO metric_hourly (organization_id, tenant_id, application_id, release_id, ring_id,"
                                + " metric, bucket_start, value) VALUES (?, ?, ?, ?,"
                                + " '00000000-0000-0000-0000-000000000000', 'DOWNLOADS', ?, 12)",
                        world.organization,
                        world.tenant,
                        world.application,
                        world.release,
                        bucket))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject(
                        "SELECT ota_bundle_id FROM metric_hourly WHERE metric = 'DOWNLOADS'", UUID.class))
                .isNull();
        assertThat(jdbc.queryForObject(
                        "SELECT ota_bundle_id FROM metric_hourly WHERE metric = 'OTA_APPLIED'", UUID.class))
                .isEqualTo(world.bundle);
    }

    private void insertBundle(
            UUID id,
            UUID versionId,
            String bundleVersion,
            String platformTag,
            String sha256,
            Long sizeBytes,
            String signature,
            String keyId,
            String status) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update(
                "INSERT INTO ota_bundles (id, organization_id, tenant_id, application_id, version_id, artifact_id,"
                        + " bundle_version, platform_tag, sha256, size_bytes, signature, key_id, forced, status,"
                        + " created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, false, ?, ?, ?)",
                id,
                world.organization,
                world.tenant,
                world.application,
                versionId,
                world.artifact,
                bundleVersion,
                platformTag,
                sha256,
                sizeBytes,
                signature,
                keyId,
                status,
                now,
                now);
    }

    private void insertEvent(String type, String result, Timestamp occurredAt, String payload) {
        insertEvent(type, result, occurredAt, payload, world.bundle);
    }

    private void insertEvent(String type, String result, Timestamp occurredAt, String payload, UUID bundleId) {
        jdbc.update(
                "INSERT INTO analytics_events (organization_id, tenant_id, application_id, release_id,"
                        + " event_type, result, occurred_at, ota_bundle_id, ota_payload)"
                        + " VALUES (?, ?, ?, ?, ?::analytics_event_type, ?, ?, ?, ?::jsonb)",
                world.organization,
                world.tenant,
                world.application,
                world.release,
                type,
                result,
                occurredAt,
                bundleId,
                payload);
    }

    private void insertLegacyInstall(String result, Timestamp occurredAt) {
        insertLegacyInstallWithBundle(result, occurredAt, null);
    }

    private void insertLegacyInstallWithBundle(String result, Timestamp occurredAt) {
        insertLegacyInstallWithBundle(result, occurredAt, world.bundle);
    }

    private void insertLegacyInstallWithBundle(String result, Timestamp occurredAt, UUID bundleId) {
        jdbc.update(
                "INSERT INTO analytics_events (organization_id, tenant_id, application_id, release_id,"
                        + " event_type, result, occurred_at, ota_bundle_id)"
                        + " VALUES (?, ?, ?, ?, 'INSTALL', ?, ?, ?)",
                world.organization,
                world.tenant,
                world.application,
                world.release,
                result,
                occurredAt,
                bundleId);
    }
}
