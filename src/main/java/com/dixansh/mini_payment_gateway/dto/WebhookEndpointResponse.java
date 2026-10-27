package com.dixansh.mini_payment_gateway.dto;

import com.dixansh.mini_payment_gateway.domain.WebhookEndpoint;

import java.time.Instant;

/** Webhook endpoint view. The {@code secret} is never echoed back. */
public record WebhookEndpointResponse(
        Long id,
        Long userId,
        String url,
        boolean active,
        Instant createdAt
) {
    public static WebhookEndpointResponse from(WebhookEndpoint e) {
        return new WebhookEndpointResponse(e.getId(), e.getUserId(), e.getUrl(), e.isActive(), e.getCreatedAt());
    }
}
