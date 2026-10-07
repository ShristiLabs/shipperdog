package com.shipperdog.api.authorization.internal.cache;

import com.shipperdog.api.application.ApplicationAccess;
import com.shipperdog.api.authorization.AuthorizationDecisionInvalidation;
import com.shipperdog.api.authorization.internal.grant.VersionAccessGrantCatalog;
import com.shipperdog.api.authorization.internal.grant.VersionAccessGrantEntity;
import com.shipperdog.api.config.MetricNames;
import com.shipperdog.api.tester.TenantTesterMembershipAccess;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The decision-input cache (issue #32, S7-03): Redis-backed, above the
 * grant contributor and the effective-set resolution — the store the
 * layered engine's decision path resolves its repeatable inputs
 * through. INPUTS ONLY, never decisions (the story's technical note):
 * the grant set per tester x tenant, the membership-effectiveness
 * flag, the tenant-application visibility flag, and the tester's
 * linked-tenant set. The engine still walks every layer and computes
 * every evaluation; a cache hit replaces the indexed database
 * descent, nothing else. The PRD 42 under-100 ms SLO is the driver —
 * a warm decision resolves its inputs without touching PostgreSQL.
 *
 * <p><strong>Fail-closed correctness (AC3):</strong> any Redis
 * failure — connection refused, command timeout, corrupt or
 * future-versioned payload — answers the LIVE loader's result and
 * counts {@code degraded}. The cache can only make decisions slower,
 * never different: a corrupt entry is deleted best-effort, and no
 * code path can synthesize an allow the engine would deny (the
 * engine evaluates the cached input set with exactly the semantics it
 * applies to the database's). Consecutive failures OPEN a fail-fast
 * breaker (a short cooldown): while open, lookups and invalidations
 * skip Redis entirely, so a dead Redis costs each decision only the
 * loader's own latency — the load-test-measured seconds-per-decision
 * of a naive per-lookup timeout stack never materialize.
 *
 * <p><strong>Write-through invalidation (AC2):</strong> the
 * {@link AuthorizationDecisionInvalidation} methods drop affected
 * keys at before-commit when a transaction is active (no reader sees
 * committed state through stale cache; a later rollback only
 * over-invalidates) and immediately otherwise (the non-transactional
 * mutators whose inner write attempt already committed). An
 * invalidation that cannot run — Redis down mid-mutation — counts
 * degraded and never fails the mutation; the TTL bounds what it can
 * leave behind.
 *
 * <p><strong>Payload versioning:</strong> every key carries the
 * payload schema version ({@code v1}) in the key itself and in the
 * body, so a format change orphans old entries to TTL expiry instead
 * of poisoning a reader — a body whose embedded version differs from
 * the reader's is corrupt-by-definition and degrades.
 */
public class AuthorizationDecisionCache implements DecisionInputs, AuthorizationDecisionInvalidation {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationDecisionCache.class);

    /** The payload schema version; bump on any serialization change. */
    private static final int PAYLOAD_VERSION = 1;

    /** Degraded warnings throttle: at most one per minute per process. */
    private static final long WARN_THROTTLE_NANOS = Duration.ofSeconds(60).toNanos();

    private static final ObjectMapper PAYLOADS = new ObjectMapper();

    private final StringRedisTemplate redis;
    private final DecisionCacheProperties settings;
    private final VersionAccessGrantCatalog grants;
    private final ObjectProvider<TenantTesterMembershipAccess> memberships;
    private final ObjectProvider<ApplicationAccess> applications;
    private final MeterRegistry meters;
    private final AtomicLong lastDegradedWarn = new AtomicLong();

    /**
     * The fail-fast breaker (AC3's operational half): consecutive
     * Redis failures OPEN the cache for a short cooldown, so a Redis
     * outage costs each decision the loader's latency — not a
     * per-lookup connect/timeout wait stacked across every input. The
     * cooldown's expiry is the half-open probe; any successful
     * operation closes the breaker again.
     */
    private static final int BREAKER_THRESHOLD = 3;

    private static final long BREAKER_COOLDOWN_NANOS = Duration.ofSeconds(5).toNanos();
    private final java.util.concurrent.atomic.AtomicInteger consecutiveFailures =
            new java.util.concurrent.atomic.AtomicInteger();
    private final AtomicLong breakerOpenUntil = new AtomicLong(0);

    public AuthorizationDecisionCache(
            StringRedisTemplate redis,
            DecisionCacheProperties settings,
            VersionAccessGrantCatalog grants,
            ObjectProvider<TenantTesterMembershipAccess> memberships,
            ObjectProvider<ApplicationAccess> applications,
            MeterRegistry meters) {
        this.redis = redis;
        this.settings = settings;
        this.grants = grants;
        this.memberships = memberships;
        this.applications = applications;
        this.meters = meters;
    }

    /**
     * One grant as the cache carries it: the shape's decision inputs
     * — the granting tenant, the application, the type, the target,
     * and the expiry — plus the row id, which never influences a
     * decision but attributes the effective-access view's
     * {@code sourceGrantIds} (the one cached consumer that names its
     * contributors; actor and timestamps stay out). The tenant rides
     * the tuple because the shared-application path matches each
     * grant's own tenant at evaluation time; the expiry is epoch
     * MICROS (null = never-expires) — PostgreSQL timestamptz carries
     * microsecond precision and the effective view exposes it, so the
     * cached form must round-trip without losing it (the #31 CI
     * lesson; never millis).
     */
    public record CachedGrant(
            UUID grantId,
            UUID tenantId,
            UUID applicationId,
            String accessType,
            UUID versionId,
            String tag,
            Integer latestN,
            Long expiresAt) {

        public static CachedGrant of(VersionAccessGrantEntity grant) {
            return new CachedGrant(
                    grant.getId(),
                    grant.getTenantId(),
                    grant.getApplicationId(),
                    grant.getAccessType().name(),
                    grant.getVersionId(),
                    grant.getTag(),
                    grant.getLatestN(),
                    grant.getExpiresAt() == null ? null : epochMicros(grant.getExpiresAt()));
        }

        /** Epoch-microseconds of the instant, microsecond precision preserved. */
        public static long epochMicros(Instant instant) {
            return instant.getEpochSecond() * 1_000_000L + instant.get(ChronoField.MICRO_OF_SECOND);
        }

        /** The instant of an epoch-microseconds value. */
        public static Instant instantOf(long epochMicros) {
            return Instant.ofEpochSecond(
                    Math.floorDiv(epochMicros, 1_000_000L), Math.floorMod(epochMicros, 1_000_000L) * 1_000L);
        }
    }

    // -------------------------------------------------------------------------
    // Lookups (cache-aside; every miss/failure answers the live loader)
    // -------------------------------------------------------------------------

    /**
     * The tester's grants through the tenant — all applications, all
     * expiries (expiry filters at evaluation time, the live probe's
     * contract), ACTIVE rows only since S7-04 (issue #33): a revoked
     * grant is history, never a decision input — the loader filters,
     * so the cached set and the live probe can never disagree and a
     * revocation's write-through drop (not a payload edit) is what
     * removes the row's effect. Loader: one
     * {@code idx_version_access_grant_subject} prefix descent over the
     * grant catalog.
     */
    public List<CachedGrant> grantsOf(UUID tenantId, UUID testerId) {
        return lookup(
                "grants",
                grantsKey(tenantId, testerId),
                GRANTS,
                () -> grants.findByTenantIdAndTesterId(tenantId, testerId).stream()
                        .filter(grant -> grant.getStatus() == VersionAccessGrantEntity.Status.ACTIVE)
                        .map(CachedGrant::of)
                        .toList());
    }

    /**
     * The tester's linked tenants (retained membership rows, any
     * status) — the shared-application probe's candidate set.
     */
    public List<UUID> tenantsOfTester(UUID testerId) {
        return lookup(
                "tenants-of-tester",
                tenantsKey(testerId),
                TENANTS,
                () -> memberships.getObject().tenantIdsOfTester(testerId));
    }

    /**
     * The two-switches effectiveness flag (person record ACTIVE and
     * membership ACTIVE) — the TENANT layer's tester-persona probe.
     */
    public boolean effectiveMembership(UUID tenantId, UUID testerId) {
        return lookup(
                "membership",
                membershipKey(tenantId, testerId),
                FLAGS,
                () -> memberships.getObject().isEffectivelyActive(tenantId, testerId));
    }

    /**
     * The S3-03 visibility flag — whether the tenant can see the
     * application (tenant-owned, or organization-shared with an
     * ACTIVE authorization mapping).
     */
    public boolean visibleToTenant(UUID applicationId, UUID tenantId) {
        return lookup(
                "visibility",
                visibilityKey(applicationId, tenantId),
                FLAGS,
                () -> applications.getObject().visibleToTenant(applicationId, tenantId));
    }

    // -------------------------------------------------------------------------
    // Write-through invalidation (AuthorizationDecisionInvalidation)
    // -------------------------------------------------------------------------

    @Override
    public void invalidateGrantSet(UUID tenantId, UUID testerId) {
        drop("grants", List.of(grantsKey(tenantId, testerId)));
    }

    @Override
    public void invalidateMembership(UUID tenantId, UUID testerId) {
        drop("membership", List.of(membershipKey(tenantId, testerId)));
    }

    @Override
    public void invalidateMembershipsOfTester(UUID testerId, Collection<UUID> tenantIds) {
        drop(
                "membership",
                tenantIds.stream()
                        .map(tenantId -> membershipKey(tenantId, testerId))
                        .toList());
    }

    @Override
    public void invalidateApplicationVisibility(UUID applicationId, UUID tenantId) {
        drop("visibility", List.of(visibilityKey(applicationId, tenantId)));
    }

    @Override
    public void invalidateTesterTenantSet(UUID testerId) {
        drop("tenants-of-tester", List.of(tenantsKey(testerId)));
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    /**
     * The cache-aside skeleton every lookup shares: disabled answers
     * the loader (bypass); an OPEN breaker degrades without touching
     * Redis (the fail-fast leg — a dead Redis must not tax every
     * lookup with a connect/timeout wait); a Redis hit parses (a
     * parse failure is corruption — degrade and delete best-effort);
     * a miss answers the loader and refills with the configured TTL;
     * any Redis failure answers the loader (degraded) and feeds the
     * breaker. The loader's own result is ALWAYS the fallback — the
     * cache can only change latency.
     */
    private <T> T lookup(String family, String key, FamilyCodec<T> codec, Supplier<T> loader) {
        if (!settings.enabled()) {
            count(family, "bypass");
            return loader.get();
        }
        if (breakerOpen()) {
            count(family, "degraded");
            return loader.get();
        }
        String json;
        try {
            json = redis.opsForValue().get(key);
            recordRedisSuccess();
        } catch (RuntimeException redisFailure) {
            count(family, "degraded");
            recordRedisFailure(redisFailure);
            return loader.get();
        }
        if (json != null) {
            try {
                T value = codec.parse(json);
                count(family, "hit");
                return value;
            } catch (RuntimeException corrupt) {
                // A body we cannot read is corruption by definition
                // (or a future schema): degrade and clear the entry so
                // the TTL question never arises for it again.
                count(family, "degraded");
                warnDegraded(corrupt);
                deleteQuietly(key);
                return loader.get();
            }
        }
        count(family, "miss");
        T value = loader.get();
        try {
            redis.opsForValue().set(key, codec.serialize(value), settings.ttl());
            recordRedisSuccess();
        } catch (RuntimeException redisFailure) {
            // The refill is best-effort: the value already answered,
            // and a failed refill only costs the next reader a miss.
            count(family, "degraded");
            recordRedisFailure(redisFailure);
        }
        return value;
    }

    /** The per-family wire format: parse a cached body, serialize a loaded value. */
    private interface FamilyCodec<T> {
        T parse(String json);

        String serialize(T value);
    }

    /**
     * The grants family's wire format: versioned JSON of shape tuples.
     * The body's {@code grants} field must be present and an array —
     * a versioned body MISSING its array is corruption, not an empty
     * hit (an empty hit can only come from a body that serialized an
     * empty set; anything else must degrade to the live loader, never
     * answer "no grants" and silently deny-by-staleness the other
     * way). The serializer is deliberate JSON concatenation: every
     * string it emits is a UUID, an enum name, or a V13 tag
     * ({@code [A-Z0-9][A-Z0-9_-]{0,49}}) — none can contain a
     * character JSON would escape — pinned by the round-trip test;
     * widening any of those vocabularies moves to a structured
     * writer first.
     */
    private static final FamilyCodec<List<CachedGrant>> GRANTS = new FamilyCodec<>() {
        @Override
        public List<CachedGrant> parse(String json) {
            JsonNode root = PAYLOADS.readTree(json);
            requireVersion(root);
            JsonNode grantsArray = root.path("grants");
            if (!grantsArray.isArray()) {
                throw new IllegalArgumentException("corrupt grants payload: missing grants array");
            }
            List<CachedGrant> parsed = new ArrayList<>();
            for (JsonNode node : grantsArray) {
                parsed.add(new CachedGrant(
                        uuidOf(node, "grantId"),
                        uuidOf(node, "tenantId"),
                        uuidOf(node, "applicationId"),
                        node.path("accessType").asString(),
                        uuidOf(node, "versionId"),
                        node.hasNonNull("tag") ? node.path("tag").asString() : null,
                        node.hasNonNull("latestN") ? node.path("latestN").asInt() : null,
                        node.hasNonNull("expiresAt") ? node.path("expiresAt").asLong() : null));
            }
            return List.copyOf(parsed);
        }

        @Override
        public String serialize(List<CachedGrant> value) {
            StringBuilder json =
                    new StringBuilder("{\"v\":").append(PAYLOAD_VERSION).append(",\"grants\":[");
            for (int i = 0; i < value.size(); i++) {
                CachedGrant grant = value.get(i);
                if (i > 0) {
                    json.append(',');
                }
                json.append("{\"grantId\":")
                        .append(uuidJson(grant.grantId()))
                        .append(",\"tenantId\":\"")
                        .append(grant.tenantId())
                        .append("\",\"applicationId\":\"")
                        .append(grant.applicationId())
                        .append("\",\"accessType\":\"")
                        .append(grant.accessType())
                        .append("\",\"versionId\":")
                        .append(uuidJson(grant.versionId()))
                        .append(",\"tag\":")
                        .append(stringJson(grant.tag()))
                        .append(",\"latestN\":")
                        .append(grant.latestN() == null ? "null" : grant.latestN())
                        .append(",\"expiresAt\":")
                        .append(grant.expiresAt() == null ? "null" : grant.expiresAt())
                        .append('}');
            }
            return json.append("]}").toString();
        }
    };

    /**
     * The linked-tenants family's wire format: versioned JSON of
     * tenant ids. The {@code tenants} array's presence is checked as
     * strictly as the grants family's — a versioned body missing its
     * array is corruption and degrades, never an empty hit.
     */
    private static final FamilyCodec<List<UUID>> TENANTS = new FamilyCodec<>() {
        @Override
        public List<UUID> parse(String json) {
            JsonNode root = PAYLOADS.readTree(json);
            requireVersion(root);
            JsonNode tenantsArray = root.path("tenants");
            if (!tenantsArray.isArray()) {
                throw new IllegalArgumentException("corrupt tenants payload: missing tenants array");
            }
            List<UUID> parsed = new ArrayList<>();
            for (JsonNode node : tenantsArray) {
                parsed.add(UUID.fromString(node.asString()));
            }
            return List.copyOf(parsed);
        }

        @Override
        public String serialize(List<UUID> value) {
            StringBuilder json =
                    new StringBuilder("{\"v\":").append(PAYLOAD_VERSION).append(",\"tenants\":[");
            for (int i = 0; i < value.size(); i++) {
                if (i > 0) {
                    json.append(',');
                }
                json.append('\"').append(value.get(i)).append('\"');
            }
            return json.append("]}").toString();
        }
    };

    /** The boolean families' wire format: {@code 1}/{@code 0} flags. */
    private static final FamilyCodec<Boolean> FLAGS = new FamilyCodec<>() {
        @Override
        public Boolean parse(String json) {
            if (!json.equals("1") && !json.equals("0")) {
                throw new IllegalArgumentException("corrupt flag payload");
            }
            return json.equals("1");
        }

        @Override
        public String serialize(Boolean value) {
            return value ? "1" : "0";
        }
    };

    /**
     * Drops keys write-through: at before-commit inside a transaction
     * (AC2 — the drop lands before the new state is visible), or
     * immediately when the caller holds no transaction (the
     * non-transactional mutators; their inner write attempt has
     * already committed, so the drop is post-commit by construction).
     * A failed drop counts degraded and never propagates — the TTL is
     * the backstop and the mutation must not fail on the cache.
     */
    private void drop(String family, List<String> keys) {
        Runnable deletion = () -> {
            if (breakerOpen()) {
                // An open breaker swallows the drop: the TTL is the
                // backstop and the mutation must not wait on a dead
                // Redis it is already known to be dead.
                count(family, "degraded");
                return;
            }
            try {
                if (!keys.isEmpty()) {
                    redis.delete(keys);
                }
                recordRedisSuccess();
                count(family, "invalidate");
            } catch (RuntimeException redisFailure) {
                count(family, "degraded");
                recordRedisFailure(redisFailure);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    deletion.run();
                }
            });
        } else {
            deletion.run();
        }
    }

    private static void requireVersion(JsonNode root) {
        if (root.path("v").asInt(-1) != PAYLOAD_VERSION) {
            throw new IllegalArgumentException("unknown payload version");
        }
    }

    private static String stringJson(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private static String uuidJson(UUID value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private static UUID uuidOf(JsonNode node, String field) {
        return node.hasNonNull(field) ? UUID.fromString(node.path(field).asString()) : null;
    }

    private void deleteQuietly(String key) {
        try {
            redis.delete(key);
        } catch (RuntimeException ignored) {
            // Best-effort: the TTL covers what this drop missed.
        }
    }

    /** Whether the fail-fast breaker is open: lookups skip Redis and degrade. */
    private boolean breakerOpen() {
        return System.nanoTime() < breakerOpenUntil.get();
    }

    /** A Redis failure: counts toward the breaker and opens it past the threshold. */
    private void recordRedisFailure(Throwable cause) {
        if (consecutiveFailures.incrementAndGet() >= BREAKER_THRESHOLD) {
            breakerOpenUntil.accumulateAndGet(System.nanoTime() + BREAKER_COOLDOWN_NANOS, Math::max);
        }
        warnDegraded(cause);
    }

    /** A Redis success closes the breaker (the half-open probe answered). */
    private void recordRedisSuccess() {
        consecutiveFailures.set(0);
        breakerOpenUntil.set(0);
    }

    private void count(String family, String outcome) {
        Counter.builder(MetricNames.AUTHORIZATION_DECISION_CACHE)
                .tag("family", family)
                .tag("outcome", outcome)
                .register(meters)
                .increment();
    }

    private void warnDegraded(Throwable cause) {
        long now = System.nanoTime();
        long last = lastDegradedWarn.get();
        if (now - last >= WARN_THROTTLE_NANOS && lastDegradedWarn.compareAndSet(last, now)) {
            log.warn(
                    "authorization decision-input cache degraded (cause: {}); "
                            + "decisions fall through to direct evaluation (fail-closed)",
                    cause.toString());
        }
    }

    private static String grantsKey(UUID tenantId, UUID testerId) {
        return "shipperdog:auth:v" + PAYLOAD_VERSION + ":grants:" + tenantId + ":" + testerId;
    }

    private static String membershipKey(UUID tenantId, UUID testerId) {
        return "shipperdog:auth:v" + PAYLOAD_VERSION + ":membership:" + tenantId + ":" + testerId;
    }

    private static String visibilityKey(UUID applicationId, UUID tenantId) {
        return "shipperdog:auth:v" + PAYLOAD_VERSION + ":visibility:" + applicationId + ":" + tenantId;
    }

    private static String tenantsKey(UUID testerId) {
        return "shipperdog:auth:v" + PAYLOAD_VERSION + ":tenants-of-tester:" + testerId;
    }
}
