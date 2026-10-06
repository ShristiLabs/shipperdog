package com.shipperdog.api.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    /** V3 (issue #4): organization management objects and vocabularies. */
    @Test
    void organizationManagementObjectsExist() {
        Set<String> tables = new HashSet<>(jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class));
        assertThat(tables).contains("organization_members", "organization_idp_configs");

        Set<String> constraints = new HashSet<>(jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE connamespace = 'public'::regnamespace", String.class));
        assertThat(constraints)
                .contains(
                        "uq_organizations_external_id",
                        "uq_organization_members_org_user",
                        "uq_organization_idp_configs_organization",
                        "fk_organization_members_organization_id",
                        "fk_organization_members_user_id",
                        "fk_user_invitations_organization_id",
                        "fk_organization_idp_configs_organization_id",
                        // V19 (issue #26) replaced the org/role pair check
                        // with the broader three-flavor scope check.
                        "ck_user_invitations_scope")
                .as("V19 replaced ck_user_invitations_org_role_pair with the scope check")
                .doesNotContain("ck_user_invitations_org_role_pair");

        Set<String> indexes = new HashSet<>(
                jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname = 'public'", String.class));
        assertThat(indexes).contains("idx_organization_members_user_id");

        List<String> orgStates = jdbc.queryForList(
                "SELECT e.enumlabel FROM pg_enum e "
                        + "JOIN pg_type t ON t.oid = e.enumtypid WHERE t.typname = 'org_status'",
                String.class);
        assertThat(orgStates).containsExactlyInAnyOrder("ACTIVE", "DISABLED", "DEACTIVATED");

        List<String> orgRoles = jdbc.queryForList(
                "SELECT e.enumlabel FROM pg_enum e "
                        + "JOIN pg_type t ON t.oid = e.enumtypid WHERE t.typname = 'org_role'",
                String.class);
        assertThat(orgRoles)
                .containsExactlyInAnyOrder(
                        "ORG_ADMIN",
                        "ORG_MEMBER",
                        "APP_OWNER",
                        "DEVELOPER",
                        "QA_ENGINEER",
                        "RELEASE_MANAGER",
                        "SECURITY_REVIEWER",
                        "COMPLIANCE_REVIEWER",
                        "VIEWER");
    }

    /** V4 (issue #5): tenant-scoped role vocabulary and memberships. */
    @Test
    void rbacObjectsExist() {
        Set<String> tables = new HashSet<>(jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class));
        assertThat(tables).contains("tenant_members");

        Set<String> constraints = new HashSet<>(jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE connamespace = 'public'::regnamespace", String.class));
        assertThat(constraints)
                .contains("uq_tenant_members_tenant_user", "fk_tenant_members_tenant_id", "fk_tenant_members_user_id");

        Set<String> indexes = new HashSet<>(
                jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname = 'public'", String.class));
        assertThat(indexes).contains("idx_tenant_members_user_id");

        List<String> tenantRoles = jdbc.queryForList(
                "SELECT e.enumlabel FROM pg_enum e "
                        + "JOIN pg_type t ON t.oid = e.enumtypid WHERE t.typname = 'tenant_role'",
                String.class);
        assertThat(tenantRoles).containsExactlyInAnyOrder("TENANT_ADMIN", "TENANT_TESTER");
    }

    /** V8 (issue #11): the membership status switch. */
    @Test
    void tenantMemberStatusSwitchExists() {
        List<String> memberStates = jdbc.queryForList(
                "SELECT e.enumlabel FROM pg_enum e "
                        + "JOIN pg_type t ON t.oid = e.enumtypid WHERE t.typname = 'tenant_member_status'",
                String.class);
        assertThat(memberStates).containsExactlyInAnyOrder("ACTIVE", "SUSPENDED");

        List<Map<String, Object>> column =
                jdbc.queryForList("SELECT column_name, is_nullable, column_default FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'tenant_members' "
                        + "AND column_name = 'status'");
        assertThat(column).hasSize(1);
        assertThat(column.getFirst().get("is_nullable"))
                .as("membership status is mandatory")
                .isEqualTo("NO");
        assertThat(String.valueOf(column.getFirst().get("column_default")))
                .as("memberships before V8 (and new ones) default to ACTIVE")
                .contains("ACTIVE");
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

    /** V5 (issue #6): the append-only audit store. */
    @Test
    void auditFrameworkObjectsExist() {
        Set<String> tables = new HashSet<>(jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class));
        assertThat(tables).contains("audit_events");

        Set<String> columns = new HashSet<>(jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'audit_events'",
                String.class));
        assertThat(columns)
                .contains(
                        "id",
                        "event_type",
                        "occurred_at",
                        "created_at",
                        "user_id",
                        "org_id",
                        "tenant_id",
                        "application_id",
                        "version_id",
                        "artifact_id",
                        "ip_address",
                        "correlation_id",
                        "reason",
                        "success",
                        "detail",
                        "before_snapshot",
                        "after_snapshot");
        assertThat(columns)
                .as("the append-only table deliberately has no updated_at (documented in V5)")
                .doesNotContain("updated_at");

        Set<String> indexes = new HashSet<>(
                jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname = 'public'", String.class));
        assertThat(indexes)
                .contains(
                        "idx_audit_events_user_id",
                        "idx_audit_events_org_id",
                        "idx_audit_events_tenant_id",
                        "idx_audit_events_occurred_at");

        List<Map<String, Object>> nullability =
                jdbc.queryForList("SELECT column_name, is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'audit_events' "
                        + "AND column_name IN ('event_type', 'occurred_at', 'success')");
        assertThat(nullability)
                .as("core audit fields are mandatory")
                .hasSize(3)
                .allSatisfy(column -> assertThat(column.get("is_nullable")).isEqualTo("NO"));
    }

    /** V19 (issue #26): the tester onboarding surface. */
    @Test
    void testerOnboardingObjectsExist() {
        List<String> testerStates = jdbc.queryForList(
                "SELECT e.enumlabel FROM pg_enum e "
                        + "JOIN pg_type t ON t.oid = e.enumtypid WHERE t.typname = 'tester_status'",
                String.class);
        assertThat(testerStates).containsExactlyInAnyOrder("INVITED", "ACTIVE", "SUSPENDED", "DISABLED");

        Map<String, Object> userIdColumn = jdbc.queryForMap("SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = 'testers' AND column_name = 'user_id'");
        assertThat(userIdColumn.get("is_nullable"))
                .as("INVITED testers exist before an account is linked (V19 widening)")
                .isEqualTo("YES");

        Set<String> constraints = new HashSet<>(jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE connamespace = 'public'::regnamespace", String.class));
        assertThat(constraints)
                .contains(
                        "fk_testers_organization_id",
                        "uq_testers_organization_email",
                        "fk_user_invitations_tester_id",
                        "ck_user_invitations_scope");

        Set<String> indexes = new HashSet<>(
                jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname = 'public'", String.class));
        assertThat(indexes).contains("uq_user_invitations_pending_tester", "idx_user_invitations_tester_id");
    }
}
