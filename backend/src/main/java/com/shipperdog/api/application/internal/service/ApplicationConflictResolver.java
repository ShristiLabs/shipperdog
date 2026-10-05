package com.shipperdog.api.application.internal.service;

import com.shipperdog.api.application.ApplicationException;
import com.shipperdog.api.application.internal.repository.ApplicationRegistryRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Re-derives which uniqueness guard lost a race (issue #13, AC2) after a
 * constraint violation, without parsing driver messages. The probes run
 * in their own transaction on purpose: PostgreSQL aborts the failed
 * write's transaction, so any re-derivation query on that transaction
 * would die with "current transaction is aborted" — a fresh transaction
 * sees the concurrent winner's committed row and classifies the conflict
 * precisely. The constraint index remains the final authority; this only
 * names the loser for the 409 code.
 */
@Component
public class ApplicationConflictResolver {

    private final ApplicationRegistryRepository registry;

    ApplicationConflictResolver(ApplicationRegistryRepository registry) {
        this.registry = registry;
    }

    /** Names the guard a concurrent insert violated. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public ApplicationException insertConflict(UUID organizationId, String name, String bundleId, String packageName) {
        if (bundleId != null && registry.existsByOrganizationIdAndBundleId(organizationId, bundleId)) {
            return conflict(ApplicationException.Code.BUNDLE_TAKEN, "bundle id is already in use in the organization");
        }
        if (packageName != null && registry.existsByOrganizationIdAndPackageName(organizationId, packageName)) {
            return conflict(
                    ApplicationException.Code.PACKAGE_TAKEN, "package name is already in use in the organization");
        }
        if (registry.existsByOrganizationIdAndName(organizationId, name)) {
            return conflict(
                    ApplicationException.Code.NAME_TAKEN, "application name is already in use in the organization");
        }
        return conflict(ApplicationException.Code.BUNDLE_TAKEN, "bundle id is already in use in the organization");
    }

    /** Names the guard a concurrent update violated, excluding the application itself. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public ApplicationException updateConflict(
            UUID organizationId, UUID applicationId, String name, String bundleId, String packageName) {
        if (bundleId != null
                && registry.existsByOrganizationIdAndBundleIdAndIdNot(organizationId, bundleId, applicationId)) {
            return conflict(ApplicationException.Code.BUNDLE_TAKEN, "bundle id is already in use in the organization");
        }
        if (packageName != null
                && registry.existsByOrganizationIdAndPackageNameAndIdNot(organizationId, packageName, applicationId)) {
            return conflict(
                    ApplicationException.Code.PACKAGE_TAKEN, "package name is already in use in the organization");
        }
        if (registry.existsByOrganizationIdAndNameAndIdNot(organizationId, name, applicationId)) {
            return conflict(
                    ApplicationException.Code.NAME_TAKEN, "application name is already in use in the organization");
        }
        return conflict(ApplicationException.Code.BUNDLE_TAKEN, "bundle id is already in use in the organization");
    }

    private static ApplicationException conflict(ApplicationException.Code code, String message) {
        return new ApplicationException(code, message);
    }
}
