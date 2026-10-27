package com.dixansh.mini_payment_gateway.dto;

import com.dixansh.mini_payment_gateway.domain.Transaction;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Payment view returned to clients and stored (as JSON) for idempotent replay.
 * {@code timeline} carries the created/updated stamps so GET /payments/{id} shows status + timeline.
 */
public record PaymentResponse(
        String id,
        String status,
        Long source,
        Long dest,
        BigDecimal amount,
        String currency,
        String failureReason,
        Instant createdAt,
        Instant updatedAt
) {
    public static PaymentResponse from(Transaction t) {
        return new PaymentResponse(
                t.getId().toString(),
                t.getStatus().name(),
                t.getSourceAccount(),
                t.getDestAccount(),
                t.getAmount(),
                t.getCurrency(),
                t.getFailureReason(),
                t.getCreatedAt(),
                t.getUpdatedAt()
        );
    }
}
