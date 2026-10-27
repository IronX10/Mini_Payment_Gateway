package com.dixansh.mini_payment_gateway.service;

import com.dixansh.mini_payment_gateway.domain.OutboxEvent;
import com.dixansh.mini_payment_gateway.repository.OutboxEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Writes outbox rows. {@link #record} is {@code Propagation.MANDATORY} on purpose: an event may
 * only be recorded as part of an already-open business transaction (the payment). If someone calls
 * it without a surrounding transaction it fails loudly rather than silently persisting an event in
 * its own transaction - which would reintroduce the very "event without a committed payment" race
 * the outbox exists to prevent.
 */
@Service
public class OutboxService {

    private final OutboxEventRepository outboxEventRepository;

    public OutboxService(OutboxEventRepository outboxEventRepository) {
        this.outboxEventRepository = outboxEventRepository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID aggregateId, String eventType, String payloadJson) {
        outboxEventRepository.save(new OutboxEvent(aggregateId, eventType, payloadJson));
    }
}
