package com.dixansh.mini_payment_gateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * The delivery state of one event to one endpoint. There is exactly one row per
 * {@code (event_id, endpoint_id)} pair (enforced by a UNIQUE constraint) - that uniqueness is the
 * consumer-side dedupe gate, because the Kafka pipeline is at-least-once and the same event may be
 * redelivered.
 *
 * <p>{@code attempt} counts tries; on failure {@code next_retry_at} schedules the next one with
 * exponential backoff + jitter until {@code attempt} hits the cap, at which point the row becomes
 * {@code DEAD}.
 */
@Entity
@Table(name = "webhook_deliveries")
@Getter
@Setter
@NoArgsConstructor
public class WebhookDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "endpoint_id", nullable = false)
    private Long endpointId;

    @Column(nullable = false)
    private int attempt = 0;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", columnDefinition = "delivery_status", nullable = false)
    private DeliveryStatus status = DeliveryStatus.PENDING;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "last_response_code")
    private Integer lastResponseCode;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public WebhookDelivery(UUID eventId, Long endpointId) {
        this.eventId = eventId;
        this.endpointId = endpointId;
        this.attempt = 0;
        this.status = DeliveryStatus.PENDING;
    }
}
