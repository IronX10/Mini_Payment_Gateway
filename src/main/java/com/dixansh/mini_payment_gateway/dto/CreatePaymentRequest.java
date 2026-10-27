package com.dixansh.mini_payment_gateway.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Create a payment (a transfer). The {@code Idempotency-Key} travels in the header, not here, so
 * that the body is exactly the value the client hashes and we can detect key reuse with a
 * different body.
 */
public record CreatePaymentRequest(
        @NotNull Long source,
        @NotNull Long dest,
        @NotNull @Positive @Digits(integer = 16, fraction = 2) BigDecimal amount,
        @NotNull @Pattern(regexp = "[A-Z]{3}", message = "currency must be a 3-letter ISO code") String currency
) {
}
