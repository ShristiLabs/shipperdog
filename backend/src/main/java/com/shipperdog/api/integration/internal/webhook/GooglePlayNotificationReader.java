package com.shipperdog.api.integration.internal.webhook;

import java.io.ByteArrayInputStream;

/**
 * The thin reader for Google Play's Real-Time Developer Notification
 * protobuf (issue #54, S12-04, FR-022): Pub/Sub pushes carry the
 * notification base64-encoded in {@code message.data}, and the S12-04
 * mapping needs only the notification's kind and its identifying
 * fields -- E13's connectors own the semantics. This is a deliberate
 * minimal wire-format walker over the field numbers of Google's
 * published {@code notifications.proto} (no generated classes, no new
 * dependency): the oneof wrapper's four variants, and each variant's
 * own fields.
 *
 * <p>Field map (Google's notifications.proto):
 * <pre>
 * DeveloperNotification {
 *   oneof payload {
 *     TestNotification          test_notification            = 1;
 *     OneTimeProductNotification one_time_product_notification = 2;
 *     VoidedPurchaseNotification voided_purchase_notification  = 3;
 *     SubscriptionNotification  subscription_notification     = 4;
 *   }
 * }
 * TestNotification          { string version = 1; }
 * OneTimeProductNotification { string version = 1; int32 notification_type = 2;
 *                              string purchase_token = 3; string sku = 4; }
 * VoidedPurchaseNotification { string purchase_token = 1; string order_id = 2;
 *                              int32 product_type = 3; int32 refund_type = 4; }
 * SubscriptionNotification  { int32 notification_type = 1; string purchase_token = 2;
 *                              string subscription_id = 3; }
 * </pre>
 *
 * <p>Tolerance is the reader's contract: unknown fields (a proto
 * revision adding one) are skipped per the wire format's rules, and
 * any shape surprise answers null -- the receiver degrades that
 * delivery to an acknowledged IGNORED, never a failure Google would
 * pointlessly retry, and never a half-read identifier.
 */
public final class GooglePlayNotificationReader {

    /** The oneof wrapper's variant tags (notifications.proto field numbers). */
    private static final int FIELD_TEST = 1;

    private static final int FIELD_ONE_TIME = 2;
    private static final int FIELD_VOIDED = 3;
    private static final int FIELD_SUBSCRIPTION = 4;

    private GooglePlayNotificationReader() {}

