package com.shipperdog.api.tenant.internal.config;

import com.shipperdog.api.tenant.internal.repository.TenantScopedRepositoryImpl;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Wires the tenant-isolation seam into every repository (INV-001, issue
 * #10): {@link TenantScopedRepositoryImpl} becomes the repository base
 * class for the whole application, so repositories extending
 * {@link com.shipperdog.api.tenant.TenantScopedRepository} inherit the
 * context-enforcing operations, while plain repositories behave exactly
 * as before (the base class is a standard Spring Data implementation
 * plus the scoped surface they never declare).
 */
@Configuration
@EnableJpaRepositories(basePackages = "com.shipperdog.api", repositoryBaseClass = TenantScopedRepositoryImpl.class)
public class TenantIsolationJpaConfig {}
