package com.dixansh.mini_payment_gateway.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/** Dev-only cash-in. Funds the account from the system settlement account (see DECISIONS.md). */
public record TopupRequest(
        @NotNull @Positive @Digits(integer = 16, fraction = 2) BigDecimal amount
) {
}
