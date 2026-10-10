package com.shipperdog.api.integration.internal.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The thin Google Play notification reader's unit legs (issue #54,
 * S12-04): the four oneof variants of Google's published
 * {@code notifications.proto} map their identifying facts; unknown
 * fields (a proto revision adding one) are skipped; and every
 * truncated or not-a-notification byte sequence answers null -- the
 * delivery degrades to an acknowledged IGNORED, never a half-read
 * identifier.
 */
class GooglePlayNotificationReaderTest {

    @Test
    void subscriptionNotificationMapsItsFacts() {
        byte[] proto = wrapped(4, taggedVarint(1, 2), string(2, "purchase-token-1"), string(3, "sub-tier-gold"));
        GooglePlayNotificationReader.Notification read = GooglePlayNotificationReader.read(proto);
        assertThat(read).isNotNull();
        assertThat(read.kind()).isEqualTo("subscription");
        assertThat(read.type()).isEqualTo(2);
        assertThat(read.purchaseToken()).isEqualTo("purchase-token-1");
        assertThat(read.productRef()).isEqualTo("sub-tier-gold");
    }

    @Test
    void oneTimeProductNotificationMapsItsFacts() {
        byte[] proto = wrapped(2, taggedVarint(2, 1), string(3, "purchase-token-2"), string(4, "sku-gems-100"));
        GooglePlayNotificationReader.Notification read = GooglePlayNotificationReader.read(proto);
        assertThat(read).isNotNull();
        assertThat(read.kind()).isEqualTo("one-time-product");
        assertThat(read.type()).isEqualTo(1);
        assertThat(read.purchaseToken()).isEqualTo("purchase-token-2");
        assertThat(read.productRef()).isEqualTo("sku-gems-100");
    }

    @Test
    void voidedPurchaseAndTestNotificationsMapTheirFacts() {
        byte[] voided = wrapped(3, string(1, "purchase-token-3"));
        GooglePlayNotificationReader.Notification voidedRead = GooglePlayNotificationReader.read(voided);
        assertThat(voidedRead.kind()).isEqualTo("voided-purchase");
        assertThat(voidedRead.type()).isNull();
        assertThat(voidedRead.purchaseToken()).isEqualTo("purchase-token-3");

        // A test notification carries only the version field today.
        byte[] test = wrapped(1, string(1, "1.0"));
        GooglePlayNotificationReader.Notification testRead = GooglePlayNotificationReader.read(test);
        assertThat(testRead.kind()).isEqualTo("test");
        assertThat(testRead.type()).isNull();
        assertThat(testRead.purchaseToken()).isNull();
    }

    @Test
    void unknownFieldsAreSkippedNotFatal() {
        // A revision adds field 15 (length-delimited) inside the
        // variant and field 9 (varint) in the wrapper: both are
        // skipped, and the known facts still map (the purchase token
        // is the subscription variant's field 2).
        byte[] proto = wrapped(
                4, string(15, "a-field-the-reader-does-not-know"), taggedVarint(1, 7), string(2, "purchase-token-4"));
        GooglePlayNotificationReader.Notification read = GooglePlayNotificationReader.read(proto);
        assertThat(read).isNotNull();
        assertThat(read.type()).isEqualTo(7);
        assertThat(read.purchaseToken()).isEqualTo("purchase-token-4");
    }

    @Test
    void everyNotANotificationShapeAnswersNull() {
        // Arrays.asList: the absent-payload leg is a deliberate null entry.
        for (byte[] candidate : java.util.Arrays.asList(
                new byte[0],
                null,
                // A wrapper with NO oneof variant set.
                taggedVarint(9, 1),
                // A length-delimited field whose declared length runs
                // past the bytes present (truncated).
                new byte[] {0x22, 0x10, 0x01})) {
            assertThat(GooglePlayNotificationReader.read(candidate)).isNull();
        }
    }

    // -----------------------------------------------------------------------
    // Wire-format helpers (the test writes what the reader reads)
    // -----------------------------------------------------------------------

    /** The oneof wrapper: field {@code variant} carrying the variant's own fields. */
    private static byte[] wrapped(int variant, byte[]... fields) {
        ByteArrayOutputStream value = new ByteArrayOutputStream();
        for (byte[] field : fields) {
            value.writeBytes(field);
        }
        return lengthDelimited(variant, value.toByteArray());
    }

    /** One string (length-delimited, wire type 2) field. */
    private static byte[] string(int field, String value) {
        return lengthDelimited(field, value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] lengthDelimited(int field, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        varintBytes(out, ((long) field << 3) | 2);
        varintBytes(out, value.length);
        out.writeBytes(value);
        return out.toByteArray();
    }

    /** One varint (wire type 0) field: the tag and the value together. */
    private static byte[] taggedVarint(int field, long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        varintBytes(out, ((long) field << 3) | 0);
        varintBytes(out, value);
        return out.toByteArray();
    }

    private static void varintBytes(ByteArrayOutputStream out, long value) {
        long remaining = value;
        while (true) {
            if ((remaining & ~0x7fL) == 0) {
                out.write((int) remaining);
                return;
            }
            out.write((int) ((remaining & 0x7fL) | 0x80));
            remaining >>>= 7;
        }
    }
}
