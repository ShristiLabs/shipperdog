package com.shipperdog.api.tenant;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a repository over a {@link TenantScope} entity that is
 * <em>authorized</em> to query across tenants (INV-001, issue #10): the
 * authorization engine's role-resolution surface, whose whole purpose is
 * to answer "which roles does this user hold, in any tenant".
 *
 * <p>Repositories carrying this annotation are review-critical: they
 * must never serve endpoint data paths directly, only the authorization
 * engine's membership questions. An architecture test pairs it with the
 * seam rule so every other repository over tenant-scoped data is forced
 * onto {@link TenantScopedRepository}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface CrossTenantByDesign {}
