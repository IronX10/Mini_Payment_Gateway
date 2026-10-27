package com.dixansh.mini_payment_gateway.util;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Opaque cursor encoding for keyset pagination. Clients treat the cursor as a black box; we
 * base64url-encode the sort key so it survives query strings and never leaks internal shape.
 */
public final class CursorCodec {

    private CursorCodec() {
    }

    // ----- ledger: cursor is a single BIGSERIAL id -----

    public static String encodeLedger(Long id) {
        return base64(String.valueOf(id));
    }

    public static Long decodeLedger(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        return Long.parseLong(unbase64(cursor));
    }

    // ----- payments: cursor is a (created_at, id) tuple -----

    public static String encodePayment(Instant ts, UUID id) {
        return base64(ts.toString() + "|" + id);
    }

    /** @return a 2-element array [Instant, UUID], or null when the cursor is absent. */
    public static Object[] decodePayment(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        String raw = unbase64(cursor);
        int sep = raw.lastIndexOf('|');
        if (sep < 0) {
            throw new IllegalArgumentException("malformed cursor");
        }
        Instant ts = Instant.parse(raw.substring(0, sep));
        UUID id = UUID.fromString(raw.substring(sep + 1));
        return new Object[]{ts, id};
    }

    private static String base64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String unbase64(String s) {
        return new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8);
    }
}
