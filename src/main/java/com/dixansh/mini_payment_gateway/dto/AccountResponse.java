package com.dixansh.mini_payment_gateway.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Account view. {@code balance} is the cached value; {@code ledgerBalance} is recomputed from the
 * ledger on read so a client (or reviewer) can see the two agree.
 */
public record AccountResponse(
        Long id,
        Long userId,
        String currency,
        BigDecimal balance,
        BigDecimal ledgerBalance,
        long version,
        Instant createdAt
) {
}
