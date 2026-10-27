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

import java.time.Instant;

/**
 * A merchant's registered webhook target. {@code secret} is the per-endpoint HMAC key: every
 * delivery carries {@code X-Signature: sha256=HMAC-SHA256(secret, body)} so the merchant can verify
 * the call really came from us and wasn't tampered with in flight.
 */
@Entity
@Table(name = "webhook_endpoints")
@Getter
@Setter
@NoArgsConstructor
public class WebhookEndpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String url;

    @Column(nullable = false)
    private String secret;

    @Column(nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public WebhookEndpoint(Long userId, String url, String secret) {
        this.userId = userId;
        this.url = url;
        this.secret = secret;
        this.active = true;
    }
}
