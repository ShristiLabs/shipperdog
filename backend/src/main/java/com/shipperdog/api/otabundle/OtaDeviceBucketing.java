package com.shipperdog.api.otabundle;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The deterministic device bucketing of ADR-0010 D6 (S16-06, issue
 * #168): which devices a rollout stage serves. Devices are NOT
 * enumerable the way the V48 tenant cohorts are, so no cohort table
 * exists -- a device's membership in the serving stage is computed
 * on the fly, every time, from exactly two facts:
 *
 * <pre>{@code
 * bucket(deviceId, planSalt) = unsignedBigEndian64(sha256(utf8(planSalt + ":" + deviceId))) mod 10000
 * served                                    = bucket < stage.basisPoints
 * }</pre>
 *
 * <p><strong>The contract, exactly</strong> (unit-test-pinned; the
 * pins are the spec):
 * <ul>
 *   <li><em>Deterministic</em>: the same {@code (deviceId, planSalt)}
 *       pair always answers the same bucket -- across calls, across
 *       restarts, across JVMs and versions -- because the function is
 *       pure arithmetic over the SHA-256 digest of a canonical byte
 *       encoding, with no seed, no clock, and no iteration order in
 *       it anywhere.</li>
 *   <li><em>Stable per device</em>: a device that falls inside a
 *       stage never falls out by a later re-computation; widening is
 *       cumulative (stage basis points are cumulative thresholds), so
 *       an advanced stage keeps every device the previous stage
 *       served.</li>
 *   <li><em>Salt-independent plans</em>: the plan's salt mixes into
 *       the digest, so two plans' bucket orders are independent -- the
 *       same device is not systematically first or last everywhere.</li>
 *   <li><em>Uniform</em>: SHA-256's avalanche keeps the buckets
 *       uniform over the device-id space (the sanity test pins the
 *       distribution's tolerance bands).</li>
 * </ul>
 *
 * <p>Public and static by design: the S16-07 device-check resolution
 * composes this with the plan's standing (fetched through {@code
 * OtaRolloutAccess}) to answer "is this device served by the current
 * stage" -- the check route computes, it never stores. The salt is
 * public-by-design too (V56's own note: device ids are not enumerable,
 * so the salt alone reveals nothing).
 */
public final class OtaDeviceBucketing {

    /** The basis-point space: 10000 buckets of one hundredth of a percent each. */
    public static final int BUCKET_SPACE = 10000;

    private OtaDeviceBucketing() {}

    /**
     * The device's bucket in the plan's hash space: 0..9999, computed
     * exactly as D6 specifies. Pure and total.
     *
     * @param deviceId the device's own identifier, exactly as the check
     *                 route received it (the raw string; never trimmed
     *                 or normalized here -- normalization would move
     *                 devices between buckets)
     * @param planSalt the plan's salt (V56 {@code plan_salt})
     */
    public static int bucketOf(String deviceId, String planSalt) {
        byte[] digest = sha256((planSalt + ":" + deviceId).getBytes(StandardCharsets.UTF_8));
        // First 8 digest bytes as an unsigned big-endian 64-bit number
        // (the sign bit masked, never a shrinking conversion), then the
        // basis-point space by the one modulus -- 2^64 and 10000 share
        // no factor that could bias any measurable band of the space.
        long unsigned = 0L;
        for (int i = 0; i < 8; i++) {
            unsigned = (unsigned << 8) | (digest[i] & 0xffL);
        }
        return (int) Long.remainderUnsigned(unsigned, BUCKET_SPACE);
    }

    /**
     * Whether the serving stage admits the device: the bucket falls
     * below the stage's CUMULATIVE basis-point threshold. A HALTED or
     * PAUSED plan never asks -- standing is the caller's first gate
     * (the S16-07 composition; halt yields no-update before any of
     * this arithmetic runs).
     */
    public static boolean servesDevice(String deviceId, String planSalt, int basisPoints) {
        return bucketOf(deviceId, planSalt) < basisPoints;
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            // Every JDK ships SHA-256 (the FIPS floor); its absence is
            // a broken runtime, not a condition to answer for.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
