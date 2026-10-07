package com.shipperdog.api.authorization.internal.grant;

import com.shipperdog.api.authorization.internal.grant.VersionAccessGrantEntity.AccessType;
import com.shipperdog.api.version.VersionAccess.VersionCatalogEntry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The effective-access computation (issue #31, S7-02): the union of
 * what a tester's unexpired grants authorize over one application's
 * version catalog, as ONE pure function over resolved inputs — no
 * repository, no clock, no Spring — so the semantics are
 * property-testable in isolation (issue #31 acceptance criterion 6)
 * and the same function serves the admin effective-access view today
 * and the portal catalog (S8-02) later, cached above it by S7-03.
 *
 * <p>Semantics (the story's scope: overlapping grants are additive —
 * union; no negative grants in V1):
 *
 * <ul>
 *   <li>{@code EXPLICIT_VERSION} adds exactly the addressed version
 *       (any lifecycle state — the engine's version/artifact layers
 *       apply their own lifecycle and availability gates at decision
 *       time; the view carries each version's status so an
 *       administrator sees a REVOKED entry for what it is).</li>
 *   <li>{@code APPLICATION} adds every version of the catalog.</li>
 *   <li>{@code TAG} adds every version currently carrying the tag —
 *       evaluation-time resolution, so a newly tagged version joins
 *       without any grant change (the caller supplies the tagged id
 *       set per tag; the set form is one query per distinct tag).</li>
 *   <li>{@code LATEST_N} adds the N newest of the WORKING SET (DRAFT
 *       and the M2-reserved ACTIVE — the default listing window): an
 *       ARCHIVED or REVOKED version never occupies a window slot, so
 *       archiving a build cannot evict a live one from a tester's N
 *       (the churn case of FR-011's "latest 3" — a 4th version
 *       arriving drops the oldest out of the window).</li>
 *   <li>{@code expiresAt} in the past contributes nothing — expired
 *       grants never authorize (evaluation-time enforcement, INV-006:
 *       nothing is swept or rewritten).</li>
 * </ul>
 *
 * <p>Each resolved entry carries the contributing grant ids and the
 * entry's time-to-live: the nearest {@code expiresAt} across the
 * contributing grants, null when any contributor never expires — the
 * TTL visibility the story's acceptance criterion 5 demands.
 */
final class EffectiveAccessResolver {

    private EffectiveAccessResolver() {}

    /** One grant as the resolver sees it: shape, target, expiry. */
    record GrantShape(
            UUID grantId, AccessType accessType, UUID versionId, String tag, Integer latestN, Instant expiresAt) {}

    /**
     * One authorized version: its catalog facts (including the
     * normalized {@code sortKey} — the cross-dimension merge order the
     * portal catalog needs when more than one tenant grants the same
     * application, issue #37 folding the #36 review's ordering
     * finding), its contributing grants, and its TTL.
     */
    record EffectiveEntry(
            UUID versionId,
            String versionName,
            String status,
            String sortKey,
            List<UUID> sourceGrantIds,
            Instant expiresAt,
            boolean expires) {}

    /** The resolved effective access: entries newest-first, keyed by version id. */
    record EffectiveSet(Map<UUID, EffectiveEntry> entriesByVersionId, Instant resolvedAt) {

        /** Newest-first view (the catalog's order). */
        List<EffectiveEntry> entries() {
            return List.copyOf(entriesByVersionId.values());
        }
    }

    /**
     * Resolves the effective access: {@code catalog} newest-first
     * (the {@code sort_key} order the version module returns),
     * {@code taggedVersions} the currently-tagged version id set per
     * tag value (queried once per distinct TAG grant), {@code now}
     * the evaluation instant.
     */
    static EffectiveSet resolve(
            Collection<GrantShape> grants,
            List<VersionCatalogEntry> catalog,
            Map<String, Set<UUID>> taggedVersions,
            Instant now) {
        List<GrantShape> live =
                grants.stream().filter(grant -> unexpired(grant, now)).toList();
        LinkedHashMap<UUID, EffectiveEntry> entries = new LinkedHashMap<>();
        for (VersionCatalogEntry version : catalog) {
            List<UUID> sources = new ArrayList<>();
            Instant nearestExpiry = null;
            boolean anyNeverExpires = false;
            for (GrantShape grant : live) {
                if (!covers(grant, version, catalog, taggedVersions)) {
                    continue;
                }
                sources.add(grant.grantId());
                if (grant.expiresAt() == null) {
                    anyNeverExpires = true;
                } else if (nearestExpiry == null || grant.expiresAt().isBefore(nearestExpiry)) {
                    nearestExpiry = grant.expiresAt();
                }
            }
            if (sources.isEmpty()) {
                continue;
            }
            entries.put(
                    version.versionId(),
                    new EffectiveEntry(
                            version.versionId(),
                            version.versionName(),
                            version.status(),
                            version.sortKey(),
                            List.copyOf(sources),
                            anyNeverExpires ? null : nearestExpiry,
                            !anyNeverExpires));
        }
        return new EffectiveSet(entries, now);
    }

    /** Whether the grant, live at {@code now}, covers the catalog version. */
    private static boolean covers(
            GrantShape grant,
            VersionCatalogEntry version,
            List<VersionCatalogEntry> catalog,
            Map<String, Set<UUID>> taggedVersions) {
        return switch (grant.accessType()) {
            case EXPLICIT_VERSION ->
                grant.versionId() != null && grant.versionId().equals(version.versionId());
            case APPLICATION -> true;
            case TAG ->
                grant.tag() != null
                        && taggedVersions.getOrDefault(grant.tag(), Set.of()).contains(version.versionId());
            case LATEST_N -> grant.latestN() != null && workingSetRank(version, catalog) < grant.latestN();
            default -> false;
        };
    }

    /**
     * The version's 0-based rank within the working set, catalog
     * order (newest-first). The catalog arrives sorted by
     * {@code sort_key} descending, so the rank is one pass — the
     * same comparison the decision-time probe expresses as an
     * indexed count.
     */
    private static int workingSetRank(VersionCatalogEntry version, List<VersionCatalogEntry> catalog) {
        int rank = 0;
        for (VersionCatalogEntry entry : catalog) {
            if (!isWorkingSet(entry)) {
                continue;
            }
            if (entry.versionId().equals(version.versionId())) {
                return rank;
            }
            rank++;
        }
        return Integer.MAX_VALUE;
    }

    private static boolean isWorkingSet(VersionCatalogEntry entry) {
        return "DRAFT".equals(entry.status()) || "ACTIVE".equals(entry.status());
    }

    private static boolean unexpired(GrantShape grant, Instant now) {
        return grant.expiresAt() == null || grant.expiresAt().isAfter(now);
    }
}
