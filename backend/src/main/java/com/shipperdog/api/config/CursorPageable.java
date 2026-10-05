package com.shipperdog.api.config;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * A {@link Pageable} over absolute offsets (issue #8): the cursor
 * convention carries an opaque offset token, and offsets need not be
 * multiples of the current page size (a caller may page with a different
 * {@code limit} than the walk started with). Spring Data 4 dropped
 * OffsetRequest, so this is the minimal paged request the repositories
 * need.
 */
public final class CursorPageable implements Pageable {

    private final long offset;

    private final int limit;

    private final Sort sort;

    public CursorPageable(long offset, int limit) {
        this(offset, limit, null);
    }

    public CursorPageable(long offset, int limit, Sort sort) {
        if (offset < 0 || limit < 1) {
            throw new IllegalArgumentException("offset must be >= 0 and limit >= 1");
        }
        this.offset = offset;
        this.limit = limit;
        this.sort = sort == null ? Sort.unsorted() : sort;
    }

    @Override
    public int getPageNumber() {
        return (int) Math.floorDiv(offset, limit);
    }

    @Override
    public int getPageSize() {
        return limit;
    }

    @Override
    public long getOffset() {
        return offset;
    }

    @Override
    public Sort getSort() {
        return sort;
    }

    @Override
    public Pageable next() {
        return new CursorPageable(offset + limit, limit, sort);
    }

    @Override
    public Pageable previousOrFirst() {
        return hasPrevious() ? new CursorPageable(Math.max(offset - limit, 0), limit, sort) : first();
    }

    @Override
    public Pageable first() {
        return new CursorPageable(0, limit, sort);
    }

    @Override
    public Pageable withPage(int pageNumber) {
        return new CursorPageable((long) pageNumber * limit, limit, sort);
    }

    @Override
    public boolean hasPrevious() {
        return offset > 0;
    }
}
