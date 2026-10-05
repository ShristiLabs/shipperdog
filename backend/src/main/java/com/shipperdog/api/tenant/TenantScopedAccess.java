package com.shipperdog.api.tenant;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

/**
 * The tenant-scoped data-access operations every {@link TenantScope}
 * repository inherits (INV-001, issue #10). Implementations inject the
 * request's tenant context into every query as an index-backed
 * {@code tenantId = :context} predicate, so no caller can compose a
 * query that spans tenants, and refuse (fail-closed) when no context is
 * bound.
 *
 * <p>This is the only shape of tenant-scoped access: repository
 * interfaces extending {@link TenantScopedRepository} may not declare
 * additional query methods (enforced by architecture tests), which is
 * what makes the isolation structural rather than a matter of endpoint
 * discipline. Extra conditions arrive as {@link Specification}
 * parameters and are composed with the forced tenant predicate.
 */
public interface TenantScopedAccess<T> {

    /**
     * Loads by primary id inside the caller's tenant context; an id from
     * another tenant is indistinguishable from an unknown id (empty).
     */
    Optional<T> findScoped(UUID id);

    /** First match of the filter inside the caller's tenant context. */
    Optional<T> findOneScoped(Specification<T> filter);

    /**
     * Paged matches of the filter inside the caller's tenant context.
     * Unsorted pageables default to the platform listing order
     * ({@code createdAt ASC, id ASC}).
     */
    Page<T> findAllScoped(Specification<T> filter, Pageable pageable);

    /** Whether the filter matches anything inside the caller's tenant context. */
    boolean existsScoped(Specification<T> filter);

    /**
     * Persists inside the caller's tenant context; an entity belonging
     * to another tenant is refused unless the platform-administrator
     * override is active.
     */
    <S extends T> S saveScoped(S entity);
}
