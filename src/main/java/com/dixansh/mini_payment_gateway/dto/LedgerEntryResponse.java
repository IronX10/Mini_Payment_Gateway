package com.dixansh.mini_payment_gateway.dto;

import com.dixansh.mini_payment_gateway.domain.LedgerEntry;

import java.math.BigDecimal;
import java.time.Instant;

public record LedgerEntryResponse(
        Long id,
        String transactionId,
        Long accountId,
        String direction,
        BigDecimal amount,
        Instant createdAt
) {
    public static LedgerEntryResponse from(LedgerEntry e) {
        return new LedgerEntryResponse(
                e.getId(),
                e.getTransactionId().toString(),
                e.getAccountId(),
                e.getDirection().name(),
                e.getAmount(),
                e.getCreatedAt()
        );
    }
}
