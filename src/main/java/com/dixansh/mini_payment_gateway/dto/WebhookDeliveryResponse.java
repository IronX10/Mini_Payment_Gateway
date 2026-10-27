package com.dixansh.mini_payment_gateway.dto;

import com.dixansh.mini_payment_gateway.domain.WebhookDelivery;

import java.time.Instant;

/** Delivery view for the failed-webhook admin endpoint. */
public record WebhookDeliveryResponse(
        Long id,
        String eventId,
        Long endpointId,
        int attempt,
        String status,
        Integer lastResponseCode,
        Instant nextRetryAt,
        Instant updatedAt
) {
    public static WebhookDeliveryResponse from(WebhookDelivery d) {
        return new WebhookDeliveryResponse(
                d.getId(),
                d.getEventId().toString(),
                d.getEndpointId(),
                d.getAttempt(),
                d.getStatus().name(),
                d.getLastResponseCode(),
                d.getNextRetryAt(),
                d.getUpdatedAt());
    }
}
