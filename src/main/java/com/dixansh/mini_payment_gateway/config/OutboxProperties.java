package com.dixansh.mini_payment_gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tunables for the outbox relay. Bound from {@code mpg.outbox.*}.
 */
@Component
@ConfigurationProperties(prefix = "mpg.outbox")
public class OutboxProperties {

    /** How often the relay polls for NEW rows, in milliseconds. */
    private long pollMs = 500;

    /** Max rows claimed per poll. Caps how much one tick can publish (and lock). */
    private int batchSize = 100;

    /** Kafka topic events are published to. */
    private String topic = "payment.events";

    public long getPollMs() {
        return pollMs;
    }

    public void setPollMs(long pollMs) {
        this.pollMs = pollMs;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }
}
