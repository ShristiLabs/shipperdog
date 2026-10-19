package com.shipperdog.api.tenant.internal.repository;

import com.shipperdog.api.config.CursorPageable;
import com.shipperdog.api.tenant.OrganizationScope;
import com.shipperdog.api.tenant.TenantContext;
import com.shipperdog.api.tenant.TenantIsolationException;
import com.shipperdog.api.tenant.TenantScope;
import com.shipperdog.api.tenant.TenantScopedAccess;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.support.JpaEntityInformation;
import org.springframework.data.jpa.repository.support.JpaEntityInformationSupport;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.query.FluentQuery;
import org.springframework.lang.Nullable;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

/**
 * The data-access enforcement seam of tenant isolation (INV-001, issue
 * #10): the repository base class behind every
 * {@link com.shipperdog.api.tenant.TenantScopedRepository}. Each scoped
 * operation composes an index-backed {@code tenantId = :context}
 * predicate from {@link TenantContext} into the query and delegates to
 * the standard Spring Data machinery, so no caller can span tenants and
 * no query runs without a scope.
 *
 * <p>The context is the request's target tenant (the route the caller
 * is authorized for), not the caller's membership set — endpoint
 * authorization decides who may enter the route; the seam guarantees
 * that everything read or written inside it belongs to that tenant.
 * Cross-tenant fetches therefore surface as empty results, which the
 * services render as the same opaque 404 a genuine miss produces
 * (PRD 12).
 *
 * <p>Fail-closed: with no context bound, every operation throws
 * {@link TenantIsolationException} instead of running unscoped. The
 * platform-administrator override ({@link TenantContext#crossTenantOverride()})
 * does not widen reads — route-scoped listings must stay route-scoped —
 * but authorizes administrative writes outside the context's tenant;
 * every override-bearing request is audited as {@code CROSS_TENANT_ACCESS}
 * by the web layer that binds the context.
 *
 * <p><strong>Inherited JpaRepository surface (ADR-0002 amendment).</strong>
 * Extending {@link org.springframework.data.jpa.repository.JpaRepository}
 * would otherwise hand every seam repository an unfiltered CRUD surface
 * ({@code findById}/{@code save}/{@code deleteAllInBatch} and friends run
 * without the tenant predicate). The seam therefore overrides the
 * <em>entire</em> inherited surface with a fixed policy, and a reflective
 * completeness guard fails the build when a Spring Data upgrade adds
 * operations this class does not know about:
 *
 * <ul>
 *   <li><strong>Enforced</strong> — operations with an exactly
 *       tenant-safe meaning get the predicate or the per-entity write
 *       guard: {@code findById}, {@code findAllById}, {@code existsById},
 *       {@code count} compose the tenant predicate (an id of another
 *       tenant is indistinguishable from an unknown id); {@code save},
 *       {@code saveAll}, {@code saveAndFlush}, {@code saveAllAndFlush},
 *       {@code delete(entity)} verify the entity's tenant equals the
 *       context (or the platform-administrator override) before
 *       delegating; {@code deleteById} removes only a row of the
 *       context tenant and treats every other id as a no-op.</li>
 *   <li><strong>Refused</strong> — operations with no tenant-safe
 *       meaning throw {@link TenantIsolationException} with and without
 *       a bound context: the unparameterized and bulk shapes
 *       ({@code findAll()}, {@code deleteAll*}, {@code deleteAllInBatch*},
 *       {@code deleteInBatch}), the lazy proxy getters ({@code getOne},
 *       {@code getById}, {@code getReferenceById} — a proxy defers the
 *       load past any check), and the entire {@code Example} query
 *       family (an example probe with a null {@code tenantId} matches
 *       every tenant, so it cannot be scoped by construction).</li>
 *   <li><strong>Passed through</strong> — {@code flush()} only pushes
 *       already-guarded pending work; it issues no query of its own.
 *       It is the sole allowlisted operation in the completeness
 *       guard.</li>
 * </ul>
 *
 * <p>This class is the Spring Data base class for <em>every</em>
 * repository, not only seam repositories; the enforcement paths apply
 * solely when the domain type implements {@link TenantScope}, so plain
 * repositories keep stock Spring Data behavior.
 *
 * <p><strong>Organization-scope mode (issue #14, S3-02, PRD 14; ADR-0002
 * third amendment).</strong> A context bound with
 * {@code TenantContext.beginOrganization} targets rows the organization
 * itself owns ({@link com.shipperdog.api.tenant.OrganizationScope}
 * entities with a null {@code tenantId} — organization-shared
 * applications). The seam composes
 * {@code tenantId IS NULL AND organizationId = :context} for every
 * scoped operation in that mode and refuses to serve entities that are
 * not {@code OrganizationScope}: organization-owned rows are reachable
 * only through their organization's scope, exactly as tenant-owned rows
 * are reachable only through their tenant's — INV-001 holds for both
 * ownership modes by the same construction, and neither mode can read or
 * write the other's rows.
 */
