package com.shipperdog.api.tenant;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

/**
 * Base repository for tenant-scoped user data (INV-001, issue #10).
 * Every {@link TenantScope} entity's repository extends this interface;
 * architecture tests reject tenant-scoped repositories that bypass it
 * and additional derived query methods on subinterfaces, so tenant
 * filtering cannot be forgotten by a future module.
 *
 * <p>The inherited {@link JpaRepository} CRUD surface does not bypass
 * the seam: the backing base class overrides that entire surface with a
 * fixed policy (ADR-0002 amendment) — id-based reads and guarded writes
 * behave tenant-scoped, operations with no tenant-safe meaning (bulk
 * deletes, unparameterized listings, lazy proxies, {@code Example}
 * queries) refuse with {@link TenantIsolationException}, and a
 * reflective completeness guard fails the build when a Spring Data
 * upgrade adds inherited operations the seam does not yet account for.
 *
 * <p>Organizations, tenants, and authorization tables are not
 * tenant-scoped user data: organizations and tenants are
 * organization-owned (their queries are organization-scoped), and
 * role-resolution repositories that must span tenants are marked
 * {@link CrossTenantByDesign}.
 */
@NoRepositoryBean
public interface TenantScopedRepository<T extends TenantScope> extends JpaRepository<T, UUID>, TenantScopedAccess<T> {}
