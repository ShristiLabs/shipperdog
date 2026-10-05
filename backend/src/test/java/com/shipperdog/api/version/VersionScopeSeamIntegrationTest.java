package com.shipperdog.api.version;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shipperdog.api.config.CursorPageable;
import com.shipperdog.api.tenant.TenantContext;
import com.shipperdog.api.tenant.TenantIsolationException;
import com.shipperdog.api.version.internal.entity.VersionEntity;
import com.shipperdog.api.version.internal.repository.VersionRegistryRepository;
import com.shipperdog.api.version.internal.repository.VersionScopedRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Repository-layer guard tests of the version seam (issue #17, INV-001,
 * ADR-0002 third amendment applied to versions): a version row carries
 * its application's owning-dimension snapshot, so a tenant-scope
 * context serves exactly the owning tenant's versions, an
 * organization-scope context exactly its organization's shared
 * (null-tenant) versions, the write guard accepts a new row only in the
 * context's owning dimension, and the sanctioned carry-along update
 * moves every version of an application between scopes in lockstep
 * with the audited ownership transition.
 */
@SpringBootTest(properties = "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef")
@Testcontainers
class VersionScopeSeamIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    private static final UUID ORGANIZATION = UUID.fromString("41000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_ORGANIZATION = UUID.fromString("41000000-0000-4000-8000-000000000002");
    private static final UUID TENANT_A = UUID.fromString("41000000-0000-4000-8000-000000000010");
    private static final UUID TENANT_B = UUID.fromString("41000000-0000-4000-8000-000000000020");
    private static final UUID ACTOR = UUID.fromString("41000000-0000-4000-8000-000000000030");

    private UUID tenantOwnedAppA;
    private UUID tenantOwnedAppB;
    private UUID orgSharedApp;
    private UUID otherOrgSharedApp;
    private UUID versionOfA;
    private UUID versionOfB;
    private UUID versionOfShared;
    private UUID versionOfOtherShared;

    @Autowired
    private VersionScopedRepository versions;

    @Autowired
    private VersionRegistryRepository registry;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update(
                "INSERT INTO organizations (id, name, external_id) VALUES (?, 'Seam Version Org One', 'seam-ver-1')",
                ORGANIZATION);
        jdbc.update(
                "INSERT INTO organizations (id, name, external_id) VALUES (?, 'Seam Version Org Two', 'seam-ver-2')",
                OTHER_ORGANIZATION);
        jdbc.update(
                "INSERT INTO tenants (id, organization_id, name, external_id) VALUES (?, ?, 'Version Alpha', 'ver-a')",
                TENANT_A,
                ORGANIZATION);
        jdbc.update(
                "INSERT INTO tenants (id, organization_id, name, external_id) VALUES (?, ?, 'Version Beta', 'ver-b')",
                TENANT_B,
                ORGANIZATION);
        tenantOwnedAppA = insertApplication(ORGANIZATION, TENANT_A, "Seam Ver Tenant A App", "com.seamver.a");
        tenantOwnedAppB = insertApplication(ORGANIZATION, TENANT_B, "Seam Ver Tenant B App", "com.seamver.b");
        orgSharedApp = insertApplication(ORGANIZATION, null, "Seam Ver Shared App", "com.seamver.shared");
        otherOrgSharedApp = insertApplication(OTHER_ORGANIZATION, null, "Seam Ver Other Shared", "com.seamver.other");
        versionOfA = insertVersion(tenantOwnedAppA, ORGANIZATION, TENANT_A, "1.0.0", "00001.00000.00000");
        versionOfB = insertVersion(tenantOwnedAppB, ORGANIZATION, TENANT_B, "1.0.0", "00001.00000.00000");
        versionOfShared = insertVersion(orgSharedApp, ORGANIZATION, null, "2.0.0", "00002.00000.00000");
        versionOfOtherShared = insertVersion(otherOrgSharedApp, OTHER_ORGANIZATION, null, "3.0.0", "00003.00000.00000");
    }

    @AfterEach
    void tearDown() {
        TenantContext.end();
        jdbc.update("DELETE FROM versions WHERE organization_id IN (?, ?)", ORGANIZATION, OTHER_ORGANIZATION);
        jdbc.update("DELETE FROM applications WHERE organization_id IN (?, ?)", ORGANIZATION, OTHER_ORGANIZATION);
        jdbc.update("DELETE FROM tenants WHERE organization_id IN (?, ?)", ORGANIZATION, OTHER_ORGANIZATION);
        jdbc.update("DELETE FROM organizations WHERE id IN (?, ?)", ORGANIZATION, OTHER_ORGANIZATION);
    }

    @Test
    void tenantScopeServesOnlyItsTenantsVersions() {
        TenantContext.begin(TENANT_A, ACTOR, false);
        assertThat(versions.findScoped(versionOfA)).isPresent();
        assertThat(versions.findScoped(versionOfB))
                .as("another tenant's version is the standard miss")
                .isEmpty();
        assertThat(versions.findScoped(versionOfShared))
                .as("an organization-owned version never matches a tenant scope")
                .isEmpty();
    }

    @Test
    void organizationScopeServesExactlyItsOwnSharedVersions() {
        TenantContext.beginOrganization(ORGANIZATION, ACTOR, false);
        assertThat(versions.findScoped(versionOfShared)).isPresent();
        assertThat(versions.findScoped(versionOfOtherShared))
                .as("another organization's shared version is the standard miss")
                .isEmpty();
        assertThat(versions.findScoped(versionOfA))
                .as("tenant-owned versions never match an organization scope")
                .isEmpty();
        assertThat(versions.findScoped(versionOfB)).isEmpty();
        assertThat(versions.findAllScoped(
                                (root, query, builder) -> builder.equal(root.get("applicationId"), orgSharedApp),
                                new CursorPageable(0, 10))
                        .getContent())
                .as("the application listing under the organization scope shows exactly its shared versions")
                .hasSize(1);
    }

    @Test
    void writeGuardAcceptsOnlyTheContextsOwningDimension() {
        TenantContext.begin(TENANT_A, ACTOR, false);
        versions.saveScoped(new VersionEntity(tenantOwnedAppA, ORGANIZATION, TENANT_A, "1.1.0", "00001.00001.00000"));
        assertThatThrownBy(() -> versions.saveScoped(
                        new VersionEntity(tenantOwnedAppB, ORGANIZATION, TENANT_B, "1.1.0", "00001.00001.00000")))
                .as("a version of another tenant's application is refused")
                .isInstanceOf(TenantIsolationException.class);
        assertThatThrownBy(() -> versions.saveScoped(
                        new VersionEntity(orgSharedApp, ORGANIZATION, null, "2.1.0", "00002.00001.00000")))
                .as("an organization-owned version never belongs to a tenant scope")
                .isInstanceOf(TenantIsolationException.class);

        TenantContext.end();
        TenantContext.beginOrganization(ORGANIZATION, ACTOR, false);
        versions.saveScoped(new VersionEntity(orgSharedApp, ORGANIZATION, null, "2.2.0", "00002.00002.00000"));
        assertThatThrownBy(() -> versions.saveScoped(
                        new VersionEntity(tenantOwnedAppA, ORGANIZATION, TENANT_A, "1.2.0", "00001.00002.00000")))
                .as("a tenant-owned version does not belong to an organization scope")
                .isInstanceOf(TenantIsolationException.class);
    }

    @Test
    @Transactional
    void carryAlongMovesEveryVersionOfTheApplicationBetweenScopes() {
        // TENANT -> ORGANIZATION: the owning tenant is nulled on every row.
        int carried = registry.reassignOwnership(tenantOwnedAppA, null, Instant.now());
        assertThat(carried).as("the application's version moved with it").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT tenant_id FROM versions WHERE id = ?", UUID.class, versionOfA))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT organization_id FROM versions WHERE id = ?", UUID.class, versionOfA))
                .isEqualTo(ORGANIZATION);

        // ORGANIZATION -> TENANT: the new owner tenant is installed.
        assertThat(registry.reassignOwnership(tenantOwnedAppA, TENANT_A, Instant.now()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT tenant_id FROM versions WHERE id = ?", UUID.class, versionOfA))
                .isEqualTo(TENANT_A);

        // Versions of other applications stay untouched.
        assertThat(jdbc.queryForObject("SELECT tenant_id FROM versions WHERE id = ?", UUID.class, versionOfB))
                .isEqualTo(TENANT_B);
    }

    private UUID insertApplication(UUID organizationId, UUID tenantId, String name, String bundleId) {
        String scope = tenantId == null ? "ORGANIZATION" : "TENANT";
        jdbc.update(
                "INSERT INTO applications (organization_id, tenant_id, ownership_scope, name, platforms, bundle_id)"
                        + " VALUES (?, ?, CAST(? AS application_ownership_scope), ?, '{IOS}', ?)",
                organizationId,
                tenantId,
                scope,
                name,
                bundleId);
        return jdbc.queryForObject(
                "SELECT id FROM applications WHERE organization_id = ? AND name = ?", UUID.class, organizationId, name);
    }

    private UUID insertVersion(UUID applicationId, UUID organizationId, UUID tenantId, String name, String sortKey) {
        jdbc.update(
                "INSERT INTO versions (application_id, organization_id, tenant_id, version_name, sort_key)"
                        + " VALUES (?, ?, ?, ?, ?)",
                applicationId,
                organizationId,
                tenantId,
                name,
                sortKey);
        return jdbc.queryForObject(
                "SELECT id FROM versions WHERE application_id = ? AND version_name = ?",
                UUID.class,
                applicationId,
                name);
    }
}
