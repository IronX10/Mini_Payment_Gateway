package com.dixansh.mini_payment_gateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Register a merchant webhook endpoint. {@code secret} is the HMAC key the gateway will sign
 * deliveries with; the merchant stores the same value to verify {@code X-Signature}.
 */
public record WebhookEndpointRequest(
        @NotNull Long userId,
        @NotBlank @Pattern(regexp = "^https?://.+", message = "url must be http(s)") String url,
        @NotBlank String secret
) {
}
