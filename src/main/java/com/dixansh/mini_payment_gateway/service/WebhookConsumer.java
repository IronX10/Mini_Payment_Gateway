package com.dixansh.mini_payment_gateway.service;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Consumes {@code payment.events} and drives webhook delivery. It reads the raw
 * {@link ConsumerRecord} rather than binding {@code @Header} params: Kafka headers arrive as
 * {@code byte[]}, so decoding them explicitly avoids any header-converter ambiguity. The record key
 * is the aggregate (transaction) id; the event id rides in the {@code event-id} header. The body is
 * never parsed here - it's rebuilt from the durable outbox row when delivering.
 *
 * <p>The pipeline is <em>at-least-once</em>: a rebalance or redelivery can hand us the same event
 * twice, so {@link WebhookService#handleEvent} is idempotent (UNIQUE (event, endpoint) dedupes).
 */
@Component
public class WebhookConsumer {

    private static final Logger log = LoggerFactory.getLogger(WebhookConsumer.class);

    private final WebhookService webhookService;

    public WebhookConsumer(WebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @KafkaListener(
            topics = "${mpg.outbox.topic:payment.events}",
            groupId = "${mpg.kafka.consumer-group:mpg-webhooks}")
    public void onEvent(ConsumerRecord<String, String> record) {
        String aggregateId = record.key();
        String eventId = headerValue(record, "event-id");
        if (eventId == null || aggregateId == null) {
            log.warn("skipping event with missing routing metadata (event-id={}, key={})", eventId, aggregateId);
            return;
        }
        log.debug("consumed event {} (aggregate {})", eventId, aggregateId);
        webhookService.handleEvent(UUID.fromString(eventId), UUID.fromString(aggregateId));
    }

    private static String headerValue(ConsumerRecord<String, String> record, String key) {
        Header header = record.headers().lastHeader(key);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
