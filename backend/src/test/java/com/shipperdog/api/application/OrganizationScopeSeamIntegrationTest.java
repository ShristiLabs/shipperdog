package com.shipperdog.api.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shipperdog.api.application.internal.entity.ApplicationEntity;
import com.shipperdog.api.application.internal.entity.ApplicationEntity.OwnershipScope;
import com.shipperdog.api.application.internal.repository.ApplicationScopedRepository;
import com.shipperdog.api.config.CursorPageable;
import com.shipperdog.api.tenant.TenantContext;
import com.shipperdog.api.tenant.TenantIsolationException;
import com.shipperdog.api.tenant.internal.repository.TenantMemberScopedRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Repository-layer guard tests of the seam's organization-scope mode
 * (INV-001 for both ownership modes, issue #14, ADR-0002 third
 * amendment): an organization-scope context serves exactly the
 * null-tenant rows of its organization — never another organization's
 * shared rows, never any tenant's rows — a tenant-scope context never
 * serves organization-owned rows, the mode mismatch is refused loudly
 * rather than guessed, and the write guard accepts an entity only when
 * it belongs to the context's owning dimension.
 */
@SpringBootTest(properties = "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef")
@Testcontainers
class OrganizationScopeSeamIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    private static final UUID ORGANIZATION = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_ORGANIZATION = UUID.fromString("40000000-0000-4000-8000-000000000002");
    private static final UUID TENANT_A = UUID.fromString("40000000-0000-4000-8000-000000000010");
    private static final UUID TENANT_B = UUID.fromString("40000000-0000-4000-8000-000000000020");
    private static final UUID ACTOR = UUID.fromString("40000000-0000-4000-8000-000000000030");

    private UUID tenantOwnedA;
    private UUID tenantOwnedB;
    private UUID orgShared;
    private UUID otherOrgShared;

    @Autowired
    private ApplicationScopedRepository applications;

    @Autowired
    private TenantMemberScopedRepository members;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update(
                "INSERT INTO organizations (id, name, external_id) VALUES (?, 'Seam Org Scope One', 'seam-org-1')",
                ORGANIZATION);
        jdbc.update(
                "INSERT INTO organizations (id, name, external_id) VALUES (?, 'Seam Org Scope Two', 'seam-org-2')",
                OTHER_ORGANIZATION);
        jdbc.update(
                "INSERT INTO tenants (id, organization_id, name, external_id) VALUES (?, ?, 'Scope Alpha', 'scope-a')",
                TENANT_A,
                ORGANIZATION);
        jdbc.update(
                "INSERT INTO tenants (id, organization_id, name, external_id) VALUES (?, ?, 'Scope Beta', 'scope-b')",
                TENANT_B,
                ORGANIZATION);
        jdbc.update(
                "INSERT INTO tenants (id, organization_id, name, external_id)"
                        + " VALUES (?, ?, 'Scope Other Org Tenant', 'scope-other-org')",
                OTHER_ORGANIZATION,
                OTHER_ORGANIZATION);
        tenantOwnedA = insertApplication(ORGANIZATION, TENANT_A, "Seam Tenant A App", "com.seam.a");
        tenantOwnedB = insertApplication(ORGANIZATION, TENANT_B, "Seam Tenant B App", "com.seam.b");
        orgShared = insertApplication(ORGANIZATION, null, "Seam Shared App", "com.seam.shared");
        otherOrgShared = insertApplication(OTHER_ORGANIZATION, null, "Seam Other Shared", "com.seam.other");
    }

    @AfterEach
    void tearDown() {
        TenantContext.end();
        jdbc.update("DELETE FROM applications WHERE organization_id IN (?, ?)", ORGANIZATION, OTHER_ORGANIZATION);
        jdbc.update("DELETE FROM tenants WHERE organization_id IN (?, ?)", ORGANIZATION, OTHER_ORGANIZATION);
        jdbc.update("DELETE FROM organizations WHERE id IN (?, ?)", ORGANIZATION, OTHER_ORGANIZATION);
    }

    @Test
    void tenantScopeServesOnlyItsTenantsRows() {
        TenantContext.begin(TENANT_A, ACTOR, false);
        assertThat(applications.findScoped(tenantOwnedA)).isPresent();
        assertThat(applications.findScoped(tenantOwnedB))
                .as("another tenant's row is the standard miss")
                .isEmpty();
        assertThat(applications.findScoped(orgShared))
                .as("an organization-owned row never matches a tenant scope")
                .isEmpty();
    }

    @Test
    void organizationScopeServesExactlyItsOwnSharedRows() {
        TenantContext.beginOrganization(ORGANIZATION, ACTOR, false);
        assertThat(applications.findScoped(orgShared)).isPresent();
        assertThat(applications.findScoped(otherOrgShared))
                .as("another organization's shared row is the standard miss")
                .isEmpty();
        assertThat(applications.findScoped(tenantOwnedA))
                .as("tenant-owned rows never match an organization scope")
                .isEmpty();
        assertThat(applications.findScoped(tenantOwnedB)).isEmpty();

        Page<ApplicationEntity> page =
                applications.findAllScoped(Specification.unrestricted(), new CursorPageable(0, 10));
        assertThat(page.getContent())
                .as("the listing shows only this organization's shared rows")
                .hasSize(1);
        assertThat(page.getContent().get(0).getId()).isEqualTo(orgShared);
    }

    @Test
    void organizationScopeRefusesPurelyTenantOwnedEntities() {
        TenantContext.beginOrganization(ORGANIZATION, ACTOR, false);
        assertThatThrownBy(() -> members.findAllScoped(Specification.unrestricted(), new CursorPageable(0, 10)))
                .isInstanceOf(TenantIsolationException.class)
                .hasMessageContaining("only OrganizationScope rows");
        assertThatThrownBy(() -> members.findScoped(UUID.randomUUID()))
                .isInstanceOf(TenantIsolationException.class)
                .hasMessageContaining("only OrganizationScope rows");
    }

    @Test
    void writeGuardAcceptsOnlyTheContextsOwningDimension() {
        // Organization scope accepts its own shared row and refuses a
        // tenant-owned row and a foreign organization's shared row.
        TenantContext.beginOrganization(ORGANIZATION, ACTOR, false);
        applications.saveScoped(new ApplicationEntity(
                ORGANIZATION,
                OwnershipScope.ORGANIZATION,
                null,
                "Seam Written Shared",
                null,
                List.of("IOS"),
                "com.seam.written",
                null));
        assertThatThrownBy(() -> applications.saveScoped(new ApplicationEntity(
                        ORGANIZATION,
                        OwnershipScope.TENANT,
                        TENANT_A,
                        "Seam Written Tenant",
                        null,
                        List.of("IOS"),
                        "com.seam.written.tenant",
                        null)))
                .as("a tenant-owned row does not belong to an organization scope")
                .isInstanceOf(TenantIsolationException.class);
        assertThatThrownBy(() -> applications.saveScoped(new ApplicationEntity(
                        OTHER_ORGANIZATION,
                        OwnershipScope.ORGANIZATION,
                        null,
                        "Seam Written Foreign",
                        null,
                        List.of("IOS"),
                        "com.seam.written.foreign",
                        null)))
                .as("a foreign organization's shared row does not belong to this scope")
                .isInstanceOf(TenantIsolationException.class);

        // Tenant scope refuses an organization-owned row.
        TenantContext.end();
        TenantContext.begin(TENANT_A, ACTOR, false);
        assertThatThrownBy(() -> applications.saveScoped(new ApplicationEntity(
                        ORGANIZATION,
                        OwnershipScope.ORGANIZATION,
                        null,
                        "Seam Written Shared 2",
                        null,
                        List.of("IOS"),
                        "com.seam.written.shared2",
                        null)))
                .as("an organization-owned row never belongs to a tenant scope")
                .isInstanceOf(TenantIsolationException.class);
    }

    @Test
    void platformAdministratorOverrideAuthorizesCrossScopeWrites() {
        TenantContext.beginOrganization(OTHER_ORGANIZATION, ACTOR, true);
        ApplicationEntity saved = applications.saveScoped(new ApplicationEntity(
                ORGANIZATION,
                OwnershipScope.ORGANIZATION,
                null,
                "Seam Override Shared",
                null,
                List.of("IOS"),
                "com.seam.override",
                null));
        assertThat(saved.getId()).isNotNull();
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
}
