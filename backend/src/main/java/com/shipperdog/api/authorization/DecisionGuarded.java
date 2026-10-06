package com.shipperdog.api.authorization;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a tenant-scoped read or download surface as guarded by the
 * layered engine (issue #30, acceptance criterion 1): the marked class
 * routes its authorization decision through
 * {@link AuthorizationService#decide} — it may not hand-roll resource
 * eligibility from repositories or the cross-tenant-by-design
 * catalogs. An architecture test enforces the contract:
 *
 * <ul>
 *   <li>a {@code @DecisionGuarded} class must depend on
 *       {@link AuthorizationService} (the decision flows through the
 *       engine);</li>
 *   <li>it must not depend on any other module's {@code .internal.}
 *       packages or on {@code @CrossTenantByDesign} types (no
 *       ad-hoc cross-tenant authorization reads beside the engine).</li>
 * </ul>
 *
 * <p>Marking is part of adding a tenant-scoped read or download
 * surface: the S7-05 isolation suite polices behavior; this marker
 * and its rule keep the structural half. The class's OWN module
 * repositories remain usable — serving the page after the engine's
 * decision is data access, not authorization (the catalog listing's
 * sanctioned visibility union IS the application layer's set form,
 * ADR-0002 fourth amendment).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface DecisionGuarded {}
