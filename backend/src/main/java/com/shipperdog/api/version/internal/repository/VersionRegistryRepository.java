package com.shipperdog.api.version.internal.repository;

import com.shipperdog.api.tenant.CrossTenantByDesign;
import com.shipperdog.api.version.VersionAccess;
import com.shipperdog.api.version.internal.entity.VersionEntity;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The scope-transition guard over {@code versions} (issue #17):
 * deliberately cross-scope, explicitly marked
 * {@link CrossTenantByDesign} per ADR-0002 (third amendment pattern).
 *
 * <p>Exactly one sanctioned operation spans scopes, declared here,
 * review-critical and in one place: the ownership-scope transition of
 * an application (issue #14, S3-02) moves the application's rows
 * between owning dimensions by definition — no single scoped context
 * can own both sides of the write, so the seam's write guard must
 * refuse it everywhere. The single declared, application-parameterized
 * update below is the one sanctioned write, called only from the
 * application module's audited transition (through {@link VersionAccess})
 * and from the version service's create-vs-transition race repair,
 * both after their checks, inside the caller's transaction. It moves
 * every version of the application in lockstep with the application
 * row, so a version can never strand in a scope its application has
 * left.
 *
 * <p>Like every {@link CrossTenantByDesign} repository, this interface
 * uses only its own declared operations — the inherited
 * {@code JpaRepository} surface stays seam-enforced for
 * {@code TenantScope} entities and would refuse without a bound
 * context. Beyond the sanctioned transition update, this repository
 * must never serve endpoint data paths: reads and writes of version
 * rows go through {@link VersionScopedRepository}, the tenant-isolation
 * seam. An architecture test pairs this marker with the seam rule.
 */
@CrossTenantByDesign
public interface VersionRegistryRepository extends JpaRepository<VersionEntity, UUID>, VersionAccess {

    /**
     * The sanctioned ownership carry-along (issue #14 transition +
     * issue #17): sets the owning tenant of every version of the
     * application, or nulls it when the application becomes
     * organization-shared. Returns the rows carried along.
     */
    @Override
    @Modifying
    @Query("update VersionEntity v set v.tenantId = :tenantId, v.updatedAt = :updatedAt"
            + " where v.applicationId = :applicationId")
    int reassignOwnership(
            @Param("applicationId") UUID applicationId,
            @Param("tenantId") UUID tenantId,
            @Param("updatedAt") Instant updatedAt);
}
