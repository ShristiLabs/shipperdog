package com.shipperdog.api.otabundle;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The deterministic device bucketing's contract, pinned (S16-06,
 * issue #168, ADR-0010 D6): these tests ARE the spec. The exact-value
 * pins freeze the algorithm's every choice -- the canonical encoding
 * ({@code planSalt + ":" + deviceId}, UTF-8), the digest (SHA-256),
 * the fold (first 8 bytes, unsigned big-endian), and the modulus
 * (10000) -- so any change to any of them fails here before it can
 * silently move devices between buckets in production. The
 * distribution tests pin the uniformity D6's widening math depends
 * on: a stage of B basis points serves B hundredths of the device
 * space, within tolerance, and the salt reorder test pins that two
 * plans' bucket orders are independent.
 */
class OtaDeviceBucketingTest {

    private static final String SALT_A = "saltA";
    private static final String SALT_B = "saltB";

    // -----------------------------------------------------------------------
    // AC1: deterministic -- the exact pins (the algorithm IS the spec)
    // -----------------------------------------------------------------------

    @Test
    void theExactValuesArePinned() {
        assertThat(OtaDeviceBucketing.bucketOf("device-0001", SALT_A)).isEqualTo(6672);
        assertThat(OtaDeviceBucketing.bucketOf("device-0002", SALT_A)).isEqualTo(2775);
        // The empty device id is a legal input to the function (the
        // route's own shape validation refuses it earlier); its bucket
        // is still exact, never an exception.
        assertThat(OtaDeviceBucketing.bucketOf("", SALT_A)).isEqualTo(6905);
        assertThat(OtaDeviceBucketing.bucketOf("device-0001", SALT_B)).isEqualTo(7735);
        // Non-ASCII ids hash by their UTF-8 bytes, exactly.
        assertThat(OtaDeviceBucketing.bucketOf("устройство-1", SALT_A)).isEqualTo(8672);
        assertThat(OtaDeviceBucketing.bucketOf(
                        "d8f2c7a9-1e6b-4a3c-9d5e-7b8a1c2d3e4f", "0123456789abcdef0123456789abcdef"))
                .isEqualTo(2008);
    }

    @Test
    void theSamePairAlwaysAnswersTheSameBucket() {
        // "Across restarts" is the pure-function property: no clock, no
        // seed, no iteration order -- repeated calls in one process and
        // the same calls in any other process agree, byte for byte.
        for (int i = 0; i < 1_000; i++) {
            String deviceId = "device-" + i;
            int first = OtaDeviceBucketing.bucketOf(deviceId, SALT_A);
            assertThat(OtaDeviceBucketing.bucketOf(deviceId, SALT_A)).isEqualTo(first);
            assertThat(OtaDeviceBucketing.bucketOf(deviceId, SALT_B))
                    .isEqualTo(OtaDeviceBucketing.bucketOf(deviceId, SALT_B));
        }
    }

    @Test
    void everyBucketStaysInsideTheBasisPointSpace() {
        for (int i = 0; i < 20_000; i++) {
            int bucket = OtaDeviceBucketing.bucketOf("device-" + i, SALT_A);
            assertThat(bucket).isBetween(0, OtaDeviceBucketing.BUCKET_SPACE - 1);
        }
    }

    // -----------------------------------------------------------------------
    // AC1: the cumulative-widening stability
    // -----------------------------------------------------------------------

    @Test
    void anAdvancedStageNeverDropsAServedDevice() {
        // Cumulative thresholds: a device inside stage N is inside
        // every later stage -- the ladder only widens (D6's invariant;
        // a retreat is the plan-level halt, never a stage's).
        for (int i = 0; i < 5_000; i++) {
            String deviceId = "device-" + i;
            for (int basis = 100; basis <= 10_000; basis += 300) {
                if (OtaDeviceBucketing.servesDevice(deviceId, SALT_A, basis)) {
                    assertThat(OtaDeviceBucketing.servesDevice(deviceId, SALT_A, basis + 300))
                            .as("device %s served at %d must stay served when the stage widens", deviceId, basis)
                            .isTrue();
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // The uniformity sanity (D6's widening math)
    // -----------------------------------------------------------------------

    @Test
    void theBucketsAreUniformOverTheDeviceSpace() {
        int devices = 200_000;
        for (int threshold : new int[] {1_000, 2_500, 5_000}) {
            int hits = 0;
            for (int i = 0; i < devices; i++) {
                if (OtaDeviceBucketing.bucketOf("device-" + i, SALT_A) < threshold) {
                    hits++;
                }
            }
            double fraction = hits / (double) devices;
            double expected = threshold / (double) OtaDeviceBucketing.BUCKET_SPACE;
            // The tolerance band: half a percent of the space either
            // side of exact -- wide enough for honest sampling noise,
            // tight enough to fail any structured skew.
            assertThat(Math.abs(fraction - expected))
                    .as("fraction of devices under %d basis points", threshold)
                    .isLessThan(0.005);
        }
    }

    @Test
    void theSpaceIsFullySpannedAndTheSaltReordersIt() {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        int reordered = 0;
        int devices = 100_000;
        for (int i = 0; i < devices; i++) {
            String deviceId = "device-" + i;
            int bucketA = OtaDeviceBucketing.bucketOf(deviceId, SALT_A);
            min = Math.min(min, bucketA);
            max = Math.max(max, bucketA);
            if (bucketA != OtaDeviceBucketing.bucketOf(deviceId, SALT_B)) {
                reordered++;
            }
        }
        // Every band of the space is reachable (min near 0, max near
        // 9999) and two plans' salts order the space independently:
        // all but a rounding share of devices move between the salts'
        // orders, so one plan's early devices are not another's.
        assertThat(min).isLessThanOrEqualTo(10);
        assertThat(max).isGreaterThanOrEqualTo(OtaDeviceBucketing.BUCKET_SPACE - 11);
        assertThat(reordered / (double) devices).isGreaterThan(0.999);
    }
}
