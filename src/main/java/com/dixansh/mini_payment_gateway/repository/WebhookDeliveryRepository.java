package com.dixansh.mini_payment_gateway.repository;

import com.dixansh.mini_payment_gateway.domain.DeliveryStatus;
import com.dixansh.mini_payment_gateway.domain.WebhookDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, Long> {

    boolean existsByEventIdAndEndpointId(UUID eventId, Long endpointId);

    /**
     * Claims deliveries that are owed an attempt and whose backoff has elapsed - both fresh
     * {@code PENDING} rows and {@code RETRYING} ones past their {@code next_retry_at}. The
     * {@code FOR UPDATE SKIP LOCKED} lets multiple workers (and app instances) each take a disjoint
     * slice without blocking, and holds the lock only for the row being worked.
     */
    @Query(value = """
            SELECT * FROM webhook_deliveries
            WHERE status IN ('PENDING', 'RETRYING') AND next_retry_at <= :now
            ORDER BY next_retry_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<WebhookDelivery> claimDue(@Param("now") Instant now, @Param("limit") int limit);

    List<WebhookDelivery> findByStatusOrderByUpdatedAtDesc(DeliveryStatus status);
}
