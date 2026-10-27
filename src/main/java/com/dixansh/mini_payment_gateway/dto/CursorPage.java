package com.dixansh.mini_payment_gateway.dto;

import java.util.List;

/**
 * A single page of a keyset-paginated result. {@code nextCursor} is null when there are no more
 * rows; otherwise the client passes it back as {@code ?cursor=} to fetch the next page.
 */
public record CursorPage<T>(
        List<T> items,
        String nextCursor
) {
}
