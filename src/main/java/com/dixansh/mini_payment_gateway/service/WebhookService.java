package com.dixansh.mini_payment_gateway.service;

import com.dixansh.mini_payment_gateway.domain.DeliveryStatus;
import com.dixansh.mini_payment_gateway.domain.WebhookDelivery;
import com.dixansh.mini_payment_gateway.domain.WebhookEndpoint;
import com.dixansh.mini_payment_gateway.repository.WebhookDeliveryRepository;
import com.dixansh.mini_payment_gateway.repository.WebhookEndpointRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Orchestrates webhook delivery on top of the transactional units in {@link WebhookDeliveryExecutor}.
 * Deliberately NOT {@code @Transactional}: a drain performs many independent HTTP calls, each in its
 * own short transaction, so a slow or failing merchant never holds a database transaction (or row
 * lock) open across the whole batch.
 */
@Service
public class WebhookService {

    /** Safety cap so a single drain can't spin forever on a deep backlog. */
    private static final int MAX_DRAIN_PER_CALL = 200;

    private final WebhookDeliveryExecutor executor;
    private final WebhookEndpointRepository endpointRepository;
    private final WebhookDeliveryRepository deliveryRepository;

    public WebhookService(WebhookDeliveryExecutor executor,
                          WebhookEndpointRepository endpointRepository,
                          WebhookDeliveryRepository deliveryRepository) {
        this.executor = executor;
        this.endpointRepository = endpointRepository;
        this.deliveryRepository = deliveryRepository;
    }

    /**
     * Handles one consumed event: create a delivery row per registered endpoint (idempotently), then
     * best-effort drain so delivery happens immediately rather than waiting for the retry tick.
     */
    public void handleEvent(UUID eventId, UUID aggregateId) {
        List<WebhookEndpoint> endpoints = executor.endpointsForAggregate(aggregateId);
        for (WebhookEndpoint endpoint : endpoints) {
            executor.createIfAbsent(eventId, endpoint.getId());
        }
        drainDue();
    }

    /**
     * Attempts every currently-due delivery, one short transaction each. Returns how many rows were
     * processed. Shared by the inline path (right after consuming) and the retry scheduler.
     */
    public int drainDue() {
        int processed = 0;
        while (processed < MAX_DRAIN_PER_CALL && executor.deliverOneDue()) {
            processed++;
        }
        return processed;
    }

    @Transactional
    public WebhookEndpoint registerEndpoint(Long userId, String url, String secret) {
        return endpointRepository.save(new WebhookEndpoint(userId, url, secret));
    }

    @Transactional(readOnly = true)
    public List<WebhookDelivery> failedDeliveries() {
        return deliveryRepository.findByStatusOrderByUpdatedAtDesc(DeliveryStatus.DEAD);
    }
}
