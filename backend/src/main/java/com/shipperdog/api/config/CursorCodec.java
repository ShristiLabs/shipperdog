package com.shipperdog.api.config;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The platform's cursor codec (issue #8): list endpoints page with opaque
 * {@code cursor} tokens so the wire contract never exposes offset
 * arithmetic and stays forward-compatible with a keyset upgrade (the
 * token's payload is an internal detail callers must not parse).
 *
 * <p>Encoding: unpadded base64url of a compact JSON object. Today the
 * payload is {@code {"o":<offset>}}; when a list grows to where offset
 * paging degrades, the payload can become a keyset tuple without any
 * contract change.
 */
public final class CursorCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CursorCodec() {}

    /** Encodes an offset cursor; {@code offset} must be non-negative. */
    public static String encode(long offset) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("o", offset);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(MAPPER.writeValueAsBytes(payload));
    }

    /**
     * Decodes a cursor to its offset.
     *
     * @throws InvalidCursorException when the token is not one this codec
     *         produced (the global handler renders INVALID_CURSOR)
     */
    public static long decode(String cursor) {
        byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(cursor);
        } catch (IllegalArgumentException e) {
            throw new InvalidCursorException();
        }
        try {
            JsonNode payload = MAPPER.readTree(new String(bytes, StandardCharsets.UTF_8));
            long offset = payload.path("o").asLong(Long.MIN_VALUE);
            if (offset < 0) {
                throw new InvalidCursorException();
            }
            return offset;
        } catch (RuntimeException e) {
            throw new InvalidCursorException();
        }
    }

    /** Raised for any token this codec did not produce. */
    public static final class InvalidCursorException extends IllegalArgumentException {

        private InvalidCursorException() {
            super("cursor is invalid");
        }
    }
}
