package com.shipperdog.api.authorization.internal.grant;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.authorization.internal.grant.EffectiveAccessResolver.EffectiveEntry;
import com.shipperdog.api.authorization.internal.grant.EffectiveAccessResolver.EffectiveSet;
import com.shipperdog.api.authorization.internal.grant.EffectiveAccessResolver.GrantShape;
import com.shipperdog.api.authorization.internal.grant.VersionAccessGrantEntity.AccessType;
import com.shipperdog.api.version.VersionAccess.VersionCatalogEntry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The effective-access semantics in isolation (issue #31, acceptance
 * criterion 6): the resolver is a pure function, so the union,
 * expiry, tag, and latest-N semantics are property-tested against an
 * independent brute-force oracle over randomized worlds — plus the
 * story's named behaviors (latest-N churn, live tag membership, TTL
 * visibility, expired grants contributing nothing).
 */
class EffectiveAccessResolverTest {

    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
    private static final List<String> TAGS = List.of("BETA", "QA", "PILOT");

    // -----------------------------------------------------------------------
    // Named behaviors
    // -----------------------------------------------------------------------

    @Test
    void latestNResolvesToTheNewestWorkingSetAndDropsTheOldestOnChurn() {
        // FR-011's example: latest 3. Four DRAFT versions newest-first;
        // the window holds the three newest, and the 4th arrival (a
        // NEWER version) pushes the oldest out — never the reverse.
        List<VersionCatalogEntry> catalog = workingSet("7.12.3", "7.12.2", "7.12.1", "7.12.0");
        GrantShape latest3 = grant(AccessType.LATEST_N, null, null, 3, null);
        EffectiveSet set = resolve(List.of(latest3), catalog, Map.of());

        assertThat(ids(set)).containsExactlyInAnyOrder(idOf(catalog, 0), idOf(catalog, 1), idOf(catalog, 2));
        assertThat(ids(set)).doesNotContain(idOf(catalog, 3));

        // Churn: a 5th, newer version arrives — the oldest drops out.
        List<VersionCatalogEntry> churned = workingSet("7.13.0", "7.12.3", "7.12.2", "7.12.1", "7.12.0");
        assertThat(ids(resolve(List.of(latest3), churned, Map.of())))
                .containsExactlyInAnyOrder(idOf(churned, 0), idOf(churned, 1), idOf(churned, 2));
    }

    @Test
    void terminalStatesNeverOccupyALatestNSlot() {
        // The newest version is ARCHIVED: the window counts working-set
        // ranks only, so the N newest WORKING versions fill it — an
        // archive cannot evict a live build from a tester's N.
        List<VersionCatalogEntry> catalog = List.of(
                entry("7.12.3", "ARCHIVED"),
                entry("7.12.2", "DRAFT"),
                entry("7.12.1", "ACTIVE"),
                entry("7.12.0", "REVOKED"));
        GrantShape latest2 = grant(AccessType.LATEST_N, null, null, 2, null);
        // WORKING candidates by rank: 7.12.2 (rank 0), 7.12.1 (rank 1),
        // 7.12.0 REVOKED (rank MAX). Window = the two working ones.
        assertThat(ids(resolve(List.of(latest2), catalog, Map.of())))
                .containsExactlyInAnyOrder(idOf(catalog, 1), idOf(catalog, 2));
    }

    @Test
    void tagGrantsCoverTaggedVersionsAndExpiryFiltersLive() {
        List<VersionCatalogEntry> catalog = workingSet("2.0.0", "1.0.0");
        Set<UUID> beta = Set.of(idOf(catalog, 1));
        GrantShape live = grant(AccessType.TAG, null, "BETA", null, NOW.plusSeconds(60));
        GrantShape expired = grant(AccessType.TAG, null, "BETA", null, NOW.minusSeconds(1));
        GrantShape never = grant(AccessType.TAG, null, "QA", null, null);

        assertThat(ids(resolve(List.of(live), catalog, Map.of("BETA", beta)))).containsExactly(idOf(catalog, 1));
        assertThat(ids(resolve(List.of(expired), catalog, Map.of("BETA", beta))))
                .as("expired grants never authorize (AC5)")
                .isEmpty();
        assertThat(ids(resolve(List.of(never), catalog, Map.of("QA", Set.of()))))
                .isEmpty();
    }

