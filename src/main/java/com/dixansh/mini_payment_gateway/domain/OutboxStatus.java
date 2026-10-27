package com.dixansh.mini_payment_gateway.domain;

/**
 * Lifecycle of an outbox row. {@code NEW} = written alongside its payment, not yet shipped;
 * {@code SENT} = the poller has published it to Kafka. Maps to the Postgres native enum
 * {@code outbox_status}.
 */
public enum OutboxStatus {
    NEW,
    SENT
}
