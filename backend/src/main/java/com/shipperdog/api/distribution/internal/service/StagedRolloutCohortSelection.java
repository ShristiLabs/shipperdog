package com.shipperdog.api.distribution.internal.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The staged rollout's deterministic cohort selection (issue #66,
 * S13-06, AC1 — the story's own technical note: "cohort selection
 * needs stability across retries (seeded selection by tenant ID hash
 * — document algorithm; re-planning is explicit)").
 *
 * <p><strong>The algorithm (documented, the one implementation):</strong>
 * the release's target tenants are ordered by SHA-256(releaseId
 * most-significant-bytes-first ‖ tenantId most-significant-bytes-first),
 * compared as unsigned 256-bit values ascending, ties broken by the
 * tenant id's own bits (a total order — equal digests are
 * cryptographically unreachable but the order stays deterministic
 * regardless). A PERCENT stage of {@code p} percent over {@code N}
 * targets takes the FIRST {@code ceil(p * N / 100)} tenants of that
 * order as its cohort; an EXPLICIT stage takes its named list. The
 * plan only ever widens: a PERCENT stage's slice must contain every
 * tenant the previous stage reached (pure percent stages are prefixes
 * of the one order, so strictly increasing percentages widen by
 * construction), and an EXPLICIT stage must name a superset — the
 * precise refusal answers the shrinking plan. The selection is a pure
 * function of (releaseId, target set, stage shapes): re-authoring the
 * same plan resolves the same cohorts, a retry re-lands the same
 * rows, and only an explicit re-planning (a new authoring) can change
 * membership.
 */
final class StagedRolloutCohortSelection {

    private StagedRolloutCohortSelection() {}

    /** One stage of the authoring request, as the selection resolves it. */
    sealed interface StageShape permits PercentStage, ExplicitStage {}

    /** A percentage stage: the first ceil(percent * N / 100) tenants of the seeded order. */
    record PercentStage(int percent) implements StageShape {}

    /** An explicit stage: the named tenants (the served order is the seeded rank). */
    record ExplicitStage(List<UUID> tenants) implements StageShape {}

    /** The resolved plan: every stage's cohort, in the stages' own order. */
    record ResolvedCohorts(List<List<RankedTenant>> stages) {}

    /** One selected tenant beside its rank in the release's seeded order. */
    record RankedTenant(UUID tenantId, int rank) {}

    /**
     * Resolves every stage's cohort over the release's target tenants:
     * the seeded order once, then each stage's slice or list, with the
     * monotone-widening rule enforced between consecutive stages.
     *
     * @throws IllegalArgumentException when a stage's shape is invalid:
     *                                  a percentage outside 1..100, an
     *                                  explicit list naming a non-target
     *                                  tenant, a duplicate stage entry,
     *                                  or any stage that would shrink
     *                                  the cohort the previous stage
     *                                  reached (the plan only widens)
     */
    static ResolvedCohorts resolve(UUID releaseId, List<UUID> targetTenants, List<StageShape> stages) {
        List<RankedTenant> order = seededOrder(releaseId, targetTenants);
        List<List<RankedTenant>> resolved = new ArrayList<>(stages.size());
        LinkedHashSet<UUID> reached = new LinkedHashSet<>();
        for (int i = 0; i < stages.size(); i++) {
            int position = i + 1;
            LinkedHashSet<UUID> cohort;
            if (shapeOf(stages, i) instanceof PercentStage percent) {
                if (percent.percent() < 1 || percent.percent() > 100) {
                    throw new IllegalArgumentException(
                            "stage " + position + ": the percentage must be an integer between 1 and 100");
                }
                int take = ceiling(percent.percent() * order.size(), 100);
                cohort = new LinkedHashSet<>();
                for (int rank = 0; rank < take; rank++) {
                    cohort.add(order.get(rank).tenantId());
                }
            } else {
                ExplicitStage explicit = (ExplicitStage) shapeOf(stages, i);
                Map<UUID, RankedTenant> byTenant = new LinkedHashMap<>();
                order.forEach(ranked -> byTenant.put(ranked.tenantId(), ranked));
                cohort = new LinkedHashSet<>();
                for (UUID tenant : explicit.tenants()) {
                    RankedTenant ranked = byTenant.get(tenant);
                    if (ranked == null) {
                        throw new IllegalArgumentException("stage " + position + ": tenant " + tenant
                                + " is not one of the release's target tenants");
                    }
                    if (!cohort.add(tenant)) {
                        throw new IllegalArgumentException(
                                "stage " + position + ": tenant " + tenant + " is named twice");
                    }
                }
            }
            LinkedHashSet<UUID> dropped = new LinkedHashSet<>(reached);
            dropped.removeAll(cohort);
            if (!dropped.isEmpty()) {
                throw new IllegalArgumentException("stage " + position + ": the stage drops " + dropped.size()
                        + " tenant(s) the previous stage reached (a staged plan only widens)");
            }
            reached = cohort;
            Map<UUID, RankedTenant> rankOf = new LinkedHashMap<>();
            order.forEach(ranked -> rankOf.put(ranked.tenantId(), ranked));
            resolved.add(cohort.stream().map(rankOf::get).toList());
        }
        return new ResolvedCohorts(List.copyOf(resolved));
    }

    private static StageShape shapeOf(List<StageShape> stages, int index) {
        StageShape shape = stages.get(index);
        if (shape == null) {
            throw new IllegalArgumentException("stage " + (index + 1) + ": the stage shape is missing");
        }
        return shape;
    }

    /**
     * The release's seeded order: every target tenant ranked by the
     * unsigned value of SHA-256 over (releaseId ‖ tenantId)
     * most-significant-bytes-first, tenant-id tie-broken — the pure
     * function a re-authoring re-evaluates identically.
     */
    static List<RankedTenant> seededOrder(UUID releaseId, List<UUID> targetTenants) {
        record DigestEntry(java.math.BigInteger digest, long tieBreak, UUID tenantId) {}
        List<DigestEntry> entries = new ArrayList<>(targetTenants.size());
        for (UUID tenant : targetTenants) {
            entries.add(new DigestEntry(unsignedDigestOf(releaseId, tenant), tenant.getLeastSignificantBits(), tenant));
        }
        entries.sort(Comparator.comparing(DigestEntry::digest).thenComparing(DigestEntry::tieBreak));
        List<RankedTenant> order = new ArrayList<>(entries.size());
        for (int rank = 0; rank < entries.size(); rank++) {
            order.add(new RankedTenant(entries.get(rank).tenantId(), rank));
        }
        return List.copyOf(order);
    }

    /** ceil(dividend / divisor) for positive integers. */
    private static int ceiling(long dividend, int divisor) {
        return (int) ((dividend + divisor - 1) / divisor);
    }

    /** SHA-256(releaseId ‖ tenantId) as an unsigned 256-bit integer. */
    private static java.math.BigInteger unsignedDigestOf(UUID releaseId, UUID tenantId) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update(uuidBytes(releaseId));
            sha256.update(uuidBytes(tenantId));
            return new java.math.BigInteger(1, sha256.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** The UUID's 16 bytes, most-significant first (RFC 4122 network byte order). */
    private static byte[] uuidBytes(UUID id) {
        byte[] bytes = new byte[16];
        long most = id.getMostSignificantBits();
        long least = id.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) (most >>> (8 * (7 - i)));
            bytes[8 + i] = (byte) (least >>> (8 * (7 - i)));
        }
        return bytes;
    }
}
