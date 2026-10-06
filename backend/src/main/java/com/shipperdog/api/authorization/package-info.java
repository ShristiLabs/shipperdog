/**
 * Access decisions across tenants, applications, versions, and artifacts.
 *
 * <p>Two engines live here, deliberately beside each other (S7-01,
 * issue #30):
 *
 * <ul>
 *   <li><strong>RBAC</strong> ({@code internal.evaluator}) — who may
 *       call the console's management surface: the permission catalog,
 *       the role matrix, and the {@code @PreAuthorize} enforcement
 *       point.</li>
 *   <li><strong>The layered authorization engine</strong>
 *       ({@code internal.engine}, PRD 11/12) — which resources a
 *       subject may reach: {@code AuthorizationService.decide} walking
 *       the User → Organization → Tenant → Application → Version →
 *       Artifact → Distribution chain with deny-by-default, opaque
 *       denials, an audited decision trail, and the entitlement seam
 *       S7-02's grants plug into.</li>
 * </ul>
 *
 * <p>Internal implementation details belong in an {@code .internal.} subpackage
 * here; other modules must not import it (enforced by ArchUnit).
 */
package com.shipperdog.api.authorization;
