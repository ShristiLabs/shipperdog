package com.shipperdog.api.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * The single list-response shape of the platform (issue #8): every
 * collection endpoint returns {@code {"items": [...], "next_cursor": ...}}
 * with {@code next_cursor} present exactly when another page follows and
 * omitted at the end. Bare-array responses are forbidden (an ArchUnit
 * test enforces it), so consumers page one way everywhere.
 *
 * @param <T> the item view type
 */
public record PageResponse<T>(
        @JsonProperty("items") List<T> items,

        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("next_cursor")
        String nextCursor) {

    /** A page of {@code items}; {@code hasMore} decides next_cursor. */
    public static <T> PageResponse<T> of(List<T> items, boolean hasMore, long nextOffset) {
        return new PageResponse<>(items, hasMore ? CursorCodec.encode(nextOffset) : null);
    }
}
