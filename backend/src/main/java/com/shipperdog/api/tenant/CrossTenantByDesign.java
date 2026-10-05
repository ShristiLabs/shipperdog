package com.shipperdog.api.tenant;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a repository over a {@link TenantScope} entity that is
 * <em>authorized</em> to query across tenants (INV-001, issue #10): the
 * authorization engine's role-resolution surface ("which roles does
 * this user hold, in any tenant") and — since the application registry
 * (issue #13, ADR-0002 second amendment) — registry guards whose
 * question is organization-scoped by their story's own definition
 * (ownership resolution for the evaluator and route binding;
 * organization-wide uniqueness of bundle ids / package names / names).
 *
 * <p>Repositories carrying this annotation are review-critical: they
 * must never serve endpoint data paths directly — row reads and writes
 * go through {@link TenantScopedRepository} — and they answer only
 * their sanctioned questions (tuples and booleans, never rows to
 * callers). An architecture test pairs it with the seam rule so every
 * other repository over tenant-scoped data is forced onto the seam.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface CrossTenantByDesign {}
