package com.dixansh.mini_payment_gateway.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the Kafka topics so {@code KafkaAdmin} creates them on startup (when a broker is
 * reachable). Single partition here for demo determinism; in production you'd partition
 * {@code payment.events} by transaction id for ordered, parallel consumption.
 */
@Configuration
public class KafkaTopicConfig {

    public static final String PAYMENT_EVENTS = "payment.events";
    public static final String PAYMENT_EVENTS_DLT = "payment.events.dlt";

    @Bean
    public NewTopic paymentEventsTopic() {
        return TopicBuilder.name(PAYMENT_EVENTS).partitions(1).replicas(1).build();
    }

    /** Dead-letter topic: deliveries that exhaust their retries are mirrored here for inspection. */
    @Bean
    public NewTopic paymentEventsDltTopic() {
        return TopicBuilder.name(PAYMENT_EVENTS_DLT).partitions(1).replicas(1).build();
    }
}
