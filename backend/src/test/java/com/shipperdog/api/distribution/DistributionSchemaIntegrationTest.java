package com.shipperdog.api.distribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
 * AC3's schema proof (issue #61, S13-01, PRD 25): the credential chain
 * holds references, not credentials -- the store_accounts column set
 * is EXACTLY id/provider/metadata/secret_reference beside the level's
 * parents and timestamps, and the envelope store's managed_secrets row
 * is ciphertext and a wrapped key only. A material-bearing column
 * cannot appear without this suite failing: the sets are pinned, not
 * pattern-matched.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
class DistributionSchemaIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void storeAccountsHoldOnlyTheReferenceFacts() {
        assertThat(columnsOf("store_accounts"))
                .containsExactlyInAnyOrder(
                        "id",
                        "organization_id",
                        "application_id",
                        "environment_id",
                        "provider",
                        "name",
                        "metadata",
                        "secret_reference",
                        "created_at",
                        "updated_at");
    }

    @Test
    void managedSecretsHoldOnlyCiphertextAndTheWrappedKey() {
        assertThat(columnsOf("managed_secrets"))
                .containsExactlyInAnyOrder(
                        "id",
                        "secret_reference",
                        "ciphertext",
                        "wrapped_data_key",
                        "key_id",
                        "algorithm",
                        "created_at",
                        "updated_at");
    }

    @Test
    void channelDistributionsCarryTheLifecycleAndReconciliationFacts() {
        assertThat(columnsOf("channel_distributions"))
                .containsExactlyInAnyOrder(
                        "id",
                        "release_id",
                        "artifact_id",
                        "organization_id",
                        "channel",
                        "status",
                        "external_id",
                        "failure_code",
                        "attempts",
                        "last_attempt_at",
                        "distributed_at",
                        "created_at",
                        "updated_at");
        // The lifecycle reuses V1's own distribution_status vocabulary.
        String enumValues = jdbc.queryForObject(
                "SELECT string_agg(enumlabel, ',' ORDER BY enumsortorder) FROM pg_enum e"
                        + " JOIN pg_type t ON t.oid = e.enumtypid WHERE t.typname = 'distribution_status'",
                String.class);
        assertThat(enumValues).isEqualTo("PENDING,DISTRIBUTING,DISTRIBUTED,FAILED");
    }

    @Test
    void theChainLevelIsUniquePerProviderNullsIncluded() {
        // NULLS NOT DISTINCT: two organization-level rows of one provider
        // are impossible by constraint (the first-defined-wins resolution
        // depends on it).
        Integer constraintCount = jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conname = 'uq_store_accounts_level_provider'", Integer.class);
        assertThat(constraintCount).isEqualTo(1);
        String definition = jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname ="
                        + " 'uq_store_accounts_level_provider'",
                String.class);
        assertThat(definition).contains("NULLS NOT DISTINCT");
    }

    private List<String> columnsOf(String table) {
        return jdbc.query(
                "SELECT column_name FROM information_schema.columns WHERE table_name = ? ORDER BY ordinal_position",
                (row, index) -> row.getString(1),
                table);
    }
}
