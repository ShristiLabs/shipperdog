package com.shipperdog.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * Acceptance checks for the V2 identity migration (issue #3): the
 * identity tables exist, enum vocabularies match the documented sets, and
 * the foreign keys promised by V1 for its forward-referenced identity
 * columns are in place with explicit names.
 */
@SpringBootTest(properties = "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef")
@Testcontainers
class IdentitySchemaIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void identityTablesExist() {
        Set<String> tables = new HashSet<>(jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class));

        assertThat(tables)
                .contains(
                        "users",
                        "refresh_tokens",
                        "password_reset_tokens",
                        "user_invitations",
                        "personal_access_tokens");
    }

    @Test
    void promisedForeignKeysFromV1ArePresentWithExplicitNames() {
        Set<String> constraints = new HashSet<>(jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE contype = 'f' AND connamespace = 'public'::regnamespace",
                String.class));

        assertThat(constraints)
                .contains(
                        "fk_testers_user_id",
                        "fk_artifacts_uploaded_by",
                        "fk_releases_created_by",
                        "fk_release_approvals_approver",
                        "fk_refresh_tokens_user_id",
                        "fk_password_reset_tokens_user_id",
                        "fk_user_invitations_invited_by",
                        "fk_personal_access_tokens_user_id");
    }

    @Test
    void userStatusEnumMatchesDocumentedValues() {
        List<String> labels = jdbc.queryForList(
                "SELECT e.enumlabel FROM pg_enum e "
                        + "JOIN pg_type t ON t.oid = e.enumtypid WHERE t.typname = 'user_status'",
                String.class);

        assertThat(labels).containsExactlyInAnyOrder("ACTIVE", "LOCKED", "DISABLED");
    }

    @Test
    void invitationStatusEnumMatchesDocumentedValues() {
        List<String> labels = jdbc.queryForList(
                "SELECT e.enumlabel FROM pg_enum e "
                        + "JOIN pg_type t ON t.oid = e.enumtypid WHERE t.typname = 'invitation_status'",
                String.class);

        assertThat(labels).containsExactlyInAnyOrder("PENDING", "ACCEPTED", "EXPIRED", "REVOKED");
    }

    @Test
    void emailAndTokenHashesAreUnique() {
        Set<String> constraints = new HashSet<>(jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE contype = 'u' AND connamespace = 'public'::regnamespace",
                String.class));

        assertThat(constraints)
                .contains(
                        "uq_users_email",
                        "uq_refresh_tokens_hash",
                        "uq_password_reset_tokens_hash",
                        "uq_user_invitations_hash",
                        "uq_personal_access_tokens_hash");
    }
}