    @Test
    void overlappingGrantsUnionAndTheTtlIsTheNearestContributingExpiry() {
        List<VersionCatalogEntry> catalog = workingSet("3.0.0", "2.0.0", "1.0.0");
        UUID version = idOf(catalog, 2);
        GrantShape neverExpires = grant(AccessType.APPLICATION, null, null, null, null);
        GrantShape soon = grant(AccessType.EXPLICIT_VERSION, version, null, null, NOW.plusSeconds(60));
        GrantShape later = grant(AccessType.EXPLICIT_VERSION, version, null, null, NOW.plusSeconds(3600));

        EffectiveSet set = resolve(List.of(neverExpires, soon, later), catalog, Map.of());
        assertThat(ids(set)).containsExactlyInAnyOrderElementsOf(idsOfAll(catalog));
        EffectiveEntry entry = set.entriesByVersionId().get(version);
        assertThat(entry.sourceGrantIds())
                .as("both explicit grants and the app-wide grant contribute (union, AC6)")
                .containsExactlyInAnyOrder(neverExpires.grantId(), soon.grantId(), later.grantId());
        assertThat(entry.expiresAt())
                .as("a never-expiring contributor makes the entry's TTL null (AC5)")
                .isNull();

        EffectiveSet withoutAppWide = resolve(List.of(soon, later), catalog, Map.of());
        assertThat(withoutAppWide.entriesByVersionId().get(version).expiresAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void anExpiryExactlyAtNowIsAlreadyExpired() {
        List<VersionCatalogEntry> catalog = workingSet("1.0.0");
        GrantShape expiringNow = grant(AccessType.APPLICATION, null, null, null, NOW);
        assertThat(ids(resolve(List.of(expiringNow), catalog, Map.of()))).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Property: randomized worlds against an independent oracle (AC6)
    // -----------------------------------------------------------------------

    @Test
    void randomizedWorldsMatchTheIndependentOracle() {
        SplittableRandom random = new SplittableRandom(20261004);
        for (int round = 0; round < 500; round++) {
            World world = randomWorld(random);
            EffectiveSet set = EffectiveAccessResolver.resolve(world.grants, world.catalog, world.tagged, NOW);
            Map<UUID, Set<UUID>> oracleSources = oracle(world);
            for (VersionCatalogEntry version : world.catalog) {
                EffectiveEntry entry = set.entriesByVersionId().get(version.versionId());
                Set<UUID> expected = oracleSources.get(version.versionId());
                if (expected == null || expected.isEmpty()) {
                    assertThat(entry)
                            .as("round %d: version %s must be absent", round, version.versionName())
                            .isNull();
                } else {
                    assertThat(entry)
                            .as("round %d: version %s", round, version.versionName())
                            .isNotNull();
                    assertThat(entry.sourceGrantIds())
                            .as("round %d: sources of %s", round, version.versionName())
                            .containsExactlyInAnyOrderElementsOf(new ArrayList<>(expected));
                    assertThat(entry.expiresAt()).isEqualTo(nearest(expected, world));
                }
            }
            // Entries preserve catalog order (newest first).
            List<UUID> ordered =
                    set.entries().stream().map(EffectiveEntry::versionId).toList();
            List<UUID> catalogOrder =
                    world.catalog.stream().map(VersionCatalogEntry::versionId).toList();
            assertThat(ordered)
                    .containsExactlyElementsOf(catalogOrder.stream()
                            .filter(set.entriesByVersionId()::containsKey)
                            .toList());
        }
    }

    /** Builds the oracle's per-version source sets by direct per-shape reasoning. */
    private static Map<UUID, Set<UUID>> oracle(World world) {
        Map<UUID, Set<UUID>> sources = new LinkedHashMap<>();
        // The latest-N oracle slices the working set explicitly.
        Map<UUID, List<UUID>> latestWindowByN = new LinkedHashMap<>();
        List<UUID> workingNewestFirst = new ArrayList<>();
        for (VersionCatalogEntry version : world.catalog) {
            if ("DRAFT".equals(version.status()) || "ACTIVE".equals(version.status())) {
                workingNewestFirst.add(version.versionId());
            }
        }
        for (GrantShape grant : world.grants) {
            if (grant.accessType() == AccessType.LATEST_N && unexpired(grant)) {
                latestWindowByN.put(
                        grant.grantId(),
                        workingNewestFirst.subList(0, Math.min(grant.latestN(), workingNewestFirst.size())));
            }
        }
        for (VersionCatalogEntry version : world.catalog) {
            for (GrantShape grant : world.grants) {
                if (!unexpired(grant)) {
                    continue;
                }
                boolean covers = switch (grant.accessType()) {
                    case EXPLICIT_VERSION -> version.versionId().equals(grant.versionId());
                    case APPLICATION -> true;
                    case TAG -> world.tagged.getOrDefault(grant.tag(), Set.of()).contains(version.versionId());
                    case LATEST_N -> latestWindowByN.get(grant.grantId()).contains(version.versionId());
                    default -> false;
                };
                if (covers) {
                    sources.computeIfAbsent(version.versionId(), ignored -> new HashSet<>())
                            .add(grant.grantId());
                }
            }
        }
        return sources;
    }

    private static Instant nearest(Set<UUID> sources, World world) {
        Instant nearest = null;
        boolean anyNever = false;
        for (GrantShape grant : world.grants) {
            if (!sources.contains(grant.grantId())) {
                continue;
            }
            if (grant.expiresAt() == null) {
                anyNever = true;
            } else if (nearest == null || grant.expiresAt().isBefore(nearest)) {
                nearest = grant.expiresAt();
            }
        }
        return anyNever ? null : nearest;
    }

    private record World(List<GrantShape> grants, List<VersionCatalogEntry> catalog, Map<String, Set<UUID>> tagged) {}

    private static World randomWorld(SplittableRandom random) {
        int versionCount = 1 + random.nextInt(8);
        List<VersionCatalogEntry> catalog = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < versionCount; i++) {
            String name = (random.nextInt(9) + 1) + "." + random.nextInt(10) + "." + random.nextInt(10);
            while (!names.add(name)) {
                name = (random.nextInt(9) + 1) + "." + random.nextInt(10) + "." + (random.nextInt(100));
            }
            String status = List.of("DRAFT", "ACTIVE", "ARCHIVED", "REVOKED").get(random.nextInt(4));
            catalog.add(new VersionCatalogEntry(UUID.randomUUID(), name, status, sortKeyOf(name)));
        }
        catalog.sort((a, b) -> b.sortKey().compareTo(a.sortKey()));
        Map<String, Set<UUID>> tagged = new LinkedHashMap<>();
        for (String tag : TAGS) {
            Set<UUID> ids = new HashSet<>();
            for (VersionCatalogEntry version : catalog) {
                if (random.nextInt(3) == 0) {
                    ids.add(version.versionId());
                }
            }
            tagged.put(tag, ids);
        }
        int grantCount = random.nextInt(9);
        List<GrantShape> grants = new ArrayList<>();
        for (int i = 0; i < grantCount; i++) {
            AccessType shape = List.of(
                            AccessType.EXPLICIT_VERSION, AccessType.APPLICATION, AccessType.TAG, AccessType.LATEST_N)
                    .get(random.nextInt(4));
            UUID versionId = shape == AccessType.EXPLICIT_VERSION
                    ? catalog.get(random.nextInt(catalog.size())).versionId()
                    : null;
            String tag = shape == AccessType.TAG ? TAGS.get(random.nextInt(TAGS.size())) : null;
            Integer n = shape == AccessType.LATEST_N ? 1 + random.nextInt(4) : null;
            int expiryKind = random.nextInt(3);
            Instant expiresAt = switch (expiryKind) {
                case 0 -> null;
                case 1 -> NOW.minusSeconds(1 + random.nextInt(1000));
                default -> NOW.plusSeconds(1 + random.nextInt(1000));
            };
            grants.add(new GrantShape(UUID.randomUUID(), shape, versionId, tag, n, expiresAt));
        }
        return new World(grants, List.copyOf(catalog), tagged);
    }

    private static String sortKeyOf(String name) {
        // A sort key consistent with the name's numeric order; the
        // resolver only relies on the LIST being newest-first, so a
        // zero-padded encoding of the name suffices.
        String[] parts = name.split("\\.");
        return String.format(
                "%05d.%05d.%05d", Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private static EffectiveSet resolve(
            List<GrantShape> grants, List<VersionCatalogEntry> catalog, Map<String, Set<UUID>> tagged) {
        return EffectiveAccessResolver.resolve(grants, catalog, tagged, NOW);
    }

    private static GrantShape grant(AccessType shape, UUID versionId, String tag, Integer latestN, Instant expiresAt) {
        return new GrantShape(UUID.randomUUID(), shape, versionId, tag, latestN, expiresAt);
    }

    private static List<UUID> ids(EffectiveSet set) {
        return set.entries().stream().map(EffectiveEntry::versionId).toList();
    }

    private static UUID idOf(List<VersionCatalogEntry> catalog, int index) {
        return catalog.get(index).versionId();
    }

    private static List<UUID> idsOfAll(List<VersionCatalogEntry> catalog) {
        return catalog.stream().map(VersionCatalogEntry::versionId).toList();
    }

    private static List<VersionCatalogEntry> workingSet(String... names) {
        List<VersionCatalogEntry> catalog = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            catalog.add(entry(names[i], "DRAFT"));
        }
        return catalog;
    }

    private static VersionCatalogEntry entry(String name, String status) {
        return new VersionCatalogEntry(UUID.randomUUID(), name, status, sortKeyOf(name));
    }

    private static boolean unexpired(GrantShape grant) {
        return grant.expiresAt() == null || grant.expiresAt().isAfter(NOW);
    }
}