    /**
     * Reads one notification, or null when the bytes are not a
     * well-shaped DeveloperNotification (including truncated input).
     */
    public static Notification read(byte[] proto) {
        if (proto == null || proto.length == 0) {
            return null;
        }
        try {
            Reader reader = new Reader(proto);
            while (reader.hasNext()) {
                long tag = reader.readTag();
                int field = (int) (tag >>> 3);
                if (field == FIELD_TEST && reader.isLengthDelimited(tag)) {
                    return test(reader.readBytes());
                }
                if (field == FIELD_ONE_TIME && reader.isLengthDelimited(tag)) {
                    return oneTime(reader.readBytes());
                }
                if (field == FIELD_VOIDED && reader.isLengthDelimited(tag)) {
                    return voided(reader.readBytes());
                }
                if (field == FIELD_SUBSCRIPTION && reader.isLengthDelimited(tag)) {
                    return subscription(reader.readBytes());
                }
                reader.skip(tag);
            }
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Notification test(byte[] message) {
        return new Notification("test", null, null, null);
    }

    private static Notification oneTime(byte[] message) {
        Reader reader = new Reader(message);
        Integer type = null;
        String purchaseToken = null;
        String sku = null;
        while (reader.hasNext()) {
            long tag = reader.readTag();
            int field = (int) (tag >>> 3);
            if (field == 2 && !reader.isLengthDelimited(tag)) {
                type = (int) reader.readVarint();
            } else if (field == 3 && reader.isLengthDelimited(tag)) {
                purchaseToken = reader.readString(reader.readBytes());
            } else if (field == 4 && reader.isLengthDelimited(tag)) {
                sku = reader.readString(reader.readBytes());
            } else {
                reader.skip(tag);
            }
        }
        return new Notification("one-time-product", type, purchaseToken, sku);
    }

    private static Notification voided(byte[] message) {
        Reader reader = new Reader(message);
        String purchaseToken = null;
        while (reader.hasNext()) {
            long tag = reader.readTag();
            int field = (int) (tag >>> 3);
            if (field == 1 && reader.isLengthDelimited(tag)) {
                purchaseToken = reader.readString(reader.readBytes());
            } else {
                reader.skip(tag);
            }
        }
        return new Notification("voided-purchase", null, purchaseToken, null);
    }

    private static Notification subscription(byte[] message) {
        Reader reader = new Reader(message);
        Integer type = null;
        String purchaseToken = null;
        String subscriptionId = null;
        while (reader.hasNext()) {
            long tag = reader.readTag();
            int field = (int) (tag >>> 3);
            if (field == 1 && !reader.isLengthDelimited(tag)) {
                type = (int) reader.readVarint();
            } else if (field == 2 && reader.isLengthDelimited(tag)) {
                purchaseToken = reader.readString(reader.readBytes());
            } else if (field == 3 && reader.isLengthDelimited(tag)) {
                subscriptionId = reader.readString(reader.readBytes());
            } else {
                reader.skip(tag);
            }
        }
        return new Notification("subscription", type, purchaseToken, subscriptionId);
    }

    /**
     * The thin mapped facts: the kind (stable vocabulary), the
     * variant's own type code when it has one, and its identifying
     * strings (purchase token; the subscription id or sku as the
     * product reference).
     */
    public record Notification(String kind, Integer type, String purchaseToken, String productRef) {}

    /** The minimal protobuf wire-format walker (varint and length-delimited legs). */
    private static final class Reader {
        private final ByteArrayInputStream in;
        private Long lookahead;
        private boolean exhausted;

        Reader(byte[] bytes) {
            this.in = new ByteArrayInputStream(bytes);
        }

        /** One-tag lookahead: the loop's {@code hasNext/readTag} pair reads each tag byte exactly once. */
        boolean hasNext() {
            if (lookahead != null) {
                return true;
            }
            if (exhausted || in.available() <= 0) {
                exhausted = true;
                return false;
            }
            try {
                lookahead = readVarintRaw();
                return true;
            } catch (RuntimeException e) {
                exhausted = true;
                return false;
            }
        }

        long readTag() {
            if (lookahead == null) {
                lookahead = readVarintRaw();
            }
            long tag = lookahead;
            lookahead = null;
            return tag;
        }

        static boolean isLengthDelimited(long tag) {
            return (tag & 0x7) == 2;
        }

        void skip(long tag) {
            switch ((int) (tag & 0x7)) {
                case 0 -> readVarintRaw();
                case 1 -> skipBytes(8);
                case 2 -> skipBytes((int) readVarintRaw());
                case 5 -> skipBytes(4);
                default -> throw new IllegalStateException("unsupported wire type");
            }
        }

        long readVarint() {
            return readVarintRaw();
        }

        byte[] readBytes() {
            int length = (int) readVarintRaw();
            if (length < 0 || length > in.available()) {
                throw new IllegalStateException("truncated length-delimited field");
            }
            byte[] bytes = new byte[length];
            in.read(bytes, 0, length);
            return bytes;
        }

        String readString(byte[] bytes) {
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }

        private long readVarintRaw() {
            long value = 0;
            int shift = 0;
            int b;
            do {
                if (in.available() <= 0) {
                    throw new IllegalStateException("truncated varint");
                }
                b = in.read();
                value |= (long) (b & 0x7f) << shift;
                shift += 7;
                if (shift > 63) {
                    throw new IllegalStateException("varint too long");
                }
            } while ((b & 0x80) != 0);
            return value;
        }

        private void skipBytes(int count) {
            if (count < 0 || in.available() < count) {
                throw new IllegalStateException("truncated field");
            }
            in.skip(count);
        }
    }
}
