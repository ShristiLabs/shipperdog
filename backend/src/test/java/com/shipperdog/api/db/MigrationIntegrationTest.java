package com.shipperdog.api.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Acceptance checks for the database foundation (issue #2): the Flyway
 * baseline applies cleanly to an empty PostgreSQL, the documented schema
 * objects exist, and re-running migrations is a no-op (restart safety).
 */
@SpringBootTest(properties = "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef")
@Testcontainers
class MigrationIntegrationTest {

    /** The 19 tables of the baseline schema (PRD section 13). */
    private static final Set<String> BASELINE_TABLES = Set.of(
            "organizations",
            "tenants",
            "applications",
            "versions",
            "artifacts",
            "testers",
            "tenant_tester_memberships",
            "tester_groups",
            "tester_group_members",
            "version_access",
            "releases",
            "release_targets",
            "release_approvals",
            "distribution",
            "environments",
            "environment_config",
            "devices",
            "feedback",
            "release_gates");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Test
    void baselineAppliesCleanlyOnEmptyDatabase() {
        Set<String> tables = new HashSet<>(jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class));

        assertThat(tables).containsAll(BASELINE_TABLES);
        assertThat(tables).contains("flyway_schema_history");
    }

    @Test
    void everyAppliedMigrationSucceeded() {
        Integer failures =
                jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success = false", Integer.class);

        assertThat(failures).isZero();
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version IS NOT NULL", Integer.class);
        assertThat(applied).isPositive();
    }

    @Test
    void migrationsAreIdempotentAcrossRestarts() {
        MigrateResult result = Flyway.configure().dataSource(dataSource).load().migrate();

        assertThat(result.migrationsExecuted)
                .as("second run must apply nothing new")
                .isZero();
    }

    @Test
    void tenantScopedTablesHaveOrganizationOrTenantIndexes() {
        Set<String> indexes = new HashSet<>(
                jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname = 'public'", String.class));

        assertThat(indexes)
                .contains(
                        "idx_tenants_organization_id",
                        "idx_applications_organization_id",
                        "idx_applications_tenant_id",
                        "idx_ttm_tester_id",
                        "idx_tgm_tester_id",
                        "idx_version_access_tester_id",
                        "idx_release_targets_tenant_id",
                        "idx_environments_organization_id",
                        "idx_devices_tester_id");
    }

    @Test
    void statusColumnsAreBackedByEnumTypes() {
        List<String> releaseStates = jdbc.queryForList(
                "SELECT e.enumlabel FROM pg_enum e "
                        + "JOIN pg_type t ON t.oid = e.enumtypid WHERE t.typname = 'release_status'",
                String.class);

        assertThat(releaseStates)
                .containsExactlyInAnyOrder(
                        "DRAFT",
                        "VALIDATING",
                        "READY",
                        "APPROVAL_PENDING",
                        "APPROVED",
                        "DEPLOYING",
                        "ACTIVE",
                        "PROMOTED",
                        "ROLLED_BACK",
                        "COMPLETED",
                        "FAILED");
    }
}
