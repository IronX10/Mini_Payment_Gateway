package com.dixansh.mini_payment_gateway.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Output of GET /internal/reconcile. {@code ledgerSignedSum} must be zero, and {@code mismatches}
 * must be empty, for the system to be considered consistent.
 */
public record ReconcileResponse(
        BigDecimal ledgerSignedSum,
        boolean balanced,
        List<AccountMismatch> mismatches
) {
    public record AccountMismatch(Long accountId, BigDecimal cachedBalance, BigDecimal ledgerBalance) {
    }
}