@NoRepositoryBean
@Transactional(readOnly = true)
public class TenantScopedRepositoryImpl<T, ID extends Serializable> extends SimpleJpaRepository<T, ID>
        implements TenantScopedAccess<T> {

    /** Discriminator path every {@link TenantScope} entity maps. */
    private static final String TENANT_ID_ATTRIBUTE = "tenantId";

    /** Owning-organization path every {@link OrganizationScope} entity maps. */
    private static final String ORGANIZATION_ID_ATTRIBUTE = "organizationId";

    /** Identity path of every entity served by the seam. */
    private static final String ID_ATTRIBUTE = "id";

    /** Platform listing order when the caller sorts nothing (issue #8). */
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.ASC, "createdAt", "id");

    /**
     * Whether this repository serves a {@link TenantScope} entity; only
     * then do the enforcement and refusal paths below engage. Plain
     * repositories share this base class and must behave exactly as
     * stock Spring Data.
     */
    private final boolean tenantScoped;

    /** The shared persistence context, kept for the seam's lock-capable read below. */
    private final EntityManager entityManager;

    public TenantScopedRepositoryImpl(JpaEntityInformation<T, ?> information, EntityManager entityManager) {
        super(information, entityManager);
        this.tenantScoped = TenantScope.class.isAssignableFrom(information.getJavaType());
        this.entityManager = entityManager;
    }

    public TenantScopedRepositoryImpl(Class<T> domainClass, EntityManager entityManager) {
        this(JpaEntityInformationSupport.getEntityInformation(domainClass, entityManager), entityManager);
    }

    // -------------------------------------------------------------------------
    // TenantScopedAccess: the blessed tenant-scoped surface
    // -------------------------------------------------------------------------

    @Override
    public Optional<T> findScoped(UUID id) {
        return findOne(scoped(idIs(id)));
    }

    @Override
    public Optional<T> findOneScoped(Specification<T> filter) {
        return findOne(scoped(filter));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Composes the forced scope predicate exactly like {@link #findOneScoped}
     * and issues the criteria query under {@code PESSIMISTIC_WRITE} — the
     * row lock the database holds to the surrounding transaction's end, so
     * concurrent callers of this shape serialize on the row itself. A
     * holder's committed delete leaves the waiter's locked read empty (the
     * row no longer matches), which is precisely the tell a racing caller
     * re-reads for the winner's fact; an uncommitted holder's row blocks
     * the read until the holder's outcome is visible.
     */
    @Override
    public Optional<T> findOneScopedForUpdate(Specification<T> filter) {
        Specification<T> scoped = scoped(filter);
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<T> query = builder.createQuery(getDomainClass());
        Root<T> root = query.from(getDomainClass());
        query.select(root).where(scoped.toPredicate(root, query, builder));
        return entityManager.createQuery(query).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList().stream()
                .findFirst();
    }

    @Override
    public Page<T> findAllScoped(Specification<T> filter, Pageable pageable) {
        return findAll(scoped(filter), withDefaultOrder(pageable));
    }

    @Override
    public boolean existsScoped(Specification<T> filter) {
        return count(scoped(filter)) > 0;
    }

    @Override
    @Transactional
    public <S extends T> S saveScoped(S entity) {
        return save(entity);
    }

    // -------------------------------------------------------------------------
    // Inherited surface: id-based reads behave scoped
    // -------------------------------------------------------------------------

    @Override
    public Optional<T> findById(ID id) {
        if (!tenantScoped) {
            return super.findById(id);
        }
        TenantContext.require();
        Assert.notNull(id, "The given id must not be null");
        return findOne(scoped(idIs(id)));
    }

    @Override
    public List<T> findAllById(Iterable<ID> ids) {
        if (!tenantScoped) {
            return super.findAllById(ids);
        }
        TenantContext.require();
        List<ID> wanted = new ArrayList<>();
        ids.forEach(wanted::add);
        if (wanted.isEmpty()) {
            return List.of();
        }
        return findAll(scoped((root, query, builder) -> root.get(ID_ATTRIBUTE).in(wanted)));
    }

    @Override
    public boolean existsById(ID id) {
        if (!tenantScoped) {
            return super.existsById(id);
        }
        TenantContext.require();
        Assert.notNull(id, "The given id must not be null");
        return count(scoped(idIs(id))) > 0;
    }

    @Override
    public long count() {
        if (!tenantScoped) {
            return super.count();
        }
        return count(scoped(null));
    }

    // -------------------------------------------------------------------------
    // Inherited surface: writes and single-entity deletes are guarded
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public <S extends T> S save(S entity) {
        if (!tenantScoped) {
            return super.save(entity);
        }
        assertTenantWrite(entity);
        return super.save(entity);
    }

    @Override
    @Transactional
    public <S extends T> List<S> saveAll(Iterable<S> entities) {
        if (!tenantScoped) {
            return super.saveAll(entities);
        }
        TenantContext.require();
        List<S> batch = new ArrayList<>();
        entities.forEach(batch::add);
        batch.forEach(TenantScopedRepositoryImpl::assertTenantWrite);
        return super.saveAll(batch);
    }

    @Override
    @Transactional
    public <S extends T> S saveAndFlush(S entity) {
        if (!tenantScoped) {
            return super.saveAndFlush(entity);
        }
        assertTenantWrite(entity);
        return super.saveAndFlush(entity);
    }

    @Override
    @Transactional
    public <S extends T> List<S> saveAllAndFlush(Iterable<S> entities) {
        if (!tenantScoped) {
            return super.saveAllAndFlush(entities);
        }
        TenantContext.require();
        List<S> batch = new ArrayList<>();
        entities.forEach(batch::add);
        batch.forEach(TenantScopedRepositoryImpl::assertTenantWrite);
        return super.saveAllAndFlush(batch);
    }

    /**
     * The verified single-entity removal path (the one
     * {@code TenantMemberService.removeMember} relies on): the loaded
     * entity's tenant must equal the context (or the platform-admin
     * override) before Spring Data may remove it. An entity the seam
     * would not have handed out is refused loudly — cross-tenant
     * deletion is a write, not a miss, so it never conceals as a no-op.
     */
    @Override
    @Transactional
    public void delete(T entity) {
        if (!tenantScoped) {
            super.delete(entity);
            return;
        }
        assertTenantWrite(entity);
        super.delete(entity);
    }

    /**
     * Scoped by-id removal preserving Spring Data semantics: native
     * {@code deleteById} is {@code findById(id).ifPresent(this::delete)},
     * so with the scoped {@link #findById} an id of another tenant —
     * like an unknown id — deletes nothing and stays silent about why.
     */
    @Override
    @Transactional
    public void deleteById(ID id) {
        if (!tenantScoped) {
            super.deleteById(id);
            return;
        }
        TenantContext.require();
        Assert.notNull(id, "The given id must not be null");
        findById(id).ifPresent(this::delete);
    }

    // -------------------------------------------------------------------------
    // Inherited surface: refused — no tenant-safe meaning exists
    // -------------------------------------------------------------------------

    @Override
    public List<T> findAll() {
        if (!tenantScoped) {
            return super.findAll();
        }
        throw refused("findAll()", "findAllScoped(Specification, Pageable)");
    }

    @Override
    public List<T> findAll(Sort sort) {
        if (!tenantScoped) {
            return super.findAll(sort);
        }
        throw refused("findAll(Sort)", "findAllScoped(Specification, Pageable)");
    }

    @Override
    public Page<T> findAll(Pageable pageable) {
        if (!tenantScoped) {
            return super.findAll(pageable);
        }
        throw refused("findAll(Pageable)", "findAllScoped(Specification, Pageable)");
    }

    @Override
    public T getReferenceById(ID id) {
        if (!tenantScoped) {
            return super.getReferenceById(id);
        }
        throw refused("getReferenceById(id)", "findScoped(id)");
    }

    @Override
    public T getOne(ID id) {
        if (!tenantScoped) {
            return super.getOne(id);
        }
        throw refused("getOne(id)", "findScoped(id)");
    }

    @Override
    public T getById(ID id) {
        if (!tenantScoped) {
            return super.getById(id);
        }
        throw refused("getById(id)", "findScoped(id)");
    }

    @Override
    public void deleteAllById(Iterable<? extends ID> ids) {
        if (!tenantScoped) {
            super.deleteAllById(ids);
            return;
        }
        throw refused("deleteAllById(ids)", "deleteById(id) or a scoped load plus delete(entity)");
    }

    @Override
    public void deleteAll(Iterable<? extends T> entities) {
        if (!tenantScoped) {
            super.deleteAll(entities);
            return;
        }
        throw refused("deleteAll(entities)", "delete(entity) per row loaded through the seam");
    }

    @Override
    public void deleteAll() {
        if (!tenantScoped) {
            super.deleteAll();
            return;
        }
        throw refused("deleteAll()", "scoped loads plus delete(entity) per row");
    }

    @Override
    public void deleteAllInBatch(Iterable<T> entities) {
        if (!tenantScoped) {
            super.deleteAllInBatch(entities);
            return;
        }
        throw refused("deleteAllInBatch(entities)", "delete(entity) per row loaded through the seam");
    }

    @Override
    public void deleteAllByIdInBatch(Iterable<ID> ids) {
        if (!tenantScoped) {
            super.deleteAllByIdInBatch(ids);
            return;
        }
        throw refused("deleteAllByIdInBatch(ids)", "deleteById(id) per id of the context tenant");
    }

    @Override
    public void deleteAllInBatch() {
        if (!tenantScoped) {
            super.deleteAllInBatch();
            return;
        }
        throw refused("deleteAllInBatch()", "scoped loads plus delete(entity) per row");
    }

    @Override
    public void deleteInBatch(Iterable<T> entities) {
        if (!tenantScoped) {
            super.deleteInBatch(entities);
            return;
        }
        throw refused("deleteInBatch(entities)", "delete(entity) per row loaded through the seam");
    }

    @Override
    public <S extends T> Optional<S> findOne(Example<S> example) {
        if (!tenantScoped) {
            return super.findOne(example);
        }
        throw refused("findOne(Example)", "findOneScoped(Specification)");
    }

    @Override
    public <S extends T> List<S> findAll(Example<S> example) {
        if (!tenantScoped) {
            return super.findAll(example);
        }
        throw refused("findAll(Example)", "findAllScoped(Specification, Pageable)");
    }

    @Override
    public <S extends T> List<S> findAll(Example<S> example, Sort sort) {
        if (!tenantScoped) {
            return super.findAll(example, sort);
        }
        throw refused("findAll(Example, Sort)", "findAllScoped(Specification, Pageable)");
    }

    @Override
    public <S extends T> Page<S> findAll(Example<S> example, Pageable pageable) {
        if (!tenantScoped) {
            return super.findAll(example, pageable);
        }
        throw refused("findAll(Example, Pageable)", "findAllScoped(Specification, Pageable)");
    }

    @Override
    public <S extends T> long count(Example<S> example) {
        if (!tenantScoped) {
            return super.count(example);
        }
        throw refused("count(Example)", "existsScoped(Specification)");
    }

    @Override
    public <S extends T> boolean exists(Example<S> example) {
        if (!tenantScoped) {
            return super.exists(example);
        }
        throw refused("exists(Example)", "existsScoped(Specification)");
    }

    @Override
    public <S extends T, R> R findBy(
            Example<S> example, Function<FluentQuery.FetchableFluentQuery<S>, R> queryFunction) {
        if (!tenantScoped) {
            return super.findBy(example, queryFunction);
        }
        throw refused("findBy(Example, Function)", "findOneScoped(Specification)");
    }

    // -------------------------------------------------------------------------
    // Guards and helpers
    // -------------------------------------------------------------------------

    /**
     * The write guard shared by every guarded shape: the entity must
     * belong to the context's scope — a {@link TenantScope} row of the
     * context's tenant, or an {@link OrganizationScope} row of the
     * context's organization with a null tenant — unless the audited
     * platform-administrator override is active.
     */
    private static void assertTenantWrite(Object entity) {
        TenantContext context = TenantContext.require();
        if (!context.crossTenantOverride() && !writeMatchesScope(context, entity)) {
            throw new TenantIsolationException("write refused: entity does not belong to the context's scope ("
                    + scopeDescription(context)
                    + "); entity tenant="
                    + ((TenantScope) entity).tenantId()
                    + ", entity organization="
                    + (entity instanceof OrganizationScope organizationScoped
                            ? organizationScoped.organizationId()
                            : null)
                    + ")");
        }
    }

    /**
     * Whether the entity belongs to the context's owning dimension: a
     * tenant-scope context owns the entity only when their tenants are
     * equal; an organization-scope context (issue #14) owns it only when
     * the entity is an {@link OrganizationScope} row of that organization
     * with a null owning tenant. Every other combination — including an
     * organization-owned row under a tenant scope, or any tenant-owned
     * row under an organization scope — is out of scope.
     */
    private static boolean writeMatchesScope(TenantContext context, Object entity) {
        if (context.organizationScoped()) {
            return entity instanceof OrganizationScope organizationScoped
                    && organizationScoped.tenantId() == null
                    && context.organizationId().equals(organizationScoped.organizationId());
        }
        UUID tenantOfEntity = tenantIdOf(entity);
        return context.tenantId().equals(tenantOfEntity);
    }

    /** The context's owning dimension, for refusal messages. */
    private static String scopeDescription(TenantContext context) {
        return context.organizationScoped()
                ? "organization " + context.organizationId()
                : "tenant " + context.tenantId();
    }

    /** The refusal every non-tenant-safe inherited operation raises. */
    private static TenantIsolationException refused(String operation, String instead) {
        return new TenantIsolationException(operation
                + " is refused on a tenant-scoped repository: it cannot be made tenant-safe; use "
                + instead
                + " (ADR-0002 inherited-surface policy)");
    }

    /**
     * Composes the forced scope predicate ahead of the caller's filter:
     * the seam's guarantee, applied identically to reads and existence
     * checks regardless of what the filter asks for. Tenant-scope
     * contexts compose {@code tenantId = :context}; organization-scope
     * contexts (issue #14) compose
     * {@code tenantId IS NULL AND organizationId = :context} and are
     * served only for {@link OrganizationScope} entities — an
     * organization-scope context over a purely tenant-owned entity is a
     * scoping error and refuses rather than guessing.
     */
    private Specification<T> scoped(@Nullable Specification<T> filter) {
        TenantContext context = TenantContext.require();
        if (context.organizationScoped() && !OrganizationScope.class.isAssignableFrom(getDomainClass())) {
            throw new TenantIsolationException("organization-scope context cannot serve "
                    + getDomainClass().getSimpleName()
                    + ": only OrganizationScope rows can be organization-owned (ADR-0002 third amendment)");
        }
        return (root, query, builder) -> {
            Predicate scopePredicate = context.organizationScoped()
                    ? builder.and(
                            builder.isNull(root.get(TENANT_ID_ATTRIBUTE)),
                            builder.equal(root.get(ORGANIZATION_ID_ATTRIBUTE), context.organizationId()))
                    : builder.equal(root.get(TENANT_ID_ATTRIBUTE), context.tenantId());
            Predicate callerPredicate = filter == null ? null : filter.toPredicate(root, query, builder);
            return callerPredicate == null ? scopePredicate : builder.and(scopePredicate, callerPredicate);
        };
    }

    /** Identity condition composed with the forced tenant predicate. */
    private Specification<T> idIs(Object id) {
        return (root, query, builder) -> builder.equal(root.get(ID_ATTRIBUTE), id);
    }

    /** Unsorted pageables adopt the platform listing order; sorted ones win. */
    private static Pageable withDefaultOrder(Pageable pageable) {
        if (pageable.getSort().isSorted()) {
            return pageable;
        }
        return new CursorPageable(pageable.getOffset(), pageable.getPageSize(), DEFAULT_SORT);
    }

    /** The entity's tenant, refusing non-scoped entities instead of guessing. */
    private static UUID tenantIdOf(Object entity) {
        if (entity instanceof TenantScope scoped && scoped.tenantId() != null) {
            return scoped.tenantId();
        }
        throw new TenantIsolationException("entity is not tenant-scoped; only TenantScope rows pass the seam");
    }
}
