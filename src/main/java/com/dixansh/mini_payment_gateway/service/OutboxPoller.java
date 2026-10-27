package com.dixansh.mini_payment_gateway.service;

import com.dixansh.mini_payment_gateway.config.OutboxProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives {@link OutboxRelay#publishBatch()} on a fixed cadence. Kept separate from the relay so the
 * scheduling concern (a {@code @Scheduled} tick) stays out of the transactional publish logic, and
 * so the relay can be unit-tested without a scheduler running.
 *
 * <p>Drains greedily: while a full batch comes back there is more backlog, so it keeps going within
 * the same tick instead of waiting {@code pollMs} between batches. A guard caps the drain so a huge
 * backlog can't monopolise the scheduler thread.
 */
@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);
    private static final int MAX_BATCHES_PER_TICK = 10;

    private final OutboxRelay outboxRelay;
    private final OutboxProperties properties;

    public OutboxPoller(OutboxRelay outboxRelay, OutboxProperties properties) {
        this.outboxRelay = outboxRelay;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${mpg.outbox.poll-ms:500}")
    public void poll() {
        try {
            int batches = 0;
            int published;
            do {
                published = outboxRelay.publishBatch();
            } while (published == properties.getBatchSize() && ++batches < MAX_BATCHES_PER_TICK);
        } catch (Exception e) {
            // Never let a bad tick kill the scheduler; NEW rows survive for the next run.
            log.warn("outbox poll failed, will retry next tick: {}", e.getMessage());
        }
    }
}
