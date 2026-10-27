package com.dixansh.mini_payment_gateway.dto;

import com.dixansh.mini_payment_gateway.domain.Transaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Admin view of a held payment for the review queue ({@code GET /internal/held}). Unlike the
 * payer-facing {@link PaymentResponse}, this deliberately exposes the risk score and the rule codes
 * that fired, so a reviewer can see <em>why</em> a payment was held and make a release decision.
 */
public record HeldPaymentResponse(
        String id,
        Long source,
        Long dest,
        BigDecimal amount,
        String currency,
        Integer riskScore,
        List<String> riskReasons,
        Instant createdAt
) {
    public static HeldPaymentResponse from(Transaction t) {
        String reasons = t.getRiskReasons();
        List<String> reasonList = (reasons == null || reasons.isBlank())
                ? List.of()
                : List.of(reasons.split(","));
        return new HeldPaymentResponse(
                t.getId().toString(),
                t.getSourceAccount(),
                t.getDestAccount(),
                t.getAmount(),
                t.getCurrency(),
                t.getRiskScore(),
                reasonList,
                t.getCreatedAt()
        );
    }
}
