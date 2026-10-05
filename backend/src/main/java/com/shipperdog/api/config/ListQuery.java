package com.shipperdog.api.config;

/**
 * Parses the platform's list parameters (issue #8): {@code limit} (default
 * 50, hard-capped at 100 — the no-unbounded-queries rule) and the optional
 * opaque {@code cursor}. Every collection endpoint funnels through this so
 * the limits and error codes are identical everywhere.
 */
public record ListQuery(int limit, long offset) {

    /** Hard cap; requests above it are rejected, never silently clamped. */
    public static final int MAX_LIMIT = 100;

    public static final int DEFAULT_LIMIT = 50;

    public static ListQuery of(String limit, String cursor) {
        int parsedLimit = DEFAULT_LIMIT;
        if (limit != null && !limit.isBlank()) {
            try {
                parsedLimit = Integer.parseInt(limit.trim());
            } catch (NumberFormatException e) {
                throw new InvalidLimitException();
            }
            if (parsedLimit < 1 || parsedLimit > MAX_LIMIT) {
                throw new InvalidLimitException();
            }
        }
        long offset = 0;
        if (cursor != null && !cursor.isBlank()) {
            offset = CursorCodec.decode(cursor);
        }
        return new ListQuery(parsedLimit, offset);
    }

    /** Raised when {@code limit} is not an integer in [1, 100]. */
    public static final class InvalidLimitException extends IllegalArgumentException {

        public InvalidLimitException() {
            super("limit must be an integer between 1 and " + MAX_LIMIT);
        }
    }
}
