package com.shipperdog.api.tenant;

import java.util.Optional;
import java.util.UUID;

/**
 * The acting request's tenant context (INV-001, issue #10, PRD 26): the
 * tenant the current operation targets, the actor, and whether the actor
 * arrived through the platform-administrator override. Carried on a
 * thread-local because the API is a blocking servlet stack; the web
 * layer begins the context for tenant-scoped routes and clears it when
 * the request completes, so handler code and the repository seam read
 * the same scope for the whole request.
 *
 * <p>Fail-closed is the core property: tenant-scoped data access
 * requires a context ({@link #require()}); with none present the
 * repository seam refuses the query rather than running it unscoped.
 * A missing context is therefore a programming error that fails loudly,
 * never a silent cross-tenant read.
 *
 * <p><strong>Organization-scope mode (issue #14, S3-02, PRD 14).</strong>
 * A context carries exactly one owning dimension: a tenant (tenant-scope
 * mode, {@link #begin}) or the organization itself (organization-scope
 * mode, {@link #beginOrganization}) for rows the organization owns
 * directly — organization-shared applications with a null owning tenant.
 * The seam composes the matching predicate per mode
 * ({@code tenantId = :context} vs
 * {@code tenantId IS NULL AND organizationId = :context}), so both
 * ownership modes keep INV-001 by construction and neither mode can read
 * or write the other's rows.
 *
 * <p>The platform-administrator override is explicit and audited: it is
 * set only from the {@code users.platform_admin} flag (never from any
 * organization or tenant role), and every request admitted through it is
 * recorded as a {@code CROSS_TENANT_ACCESS} audit event by the web
 * layer. Reads stay scoped to the route's scope regardless; the
 * override only admits requests from actors outside the scope's
 * membership and authorizes cross-scope writes for administration.
 */
public final class TenantContext {

    private static final ThreadLocal<TenantContext> CURRENT = new ThreadLocal<>();

    /** Null in organization-scope mode. */
    private final UUID tenantId;

    /** Null in tenant-scope mode. */
    private final UUID organizationId;

    private final UUID actorId;
    private final boolean crossTenantOverride;

    private TenantContext(UUID tenantId, UUID organizationId, UUID actorId, boolean crossTenantOverride) {
        this.tenantId = tenantId;
        this.organizationId = organizationId;
        this.actorId = actorId;
        this.crossTenantOverride = crossTenantOverride;
    }

    /**
     * Binds a tenant-scope context for the current thread. The web layer
     * owns this for HTTP requests; background jobs that touch
     * tenant-scoped data must begin (and {@link #end()}) their own scope
     * explicitly.
     *
     * @throws NullPointerException when the tenant or actor is missing —
     *     a scope without both is fail-open nonsense and must not exist
     */
    public static TenantContext begin(UUID tenantId, UUID actorId, boolean crossTenantOverride) {
        if (tenantId == null || actorId == null) {
            throw new NullPointerException("tenant context requires tenantId and actorId");
        }
        TenantContext context = new TenantContext(tenantId, null, actorId, crossTenantOverride);
        CURRENT.set(context);
        return context;
    }

    /**
     * Binds an organization-scope context (issue #14, S3-02): the request
     * targets rows the organization itself owns — organization-shared
     * applications (PRD 14) with a null owning tenant. Same binding
     * authority and same fail-closed contract as {@link #begin}; exactly
     * one mode exists per context, and the seam serves only null-tenant
     * rows of that organization under it.
     *
     * @throws NullPointerException when the organization or actor is
     *     missing — a scope without both is fail-open nonsense and must
     *     not exist
     */
    public static TenantContext beginOrganization(UUID organizationId, UUID actorId, boolean crossTenantOverride) {
        if (organizationId == null || actorId == null) {
            throw new NullPointerException("organization context requires organizationId and actorId");
        }
        TenantContext context = new TenantContext(null, organizationId, actorId, crossTenantOverride);
        CURRENT.set(context);
        return context;
    }

    /** The bound context, if any. Presence-checking is the caller's concern. */
    public static Optional<TenantContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * The bound context or a refusal: tenant-scoped access without a
     * context never runs unscoped — it throws, keeping INV-001 enforced
     * by construction even for code paths that forgot their scope.
     */
    public static TenantContext require() {
        TenantContext context = CURRENT.get();
        if (context == null) {
            throw new TenantIsolationException(
                    "no tenant context bound to this thread; tenant-scoped access is refused");
        }
        return context;
    }

    /** Clears the context; safe to call regardless of whether one is bound. */
    public static void end() {
        CURRENT.remove();
    }

    /**
     * The tenant this operation is scoped to; the seam's filter value in
     * tenant-scope mode. Null in organization-scope mode — callers that
     * need a non-null tenant must check {@link #organizationScoped()}
     * first (the seam does).
     */
    public UUID tenantId() {
        return tenantId;
    }

    /**
     * The organization this operation is scoped to; the seam's filter
     * value in organization-scope mode. Null in tenant-scope mode.
     */
    public UUID organizationId() {
        return organizationId;
    }

    /** Whether this context scopes to organization-owned rows (issue #14). */
    public boolean organizationScoped() {
        return organizationId != null;
    }

    /** The acting user; carried for audits and write guards. */
    public UUID actorId() {
        return actorId;
    }

    /**
     * Whether the actor was admitted through the platform-administrator
     * override (see class documentation); organization roles can never
     * set this.
     */
    public boolean crossTenantOverride() {
        return crossTenantOverride;
    }
}
