/**
 * Organizations, the top-level ownership scope of the platform (issue #4,
 * PRD 8.1/FR-001): lifecycle and settings, user membership with roles, and
 * the stored identity-provider configuration record (S14 placeholder).
 * The public {@link com.shipperdog.api.organization.OrganizationAccess}
 * surface serves cross-module queries (login blocks, token claims);
 * membership role checks here are deliberately minimal — the RBAC engine
 * (S1-05) owns general authorization.
 *
 * <p>Internal implementation details belong in an {@code .internal.} subpackage
 * here; other modules must not import it (enforced by ArchUnit).
 */
package com.shipperdog.api.organization;
