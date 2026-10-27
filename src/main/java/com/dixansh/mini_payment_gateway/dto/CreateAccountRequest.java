package com.dixansh.mini_payment_gateway.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;

/**
 * Create an account. If {@code email} matches an existing user the account is attached to them,
 * otherwise a new dev user is created on the fly. {@code currency} is an ISO-4217 code (3 upper
 * letters); defaults to INR when omitted.
 */
public record CreateAccountRequest(
        @Email String email,
        @Pattern(regexp = "[A-Z]{3}", message = "currency must be a 3-letter ISO code") String currency
) {
}
