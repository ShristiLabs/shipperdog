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
 * <p>The platform-administrator override is explicit and audited: it is
 * set only from the {@code users.platform_admin} flag (never from any
 * organization or tenant role), and every request admitted through it is
 * recorded as a {@code CROSS_TENANT_ACCESS} audit event by the web
 * layer. Reads stay scoped to the route's tenant regardless; the
 * override only admits requests from actors outside the tenant's
 * membership and authorizes cross-tenant writes for administration.
 */
public final class TenantContext {

    private static final ThreadLocal<TenantContext> CURRENT = new ThreadLocal<>();

    private final UUID tenantId;
    private final UUID actorId;
    private final boolean crossTenantOverride;

    private TenantContext(UUID tenantId, UUID actorId, boolean crossTenantOverride) {
        this.tenantId = tenantId;
        this.actorId = actorId;
        this.crossTenantOverride = crossTenantOverride;
    }

    /**
     * Binds the context for the current thread. The web layer owns this
     * for HTTP requests; background jobs that touch tenant-scoped data
     * must begin (and {@link #end()}) their own scope explicitly.
     *
     * @throws NullPointerException when the tenant or actor is missing —
     *     a scope without both is fail-open nonsense and must not exist
     */
    public static TenantContext begin(UUID tenantId, UUID actorId, boolean crossTenantOverride) {
        if (tenantId == null || actorId == null) {
            throw new NullPointerException("tenant context requires tenantId and actorId");
        }
        TenantContext context = new TenantContext(tenantId, actorId, crossTenantOverride);
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

    /** The tenant this operation is scoped to; the seam's filter value. */
    public UUID tenantId() {
        return tenantId;
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
