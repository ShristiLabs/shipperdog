package com.shipperdog.api.db;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Verifies the dev seed fixture (issue #2): when the seed Flyway location is
 * enabled, exactly one organization, two tenants, and one application exist,
 * with the stable external identifiers later integration tests rely on.
 */
@SpringBootTest(
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
class DevSeedIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Test
    void devSeedCreatesOneOrganizationTwoTenantsOneApplication() {
        Integer orgs = jdbc.queryForObject("SELECT count(*) FROM organizations", Integer.class);
        Integer tenants = jdbc.queryForObject("SELECT count(*) FROM tenants", Integer.class);
        Integer applications = jdbc.queryForObject("SELECT count(*) FROM applications", Integer.class);
        Integer users = jdbc.queryForObject("SELECT count(*) FROM users", Integer.class);

        assertThat(orgs).isEqualTo(1);
        assertThat(tenants).isEqualTo(2);
        assertThat(applications).isEqualTo(1);
        assertThat(users).isEqualTo(1);
    }

    @Test
    void devSeedCreatesBootstrapAdminWithArgon2idHash() {
        var row =
                jdbc.queryForMap("SELECT email, password_hash, status FROM users WHERE email = 'dev@shipperdog.local'");

        assertThat(row.get("status")).isEqualTo("ACTIVE");
        assertThat((String) row.get("password_hash")).startsWith("$argon2id$");
    }

    @Test
    void devSeedUsesStableExternalIdentifiers() {
        var rows = jdbc.queryForList("SELECT external_id FROM tenants ORDER BY external_id", String.class);

        assertThat(rows).containsExactly("demo-acme", "demo-contoso");
    }

    @Test
    void reRunningSeedScriptAgainstSeededDatabaseIsANoop() {
        ResourceDatabasePopulator populator =
                new ResourceDatabasePopulator(new ClassPathResource("db/seed/R__dev_seed.sql"));
        populator.execute(dataSource);

        Integer orgs = jdbc.queryForObject("SELECT count(*) FROM organizations", Integer.class);
        Integer tenants = jdbc.queryForObject("SELECT count(*) FROM tenants", Integer.class);
        Integer applications = jdbc.queryForObject("SELECT count(*) FROM applications", Integer.class);
        Integer users = jdbc.queryForObject("SELECT count(*) FROM users", Integer.class);

        assertThat(orgs).as("seed re-execution must not duplicate rows").isEqualTo(1);
        assertThat(tenants).isEqualTo(2);
        assertThat(applications).isEqualTo(1);
        assertThat(users).isEqualTo(1);
    }
}
