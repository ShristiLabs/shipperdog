/**
 * Environment management (issue #58, S9-04, FR-025, PRD 8.11): the
 * organization-grain deployment contexts a release flows through —
 * Development, QA, UAT, Pilot, Production, plus custom environments —
 * each with its own versioned configuration snapshots, tenant
 * assignments (the release-target allow-list), and reference-only
 * credential bindings (secret references, never material, PRD 25). An
 * environment describes WHAT the application points to; a ring (the
 * release module's reference data) describes WHO receives: orthogonal
 * axes kept in separate tables by design.
 *
 * <p>Internal implementation details belong in an {@code .internal.} subpackage
 * here; other modules must not import it (enforced by ArchUnit).
 */
package com.shipperdog.api.environment;
